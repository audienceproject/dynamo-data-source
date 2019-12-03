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

import java.util.Optional

import org.apache.spark.sql.sources.DataSourceRegister
import org.apache.spark.sql.sources.v2.reader.DataSourceReader
import org.apache.spark.sql.sources.v2.writer.DataSourceWriter
import org.apache.spark.sql.sources.v2.{DataSourceOptions, ReadSupport, WriteSupport}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class DefaultSource extends ReadSupport with WriteSupport with DataSourceRegister {

    private val logger = LoggerFactory.getLogger(this.getClass)

    override def createReader(schema: StructType, options: DataSourceOptions): DataSourceReader = {
        val optionsMap = options.asMap().asScala
        val defaultParallelism = optionsMap.get("defaultparallelism").map(_.toInt).getOrElse(getDefaultParallelism)
        new DynamoDataSourceReader(defaultParallelism, Map(optionsMap.toSeq: _*), Some(schema))
    }

    override def createReader(options: DataSourceOptions): DataSourceReader = {
        val optionsMap = options.asMap().asScala
        val defaultParallelism = optionsMap.get("defaultparallelism").map(_.toInt).getOrElse(getDefaultParallelism)
        new DynamoDataSourceReader(defaultParallelism, Map(optionsMap.toSeq: _*))
    }

    override def createWriter(writeUUID: String, schema: StructType, mode: SaveMode, options: DataSourceOptions): Optional[DataSourceWriter] = {
        if (mode == SaveMode.Append || mode == SaveMode.Overwrite)
            throw new IllegalArgumentException(s"DynamoDB data source does not support save modes ($mode)." +
                " Please use option 'update' (true | false) to differentiate between append/overwrite and append/update behavior.")
        val optionsMap = options.asMap().asScala
        val defaultParallelism = optionsMap.get("defaultparallelism").map(_.toInt).getOrElse(getDefaultParallelism)
        val writer = new DynamoDataSourceWriter(defaultParallelism, Map(optionsMap.toSeq: _*), schema)
        Optional.of(writer)
    }

    override def shortName(): String = "dynamodb"

    private def getDefaultParallelism: Int =
        SparkSession.getActiveSession match {
            case Some(spark) => spark.sparkContext.defaultParallelism
            case None =>
                logger.warn("Unable to read defaultParallelism from SparkSession." +
                    " Parallelism will be 1 unless overwritten with option `defaultParallelism`")
                1
        }

}
