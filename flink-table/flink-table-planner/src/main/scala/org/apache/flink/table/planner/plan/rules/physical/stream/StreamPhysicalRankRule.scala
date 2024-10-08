/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.planner.plan.rules.physical.stream

import org.apache.flink.table.planner.plan.`trait`.FlinkRelDistribution
import org.apache.flink.table.planner.plan.nodes.FlinkConventions
import org.apache.flink.table.planner.plan.nodes.logical.FlinkLogicalRank
import org.apache.flink.table.planner.plan.nodes.physical.stream.StreamPhysicalRank
import org.apache.flink.table.planner.plan.utils.{RankProcessStrategy, RankUtil}

import org.apache.calcite.plan.RelOptRule
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.convert.ConverterRule
import org.apache.calcite.rel.convert.ConverterRule.Config

/** Rule that converts [[FlinkLogicalRank]] with fetch to [[StreamPhysicalRank]]. */
class StreamPhysicalRankRule(config: Config) extends ConverterRule(config) {

  override def convert(rel: RelNode): RelNode = {
    val rank = rel.asInstanceOf[FlinkLogicalRank]
    val input = rank.getInput
    val requiredDistribution = if (!rank.partitionKey.isEmpty) {
      FlinkRelDistribution.hash(rank.partitionKey.toList)
    } else {
      FlinkRelDistribution.SINGLETON
    }
    val requiredTraitSet = input.getTraitSet
      .replace(FlinkConventions.STREAM_PHYSICAL)
      .replace(requiredDistribution)
    val providedTraitSet = rank.getTraitSet.replace(FlinkConventions.STREAM_PHYSICAL)
    val newInput: RelNode = RelOptRule.convert(input, requiredTraitSet)

    val sortOnRowTime = RankUtil.sortOnRowTime(rank.orderKey, input.getRowType)

    new StreamPhysicalRank(
      rank.getCluster,
      providedTraitSet,
      newInput,
      rank.partitionKey,
      rank.orderKey,
      rank.rankType,
      rank.rankRange,
      rank.rankNumberType,
      rank.outputRankNumber,
      RankProcessStrategy.UNDEFINED_STRATEGY,
      sortOnRowTime)
  }
}

object StreamPhysicalRankRule {
  val INSTANCE: RelOptRule = new StreamPhysicalRankRule(
    Config.INSTANCE.withConversion(
      classOf[FlinkLogicalRank],
      FlinkConventions.LOGICAL,
      FlinkConventions.STREAM_PHYSICAL,
      "StreamPhysicalRankRule"))
}
