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
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBAsyncClientBuilder, AmazonDynamoDBClientBuilder}
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, ItemCollection, ScanOutcome}
import org.apache.spark.sql.sources.Filter
import com.amazon.dax.client.dynamodbv2.AmazonDaxClientBuilder
import com.amazonaws.regions.DefaultAwsRegionProviderChain

private[dynamodb] trait DynamoConnector {

    def getDynamoDB(parameters: Map[String, String] = Map.empty): DynamoDB = {
        val client: AmazonDynamoDB = getDynamoDBClient(parameters)
        new DynamoDB(client)
    }


    def getDynamoDBClient(parameters: Map[String, String] = Map.empty) = {
        val builder = AmazonDynamoDBClientBuilder.standard()
        val credentials = Option(System.getProperty("aws.profile"))
            .map(new ProfileCredentialsProvider(_))
            .getOrElse(DefaultAWSCredentialsProviderChain.getInstance())

        val region: String = parameters.get("region").orElse(sys.env.get("aws.dynamodb.region")).getOrElse(new DefaultAwsRegionProviderChain().getRegion)
        builder.withCredentials(credentials)

        Option(System.getProperty("aws.dynamodb.endpoint")).map(endpoint => {
            builder
                .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
        }).getOrElse(
            builder.withRegion(region)).build()

    }

    def getDynamoDBAsyncClient(parameters: Map[String, String] = Map.empty) = {
        val builder = AmazonDynamoDBAsyncClientBuilder.standard()
        val credentials = Option(System.getProperty("aws.profile"))
            .map(new ProfileCredentialsProvider(_))
            .getOrElse(DefaultAWSCredentialsProviderChain.getInstance())

        val region: String = parameters.get("region").orElse(sys.env.get("aws.dynamodb.region")).getOrElse(new DefaultAwsRegionProviderChain().getRegion)
        builder.withCredentials(credentials)

        Option(System.getProperty("aws.dynamodb.endpoint")).map(endpoint => {
            builder
                .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
        }).getOrElse(
            builder.withRegion(region)).build()

    }


    val keySchema: KeySchema

    val readLimit: Double

    val itemLimit: Int

    val totalSizeInBytes: Long

    def scan(segmentNum: Int, columns: Seq[String], filters: Seq[Filter]): ItemCollection[ScanOutcome]

    def isEmpty: Boolean = itemLimit == 0

    def nonEmpty: Boolean = !isEmpty

}
