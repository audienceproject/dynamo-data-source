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

import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.audienceproject.shaded.google.common.util.concurrent.RateLimiter
import com.audienceproject.spark.dynamodb.connector.{ColumnSchema, TableConnector}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connector.write.{DataWriter, WriterCommitMessage}

import scala.collection.mutable.ArrayBuffer

class DynamoDataWriter(batchSize: Int,
                       columnSchema: ColumnSchema,
                       connector: TableConnector,
                       client: DynamoDB)
    extends DataWriter[InternalRow] {

    protected val buffer: ArrayBuffer[InternalRow] = new ArrayBuffer[InternalRow](batchSize)
    protected val rateLimiter: RateLimiter = RateLimiter.create(connector.writeLimit)

    override def write(record: InternalRow): Unit = {
        buffer += record.copy()
        if (buffer.size == batchSize) {
            flush()
        }
    }

    override def commit(): WriterCommitMessage = {
        flush()
        new WriterCommitMessage {}
    }

    override def abort(): Unit = {}

    override def close(): Unit = {
        buffer.clear()
        client.shutdown()
    }

    protected def flush(): Unit = {
        if (buffer.nonEmpty) {
            connector.putItems(columnSchema, buffer)(client, rateLimiter)
            buffer.clear()
        }
    }

}
