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
package com.audienceproject.spark.dynamodb.rdd

import com.audienceproject.spark.dynamodb.connector.TableConnector
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources.{BaseRelation, TableScan}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

private[dynamodb] class DynamoUpdateRelation(data: DataFrame, parameters: Map[String, String])
                                            (@transient val sqlContext: SQLContext)
    extends BaseRelation with Serializable
        with TableScan {

    private val tableName = parameters("tableName")
    private val numPartitions = data.rdd.getNumPartitions
    private val connector = new TableConnector(tableName, numPartitions, parameters)

    override val schema: StructType = data.schema

    override def buildScan(): RDD[Row] = data.rdd

    def write(): Unit = {
        data.foreachPartition(connector.updateItems(schema) _)
    }

}
