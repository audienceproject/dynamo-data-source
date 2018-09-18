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

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBClientBuilder}
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, ItemCollection, ScanOutcome}
import org.apache.spark.sql.sources.Filter

private[dynamodb] trait DynamoConnector {

    def getDynamoDB: DynamoDB = {
        val client: AmazonDynamoDB = getDynamoDBClient
        new DynamoDB(client)
    }

    def getDynamoDBClient = {
        Option(System.getProperty("aws.dynamodb.endpoint")).map(endpoint => {
            val region = sys.env.getOrElse("aws.dynamodb.region", "us-east-1")
            val credentials = Option(System.getProperty("aws.profile"))
                .map(new ProfileCredentialsProvider(_))
                .getOrElse(new DefaultAWSCredentialsProviderChain)
            AmazonDynamoDBClientBuilder.standard()
                .withCredentials(credentials)
                .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
                .build()
        }).getOrElse(AmazonDynamoDBClientBuilder.defaultClient())
    }

    val keySchema: KeySchema

    val readLimit: Double

    val itemLimit: Int

    val totalSizeInBytes: Long

    def scan(segmentNum: Int, columns: Seq[String], filters: Seq[Filter]): ItemCollection[ScanOutcome]

    def isEmpty: Boolean = itemLimit == 0

    def nonEmpty: Boolean = !isEmpty

}
