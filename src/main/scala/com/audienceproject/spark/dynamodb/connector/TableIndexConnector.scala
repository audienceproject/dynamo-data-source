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

private[dynamodb] class TableIndexConnector(tableName: String, indexName: String, totalSegments: Int, parameters: Map[String, String])
    extends DynamoConnector with Serializable {

    private val consistentRead = parameters.getOrElse("stronglyConsistentReads", "false").toBoolean
    private val filterPushdown = parameters.getOrElse("filterPushdown", "true").toBoolean

    override val (keySchema, readLimit, itemLimit, totalSizeInBytes) = {
        val table = getDynamoDB.getTable(tableName)
        val indexDesc = table.describe().getGlobalSecondaryIndexes.asScala.find(_.getIndexName == indexName).get

        // Key schema.
        val keySchema = KeySchema.fromDescription(indexDesc.getKeySchema.asScala)

        // Parameters.
        val bytesPerRCU = parameters.getOrElse("bytesPerRCU", "4000").toInt
        val targetCapacity = parameters.getOrElse("targetCapacity", "1").toDouble
        val readFactor = if (consistentRead) 1 else 2

        // Rate limit calculation.
        val tableSize = indexDesc.getIndexSizeBytes
        val avgItemSize = tableSize.toDouble / indexDesc.getItemCount
        val readCapacity = indexDesc.getProvisionedThroughput.getReadCapacityUnits * targetCapacity

        val rateLimit = readCapacity / totalSegments
        val itemLimit = ((bytesPerRCU / avgItemSize * rateLimit).toInt * readFactor) max 1

        (keySchema, rateLimit, itemLimit, tableSize.toLong)
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

        getDynamoDB.getTable(tableName).getIndex(indexName).scan(scanSpec)
    }

}
