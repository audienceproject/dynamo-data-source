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

import com.audienceproject.spark.dynamodb.connector.TableConnector
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.v2.writer.{DataSourceWriter, DataWriterFactory, WriterCommitMessage}
import org.apache.spark.sql.types.StructType

class DynamoDataSourceWriter(parallelism: Int, parameters: Map[String, String], schema: StructType)
    extends DataSourceWriter {

    private val tableName = parameters("tablename")
    private val dynamoConnector = new TableConnector(tableName, parallelism, parameters)

    override def createWriterFactory(): DataWriterFactory[InternalRow] =
        new DynamoWriterFactory(dynamoConnector, parameters, schema)

    override def commit(messages: Array[WriterCommitMessage]): Unit = {}

    override def abort(messages: Array[WriterCommitMessage]): Unit = {}

}
