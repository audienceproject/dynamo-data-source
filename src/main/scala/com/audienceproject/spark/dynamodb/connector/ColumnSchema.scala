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
package com.audienceproject.spark.dynamodb.connector

import org.apache.spark.sql.types.{DataType, StructType}

private[dynamodb] class ColumnSchema(keySchema: KeySchema,
                                     sparkSchema: StructType) {

    type Attr = (String, Int, DataType)

    private val columnNames = sparkSchema.map(_.name)

    private val keyIndices = keySchema match {
        case KeySchema(hashKey, None) =>
            val hashKeyIndex = columnNames.indexOf(hashKey)
            val hashKeyType = sparkSchema(hashKey).dataType
            Left(hashKey, hashKeyIndex, hashKeyType)
        case KeySchema(hashKey, Some(rangeKey)) =>
            val hashKeyIndex = columnNames.indexOf(hashKey)
            val rangeKeyIndex = columnNames.indexOf(rangeKey)
            val hashKeyType = sparkSchema(hashKey).dataType
            val rangeKeyType = sparkSchema(rangeKey).dataType
            Right((hashKey, hashKeyIndex, hashKeyType), (rangeKey, rangeKeyIndex, rangeKeyType))
    }

    private val attributeIndices = columnNames.zipWithIndex.filterNot({
        case (name, _) => keySchema match {
            case KeySchema(hashKey, None) => name == hashKey
            case KeySchema(hashKey, Some(rangeKey)) => name == hashKey || name == rangeKey
        }
    }).map({
        case (name, index) => (name, index, sparkSchema(name).dataType)
    })

    def keys(): Either[Attr, (Attr, Attr)] = keyIndices

    def attributes(): Seq[Attr] = attributeIndices

}
