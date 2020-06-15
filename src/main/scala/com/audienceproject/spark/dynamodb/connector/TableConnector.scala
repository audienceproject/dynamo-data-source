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
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._

private[dynamodb] class TableConnector(tableName: String, parallelism: Int, parameters: Map[String, String])
    extends DynamoConnector with DynamoWritable with Serializable {

    private val consistentRead = parameters.getOrElse("stronglyconsistentreads", "false").toBoolean
    private val filterPushdown = parameters.getOrElse("filterpushdown", "true").toBoolean
    private val region = parameters.get("region")
    private val roleArn = parameters.get("rolearn")
    private val maxRetries = parameters.getOrElse("maxretries", "3").toInt
    override val filterPushdownEnabled: Boolean = filterPushdown
    private val logger = LoggerFactory.getLogger(this.getClass)
    override val (keySchema, readLimit, writeLimit, itemLimit, totalSegments) = {
        val table = getDynamoDB(region, roleArn).getTable(tableName)
        val desc = table.describe()

        // Key schema.
        val keySchema = KeySchema.fromDescription(desc.getKeySchema.asScala)

        // User parameters.
        val bytesPerRCU = parameters.getOrElse("bytesperrcu", "4000").toInt
        val maxPartitionBytes = parameters.getOrElse("maxpartitionbytes", "128000000").toInt
        val targetCapacity = parameters.getOrElse("targetcapacity", "1").toDouble
        val readFactor = if (consistentRead) 1 else 2
        //Write parallelisation parameter. depends on number of input partitions the Data Frame is distributed in.
        //This can be passed by using numInputDFPartitions option.
        //By default it is chosen tobe spark's default parallelism
        val numTasks = parameters.getOrElse("numinputdfpartitions", parallelism.toString).toInt
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
        //If information about absolute throughput is provided
        var readCapacity = parameters.getOrElse("absread", "-1").toDouble
        var writeCapacity = parameters.getOrElse("abswrite", "-1").toDouble
        // Else
        if(readCapacity < 0) {
            val readThroughput = parameters.getOrElse("throughput", Option(desc.getProvisionedThroughput.getReadCapacityUnits)
                .filter(_ > 0).map(_.longValue().toString)
                .getOrElse("100")).toLong
            readCapacity = readThroughput * targetCapacity
        }
        if(writeCapacity < 0) {
            val writeThroughput = parameters.getOrElse("throughput", Option(desc.getProvisionedThroughput.getWriteCapacityUnits)
                .filter(_ > 0).map(_.longValue().toString)
                .getOrElse("100")).toLong
            // Rate limit calculation.
            writeCapacity = writeThroughput * targetCapacity
        }
        //Calculating write limit for each task, based on number of parallel tasks, target capacity, and WCU limit
        val writeLimit = writeCapacity / numTasks
        val readLimit = readCapacity / parallelism
        val avgItemSize = tableSize.toDouble / itemCount
        val itemLimit = ((bytesPerRCU / avgItemSize * readLimit).toInt * readFactor) max 1

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

        getDynamoDB(region, roleArn).getTable(tableName).scan(scanSpec)
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
        handleBatchWriteResponse(client, rateLimiter)(response, 0)
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
        handleBatchWriteResponse(client, rateLimiter)(response, 0)
    }

    @tailrec
    private def handleBatchWriteResponse(client: DynamoDB, rateLimiter: RateLimiter)
                                        (response: BatchWriteItemOutcome, retries: Int): Unit = {
        // Rate limit on write capacity.
        if (response.getBatchWriteItemResult.getConsumedCapacity != null) {
            response.getBatchWriteItemResult.getConsumedCapacity.asScala.map(cap => {
                cap.getTableName -> cap.getCapacityUnits.toInt
            }).toMap.get(tableName).foreach(units => rateLimiter.acquire(units max 1))
        }
        // Retry unprocessed items.
        if (response.getUnprocessedItems != null && !response.getUnprocessedItems.isEmpty) {
            println("Unprocessed items found")
            if (retries < maxRetries) {
                val newResponse = client.batchWriteItemUnprocessed(response.getUnprocessedItems)
                handleBatchWriteResponse(client, rateLimiter)(newResponse, retries + 1)
            }
            else{
                val unprocessed = response.getUnprocessedItems
                //logging about unprocessed items
                unprocessed.asScala.foreach(keyValue =>
                    logger.info("Maximum retiries reached while writing items to the DynamoDB." +
                                "Number of unprocessed items of table \"" + keyValue._1 +"\" = " +
                                keyValue._2.asScala.length)
                )

            }
        }
    }

}
