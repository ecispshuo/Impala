// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.planner;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.analysis.JoinOperator;
import com.cloudera.impala.analysis.Predicate;
import com.cloudera.impala.analysis.SlotDescriptor;
import com.cloudera.impala.analysis.SlotId;
import com.cloudera.impala.analysis.SlotRef;
import com.cloudera.impala.catalog.ColumnStats;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.Pair;
import com.cloudera.impala.thrift.TEqJoinCondition;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.THashJoinNode;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Hash join between left child and right child.
 * The right child must be a leaf node, ie, can only materialize
 * a single input tuple.
 *
 */
public class HashJoinNode extends PlanNode {
  private final static Logger LOG = LoggerFactory.getLogger(HashJoinNode.class);

  private final JoinOperator joinOp;
  // conjuncts of the form "<lhs> = <rhs>", recorded as Pair(<lhs>, <rhs>)
  private final List<Pair<Expr, Expr> > eqJoinConjuncts;

  // join conjuncts from the JOIN clause that aren't equi-join predicates
  private final List<Predicate> otherJoinConjuncts;

  public HashJoinNode(
      PlanNodeId id, PlanNode outer, PlanNode inner, JoinOperator joinOp,
      List<Pair<Expr, Expr> > eqJoinConjuncts, List<Predicate> otherJoinConjuncts) {
    super(id);
    Preconditions.checkArgument(eqJoinConjuncts != null);
    Preconditions.checkArgument(otherJoinConjuncts != null);
    tupleIds.addAll(outer.getTupleIds());
    tupleIds.addAll(inner.getTupleIds());
    this.joinOp = joinOp;
    this.eqJoinConjuncts = eqJoinConjuncts;
    this.otherJoinConjuncts = otherJoinConjuncts;
    children.add(outer);
    children.add(inner);

    // Inherits all the nullable tuple from the children
    // Mark tuples that form the "nullable" side of the outer join as nullable.
    nullableTupleIds.addAll(inner.getNullableTupleIds());
    nullableTupleIds.addAll(outer.getNullableTupleIds());
    if (joinOp.equals(JoinOperator.FULL_OUTER_JOIN)) {
      nullableTupleIds.addAll(outer.getTupleIds());
      nullableTupleIds.addAll(inner.getTupleIds());
    } else if (joinOp.equals(JoinOperator.LEFT_OUTER_JOIN)) {
      nullableTupleIds.addAll(inner.getTupleIds());
    } else if (joinOp.equals(JoinOperator.RIGHT_OUTER_JOIN)) {
      nullableTupleIds.addAll(outer.getTupleIds());
    }
  }

  @Override
  public void finalize(Analyzer analyzer) throws InternalException {
    super.finalize(analyzer);

    // For a join between child(0) and child(1), we look for join conditions "L.c = R.d"
    // (with L being from child(0) and R from child(1)) and use as the cardinality
    // estimate the maximum of
    //   child(0).cardinality * R.cardinality / # distinct values for R.d
    //     * child(1).cardinality / R.cardinality
    // across all suitable join conditions, which simplifies to
    //   child(0).cardinality * child(1).cardinality / # distinct values for R.d
    // The reasoning is that
    // - each row in child(0) joins with R.cardinality/#DV_R.d rows in R
    // - each row in R is 'present' in child(1).cardinality / R.cardinality rows in
    //   child(1)
    //
    // This handles the very frequent case of a fact table/dimension table join
    // (aka foreign key/primary key join) if the primary key is a single column, with
    // possible additional predicates against the dimension table. An example:
    // FROM FactTbl F JOIN Customers C D ON (F.cust_id = C.id) ... WHERE C.region = 'US'
    // - if there are 5 regions, the selectivity of "C.region = 'US'" would be 0.2
    //   and the output cardinality of the Customers scan would be 0.2 * # rows in
    //   Customers
    // - # rows in Customers == # of distinct values for Customers.id
    // - the output cardinality of the join would be F.cardinality * 0.2

    long maxNumDistinct = 0;
    for (Pair<Expr, Expr> eqJoinPredicate: eqJoinConjuncts) {
      if (eqJoinPredicate.first.unwrapSlotRef() == null) {
        continue;
      }
      SlotRef rhsSlotRef = eqJoinPredicate.second.unwrapSlotRef();
      if (rhsSlotRef == null) {
        continue;
      }
      SlotDescriptor slotDesc = rhsSlotRef.getDesc();
      if (slotDesc == null) {
        continue;
      }
      ColumnStats stats = slotDesc.getStats();
      if (!stats.hasNumDistinctValues()) {
        continue;
      }
      maxNumDistinct = Math.max(maxNumDistinct, stats.getNumDistinctValues());
    }

    if (maxNumDistinct == 0) {
      // if we didn't find any suitable join predicates or don't have stats
      // on the relevant columns, we very optimistically assume we're doing an
      // FK/PK join (which doesn't alter the cardinality of the left-hand side)
      cardinality = getChild(0).cardinality;
    } else {
      cardinality = Math.round(
          (double) getChild(0).cardinality
            * (double) getChild(1).cardinality / (double) maxNumDistinct);
    }
    LOG.info("finalize HashJoin: cardinality=" + Long.toString(cardinality));
  }

  @Override
  protected String debugString() {
    return Objects.toStringHelper(this)
        .add("eqJoinConjuncts", eqJoinConjunctsDebugString())
        .addValue(super.debugString())
        .toString();
  }

  private String eqJoinConjunctsDebugString() {
    Objects.ToStringHelper helper = Objects.toStringHelper(this);
    for (Pair<Expr, Expr> entry: eqJoinConjuncts) {
      helper.add("lhs" , entry.first).add("rhs", entry.second);
    }
    return helper.toString();
  }

  @Override
  public void getMaterializedIds(Analyzer analyzer, List<SlotId> ids) {
    super.getMaterializedIds(analyzer, ids);
    // we also need to materialize everything referenced by eqJoinConjuncts
    // and otherJoinConjuncts
    for (Pair<Expr, Expr> p: eqJoinConjuncts) {
      p.first.getIds(null, ids);
      p.second.getIds(null, ids);
    }
    for (Predicate p: otherJoinConjuncts) {
      p.getIds(null, ids);
    }
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    msg.node_type = TPlanNodeType.HASH_JOIN_NODE;
    msg.hash_join_node = new THashJoinNode();
    msg.hash_join_node.join_op = joinOp.toThrift();
    for (Pair<Expr, Expr> entry: eqJoinConjuncts) {
      TEqJoinCondition eqJoinCondition =
          new TEqJoinCondition(entry.first.treeToThrift(), entry.second.treeToThrift());
      msg.hash_join_node.addToEq_join_conjuncts(eqJoinCondition);
    }
    for (Predicate p: otherJoinConjuncts) {
      msg.hash_join_node.addToOther_join_conjuncts(p.treeToThrift());
    }
  }

  @Override
  protected String getExplainString(String prefix, TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder()
        .append(prefix + "HASH JOIN\n")
        .append(prefix + "  JOIN OP: " + joinOp.toString() + "\n")
        .append(prefix + "  HASH PREDICATES:");
    for (Pair<Expr, Expr> entry: eqJoinConjuncts) {
      output.append(
          "\n" + prefix + "    " + entry.first.toSql() + " = " + entry.second.toSql());
    }
    output.append("\n");
    if (!otherJoinConjuncts.isEmpty()) {
      output.append(prefix + "  OTHER JOIN PREDICATES: ")
          .append(getExplainString(otherJoinConjuncts) + "\n");
    }
    if (!conjuncts.isEmpty()) {
      output.append(prefix + "  OTHER PREDICATES: ")
          .append(getExplainString(conjuncts) + "\n");
    }
    output.append(super.getExplainString(prefix + "  ", detailLevel))
        .append(getChild(0).getExplainString(prefix + "    ", detailLevel))
        .append(getChild(1).getExplainString(prefix + "    ", detailLevel));
    return output.toString();
  }
}
