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
  * Copyright © 2019 AudienceProject. All rights reserved.
  */
package com.audienceproject.spark.dynamodb.datasource

import java.util

import com.audienceproject.spark.dynamodb.connector.{FilterPushdown, TableConnector, TableIndexConnector}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.sources.v2.reader.partitioning.Partitioning
import org.apache.spark.sql.types._

import scala.collection.JavaConverters._

class DynamoDataSourceReader(parallelism: Int,
                             parameters: Map[String, String],
                             userSchema: Option[StructType] = None)
    extends DataSourceReader
        with SupportsPushDownRequiredColumns
        with SupportsPushDownFilters
        with SupportsReportPartitioning {

    private val tableName = parameters("tablename")
    private val indexName = parameters.get("indexName")

    private val dynamoConnector =
        if (indexName.isDefined) new TableIndexConnector(tableName, indexName.get, parallelism, parameters)
        else new TableConnector(tableName, parallelism, parameters)

    private var acceptedFilters: Array[Filter] = Array.empty
    private var currentSchema: StructType = _

    override val outputPartitioning: Partitioning = new OutputPartitioning(dynamoConnector.totalSegments)

    override def readSchema(): StructType = {
        if (currentSchema == null)
            currentSchema = userSchema.getOrElse(inferSchema())
        currentSchema
    }

    override def planInputPartitions(): util.List[InputPartition[InternalRow]] = {
        val inputPartitions = new util.ArrayList[InputPartition[InternalRow]]
        for (partitionIndex <- 0 until dynamoConnector.totalSegments) {
            inputPartitions.add(new ScanPartition(readSchema(), partitionIndex, dynamoConnector, acceptedFilters))
        }
        inputPartitions
    }

    override def pruneColumns(requiredSchema: StructType): Unit = {
        val schema = readSchema()
        val keyFields = Seq(Some(dynamoConnector.keySchema.hashKeyName), dynamoConnector.keySchema.rangeKeyName).flatten
            .flatMap(keyName => schema.fields.find(_.name == keyName))
        val requiredFields = keyFields ++ requiredSchema.fields
        val newFields = readSchema().fields.filter(requiredFields.contains)
        currentSchema = StructType(newFields)
    }

    override def pushFilters(filters: Array[Filter]): Array[Filter] = {
        if (dynamoConnector.filterPushdownEnabled) {
            val (acceptedFilters, postScanFilters) = FilterPushdown.acceptFilters(filters)
            this.acceptedFilters = acceptedFilters
            postScanFilters // Return filters that need to be evaluated after scanning.
        } else filters
    }

    override def pushedFilters(): Array[Filter] = acceptedFilters

    private def inferSchema(): StructType = {
        val inferenceItems =
            if (dynamoConnector.nonEmpty) dynamoConnector.scan(0, Seq.empty, Seq.empty).firstPage().getLowLevelResult.getItems.asScala
            else Seq.empty

        val typeMapping = inferenceItems.foldLeft(Map[String, DataType]())({
            case (map, item) => map ++ item.asMap().asScala.mapValues(inferType)
        })
        val typeSeq = typeMapping.map({ case (name, sparkType) => StructField(name, sparkType) }).toSeq

        if (typeSeq.size > 100) throw new RuntimeException("Schema inference not possible, too many attributes in table.")

        StructType(typeSeq)
    }

    private def inferType(value: Any): DataType = value match {
        case number: java.math.BigDecimal =>
            if (number.scale() == 0) {
                if (number.precision() < 10) IntegerType
                else if (number.precision() < 19) LongType
                else DataTypes.createDecimalType(number.precision(), number.scale())
            }
            else DoubleType
        case list: java.util.ArrayList[_] =>
            if (list.isEmpty) ArrayType(StringType)
            else ArrayType(inferType(list.get(0)))
        case set: java.util.Set[_] =>
            if (set.isEmpty) ArrayType(StringType)
            else ArrayType(inferType(set.iterator().next()))
        case map: java.util.Map[String, _] =>
            val mapFields = (for ((fieldName, fieldValue) <- map.asScala) yield {
                StructField(fieldName, inferType(fieldValue))
            }).toSeq
            StructType(mapFields)
        case _: java.lang.Boolean => BooleanType
        case _: Array[Byte] => BinaryType
        case _ => StringType
    }

}
