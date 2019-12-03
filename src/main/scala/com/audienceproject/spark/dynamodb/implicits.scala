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
package com.audienceproject.spark.dynamodb

import com.audienceproject.spark.dynamodb.reflect.SchemaAnalysis
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.StructField

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

object implicits {

    implicit class DynamoDBDataFrameReader(reader: DataFrameReader) {

        def dynamodb(tableName: String): DataFrame =
            getDynamoDBSource(tableName).load()

        def dynamodb(tableName: String, indexName: String): DataFrame =
            getDynamoDBSource(tableName).option("indexName", indexName).load()

        def dynamodbAs[T <: Product : ClassTag : TypeTag](tableName: String): Dataset[T] = {
            implicit val encoder: Encoder[T] = ExpressionEncoder()
            getColumnsAlias(getDynamoDBSource(tableName)
                .schema(SchemaAnalysis[T]).load()).as
        }

        def dynamodbAs[T <: Product : ClassTag : TypeTag](tableName: String, indexName: String): Dataset[T] = {
            implicit val encoder: Encoder[T] = ExpressionEncoder()
            getColumnsAlias(getDynamoDBSource(tableName)
                .option("indexName", indexName)
                .schema(SchemaAnalysis[T]).load()).as
        }

        private def getDynamoDBSource(tableName: String): DataFrameReader =
            reader.format("com.audienceproject.spark.dynamodb.datasource").option("tableName", tableName)

        private def getColumnsAlias(dataFrame: DataFrame): DataFrame = {
            val columnsAlias = dataFrame.schema.collect({
                case StructField(name, _, _, metadata) if metadata.contains("alias") =>
                    col(name).as(metadata.getString("alias"))
                case StructField(name, _, _, _) =>
                    col(name)
            })
            dataFrame.select(columnsAlias: _*)
        }

    }

    implicit class DynamoDBDataFrameWriter[T](writer: DataFrameWriter[T]) {

        def dynamodb(tableName: String): Unit =
            writer.format("com.audienceproject.spark.dynamodb.datasource").option("tableName", tableName).save()

    }

}
