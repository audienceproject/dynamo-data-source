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

import com.amazonaws.services.dynamodbv2.document._
import com.amazonaws.services.dynamodbv2.document.spec.{BatchWriteItemSpec, ScanSpec, UpdateItemSpec}
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity
import com.amazonaws.services.dynamodbv2.xspec.ExpressionSpecBuilder
import com.audienceproject.shaded.google.common.util.concurrent.RateLimiter
import com.audienceproject.spark.dynamodb.catalyst.JavaConverter
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.Filter

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
 *
 * @param tableName
 * @param parallelism
 * @param parameters case sensitive Map, all keys have been lowercased
 */
private[dynamodb] class TableConnector(tableName: String, parallelism: Int, parameters: Map[String, String])
    extends DynamoConnector with DynamoWritable with Serializable {

    private val consistentRead = parameters.getOrElse("stronglyconsistentreads", "false").toBoolean
    private val filterPushdown = parameters.getOrElse("filterpushdown", "true").toBoolean
    private val region = parameters.get("region")
    private val roleArn = parameters.get("rolearn")
    private val providerClassName = parameters.get("providerclassname")

    override val filterPushdownEnabled: Boolean = filterPushdown

    override val daxEndpoint: String = parameters.getOrElse("daxendpoint", "").trim

    override val (keySchema, readLimit, writeLimit, itemLimit, totalSegments) = {
        val table = getDynamoDB(region, roleArn, providerClassName, omitDax = true).getTable(tableName)
        val desc = table.describe()

        // Key schema.
        val keySchema = KeySchema.fromDescription(desc.getKeySchema.asScala)

        // User parameters.
        val bytesPerRCU = parameters.getOrElse("bytesperrcu", "4000").toInt
        val maxPartitionBytes = parameters.getOrElse("maxpartitionbytes", "128000000").toInt
        val targetCapacity = parameters.getOrElse("targetcapacity", "1").toDouble
        val readFactor = if (consistentRead) 1 else 2

        // Table parameters.
        val tableSize = desc.getTableSizeBytes
        val itemCount = desc.getItemCount

        // Partitioning calculation.
        val numPartitions = parameters.get("readpartitions").map(_.toInt).getOrElse({
            val sizeBased = (tableSize / maxPartitionBytes).toInt max 1
            val remainder = sizeBased % parallelism
            if (remainder > 0) sizeBased + (parallelism - remainder)
            else sizeBased
        })

        // Provisioned or on-demand throughput.
        val readThroughput = parameters.getOrElse("throughput", Option(desc.getProvisionedThroughput.getReadCapacityUnits)
            .filter(_ > 0).map(_.longValue().toString)
            .getOrElse("100")).toLong
        val writeThroughput = parameters.getOrElse("throughput", Option(desc.getProvisionedThroughput.getWriteCapacityUnits)
            .filter(_ > 0).map(_.longValue().toString)
            .getOrElse("100")).toLong

        // Rate limit calculation.
        val avgItemSize = tableSize.toDouble / itemCount
        val readCapacity = readThroughput * targetCapacity
        val writeCapacity = writeThroughput * targetCapacity

        val readLimit = readCapacity / parallelism
        val itemLimit = ((bytesPerRCU / avgItemSize * readLimit).toInt * readFactor) max 1

        val writeLimit = writeCapacity / parallelism

        (keySchema, readLimit, writeLimit, itemLimit, numPartitions)
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

        getDynamoDB(region, roleArn, providerClassName).getTable(tableName).scan(scanSpec)
    }

    override def putItems(columnSchema: ColumnSchema, items: Seq[InternalRow])
                         (client: DynamoDB, rateLimiter: RateLimiter): Unit = {
        // For each batch.
        val batchWriteItemSpec = new BatchWriteItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
        batchWriteItemSpec.withTableWriteItems(new TableWriteItems(tableName).withItemsToPut(
            // Map the items.
            items.map(row => {
                val item = new Item()

                // Map primary key.
                columnSchema.keys() match {
                    case Left((hashKey, hashKeyIndex, hashKeyType)) =>
                        item.withPrimaryKey(hashKey, JavaConverter.convertRowValue(row, hashKeyIndex, hashKeyType))
                    case Right(((hashKey, hashKeyIndex, hashKeyType), (rangeKey, rangeKeyIndex, rangeKeyType))) =>
                        val hashKeyValue = JavaConverter.convertRowValue(row, hashKeyIndex, hashKeyType)
                        val rangeKeyValue = JavaConverter.convertRowValue(row, rangeKeyIndex, rangeKeyType)
                        item.withPrimaryKey(hashKey, hashKeyValue, rangeKey, rangeKeyValue)
                }

                // Map remaining columns.
                columnSchema.attributes().foreach({
                    case (name, index, dataType) if !row.isNullAt(index) =>
                        item.`with`(name, JavaConverter.convertRowValue(row, index, dataType))
                    case _ =>
                })

                item
            }): _*
        ))

        val response = client.batchWriteItem(batchWriteItemSpec)
        handleBatchWriteResponse(client, rateLimiter)(response)
    }

    override def updateItem(columnSchema: ColumnSchema, row: InternalRow)
                           (client: DynamoDB, rateLimiter: RateLimiter): Unit = {
        val updateItemSpec = new UpdateItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)

        // Map primary key.
        columnSchema.keys() match {
            case Left((hashKey, hashKeyIndex, hashKeyType)) =>
                updateItemSpec.withPrimaryKey(hashKey, JavaConverter.convertRowValue(row, hashKeyIndex, hashKeyType))
            case Right(((hashKey, hashKeyIndex, hashKeyType), (rangeKey, rangeKeyIndex, rangeKeyType))) =>
                val hashKeyValue = JavaConverter.convertRowValue(row, hashKeyIndex, hashKeyType)
                val rangeKeyValue = JavaConverter.convertRowValue(row, rangeKeyIndex, rangeKeyType)
                updateItemSpec.withPrimaryKey(hashKey, hashKeyValue, rangeKey, rangeKeyValue)
        }

        // Map remaining columns.
        val attributeUpdates = columnSchema.attributes().collect({
            case (name, index, dataType) if !row.isNullAt(index) =>
                new AttributeUpdate(name).put(JavaConverter.convertRowValue(row, index, dataType))
        })

        updateItemSpec.withAttributeUpdate(attributeUpdates: _*)

        // Update item and rate limit on write capacity.
        val response = client.getTable(tableName).updateItem(updateItemSpec)
        Option(response.getUpdateItemResult.getConsumedCapacity)
            .foreach(cap => rateLimiter.acquire(cap.getCapacityUnits.toInt max 1))
    }

    override def deleteItems(columnSchema: ColumnSchema, items: Seq[InternalRow])
                            (client: DynamoDB, rateLimiter: RateLimiter): Unit = {
        // For each batch.
        val batchWriteItemSpec = new BatchWriteItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)

        val tableWriteItems = new TableWriteItems(tableName)
        val tableWriteItemsWithItems: TableWriteItems =
        // Check if hash key only or also range key.
            columnSchema.keys() match {
                case Left((hashKey, hashKeyIndex, hashKeyType)) =>
                    val hashKeys = items.map(row =>
                        JavaConverter.convertRowValue(row, hashKeyIndex, hashKeyType).asInstanceOf[AnyRef])
                    tableWriteItems.withHashOnlyKeysToDelete(hashKey, hashKeys: _*)
                case Right(((hashKey, hashKeyIndex, hashKeyType), (rangeKey, rangeKeyIndex, rangeKeyType))) =>
                    val alternatingHashAndRangeKeys = items.flatMap { row =>
                        val hashKeyValue = JavaConverter.convertRowValue(row, hashKeyIndex, hashKeyType)
                        val rangeKeyValue = JavaConverter.convertRowValue(row, rangeKeyIndex, rangeKeyType)
                        Seq(hashKeyValue.asInstanceOf[AnyRef], rangeKeyValue.asInstanceOf[AnyRef])
                    }
                    tableWriteItems.withHashAndRangeKeysToDelete(hashKey, rangeKey, alternatingHashAndRangeKeys: _*)
            }

        batchWriteItemSpec.withTableWriteItems(tableWriteItemsWithItems)

        val response = client.batchWriteItem(batchWriteItemSpec)
        handleBatchWriteResponse(client, rateLimiter)(response)
    }

    @tailrec
    private def handleBatchWriteResponse(client: DynamoDB, rateLimiter: RateLimiter)
                                        (response: BatchWriteItemOutcome): Unit = {
        // Rate limit on write capacity.
        if (response.getBatchWriteItemResult.getConsumedCapacity != null) {
            response.getBatchWriteItemResult.getConsumedCapacity.asScala.map(cap => {
                cap.getTableName -> cap.getCapacityUnits.toInt
            }).toMap.get(tableName).foreach(units => rateLimiter.acquire(units max 1))
        }
        // Retry unprocessed items.
        if (response.getUnprocessedItems != null && !response.getUnprocessedItems.isEmpty) {
            val newResponse = client.batchWriteItemUnprocessed(response.getUnprocessedItems)
            handleBatchWriteResponse(client, rateLimiter)(newResponse)
        }
    }

}
