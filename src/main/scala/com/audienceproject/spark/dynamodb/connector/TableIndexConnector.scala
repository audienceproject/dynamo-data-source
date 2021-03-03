/**
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements.  See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership.  The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
  * with the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  * KIND, either express or implied.  See the License for the
  * specific language governing permissions and limitations
  * under the License.
  *
  * Copyright © 2018 AudienceProject. All rights reserved.
  */
package com.audienceproject.spark.dynamodb.connector

import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec
import com.amazonaws.services.dynamodbv2.document.{ItemCollection, ScanOutcome}
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity
import com.amazonaws.services.dynamodbv2.xspec.ExpressionSpecBuilder
import org.apache.spark.sql.sources.Filter

import scala.collection.JavaConverters._

private[dynamodb] class TableIndexConnector(tableName: String, indexName: String, parallelism: Int, parameters: Map[String, String])
    extends DynamoConnector with Serializable {

    private val consistentRead = parameters.getOrElse("stronglyConsistentReads", "false").toBoolean
    private val filterPushdown = parameters.getOrElse("filterPushdown", "true").toBoolean
    private val region = parameters.get("region")
    private val roleArn = parameters.get("roleArn")
    private val providerClassName = parameters.get("providerclassname")

    override val filterPushdownEnabled: Boolean = filterPushdown

    override val daxEndpoint: String = parameters.getOrElse("daxendpoint", "").trim

    override val (keySchema, readLimit, itemLimit, totalSegments) = {
        val table = getDynamoDB(region, roleArn, providerClassName).getTable(tableName)
        val indexDesc = table.describe().getGlobalSecondaryIndexes.asScala.find(_.getIndexName == indexName).get

        // Key schema.
        val keySchema = KeySchema.fromDescription(indexDesc.getKeySchema.asScala)

        // User parameters.
        val bytesPerRCU = parameters.getOrElse("bytesPerRCU", "4000").toInt
        val maxPartitionBytes = parameters.getOrElse("maxpartitionbytes", "128000000").toInt
        val targetCapacity = parameters.getOrElse("targetCapacity", "1").toDouble
        val readFactor = if (consistentRead) 1 else 2

        // Table parameters.
        val indexSize = indexDesc.getIndexSizeBytes
        val itemCount = indexDesc.getItemCount

        // Partitioning calculation.
        val numPartitions = parameters.get("readpartitions").map(_.toInt).getOrElse({
            val sizeBased = (indexSize / maxPartitionBytes).toInt max 1
            val remainder = sizeBased % parallelism
            if (remainder > 0) sizeBased + (parallelism - remainder)
            else sizeBased
        })

        // Provisioned or on-demand throughput.
        val readThroughput = parameters.getOrElse("throughput", Option(indexDesc.getProvisionedThroughput.getReadCapacityUnits)
            .filter(_ > 0).map(_.longValue().toString)
            .getOrElse("100")).toLong

        // Rate limit calculation.
        val avgItemSize = indexSize.toDouble / itemCount
        val readCapacity = readThroughput * targetCapacity

        val rateLimit = readCapacity / parallelism
        val itemLimit = ((bytesPerRCU / avgItemSize * rateLimit).toInt * readFactor) max 1

        (keySchema, rateLimit, itemLimit, numPartitions)
    }

    override def scan(segmentNum: Int, columns: Seq[String], filters: Seq[Filter]): ItemCollection[ScanOutcome] = {
        val scanSpec = new ScanSpec()
            .withSegment(segmentNum)
            .withTotalSegments(totalSegments)
            .withMaxPageSize(itemLimit)
            .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
            .withConsistentRead(consistentRead)

        if (columns.nonEmpty) {
            val xspec = new ExpressionSpecBuilder().addProjections(columns: _*)

            if (filters.nonEmpty && filterPushdown) {
                xspec.withCondition(FilterPushdown(filters))
            }

            scanSpec.withExpressionSpec(xspec.buildForScan())
        }

        getDynamoDB(region, roleArn, providerClassName).getTable(tableName).getIndex(indexName).scan(scanSpec)
    }

}
