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

import java.io.File
import java.util.jar.JarFile

import com.audienceproject.spark.dynamodb.rdd.{DynamoRelation, DynamoUpdateRelation, DynamoWriteRelation}
import com.google.common.base.Charsets
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode}

class DefaultSource extends RelationProvider
    with SchemaRelationProvider
    with CreatableRelationProvider {

    val logger: Logger = LoggerFactory.getLogger(this.getClass.getName)

    override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation = {
        createRelation(sqlContext, parameters, null)
    }

    override def createRelation(sqlContext: SQLContext, parameters: Map[String, String],
                                schema: StructType): BaseRelation = {
        logger.info(s"Using Guava version $getGuavaVersion")
        new DynamoRelation(schema, parameters)(sqlContext)
    }

    override def createRelation(sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String],
                                data: DataFrame): BaseRelation = {
        logger.info(s"Using Guava version $getGuavaVersion")
        val writeData =
            if (parameters.get("writePartitions").contains("skip")) data
            else data.repartition(parameters.get("writePartitions").map(_.toInt).getOrElse(sqlContext.sparkContext.defaultParallelism))

        if (parameters.getOrElse("update","false").toBoolean) {
            val updateRelation = new DynamoUpdateRelation(writeData, parameters)(sqlContext)
            updateRelation.write()
            updateRelation
        } else {
            val writeRelation= new DynamoWriteRelation(writeData, parameters)(sqlContext)
            writeRelation.write()
            writeRelation
        }

    }

    private def getGuavaVersion: String = try {
        val file = new File(classOf[Charsets].getProtectionDomain.getCodeSource.getLocation.toURI)
        val jar = new JarFile(file)
        try
            jar.getManifest.getMainAttributes.getValue("Bundle-Version")
        finally if (jar != null) jar.close()
    } catch {
        case ex: Exception => throw new RuntimeException("Unable to get the version of Guava", ex)
    }

}
