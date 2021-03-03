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

import java.net.URI

import com.amazon.dax.client.dynamodbv2.{AmazonDaxAsyncClientBuilder, AmazonDaxClientBuilder}
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{AWSCredentialsProvider, AWSStaticCredentialsProvider, BasicSessionCredentials, DefaultAWSCredentialsProviderChain}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.dynamodbv2.document.{DynamoDB, ItemCollection, ScanOutcome}
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBAsync, AmazonDynamoDBAsyncClientBuilder, AmazonDynamoDBClientBuilder}
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest
import org.apache.spark.sql.sources.Filter
import org.slf4j.LoggerFactory

private[dynamodb] trait DynamoConnector {
    private val logger = LoggerFactory.getLogger(this.getClass)

    @transient private lazy val properties = sys.props

    def getDynamoDB(region: Option[String] = None, roleArn: Option[String] = None,
                    providerClassName: Option[String] = None, omitDax: Boolean = false): DynamoDB = {
        val client: AmazonDynamoDB = getDynamoDBClient(region, roleArn, providerClassName, omitDax)
        new DynamoDB(client)
    }

    private def getDynamoDBClient(region: Option[String] = None,
                                  roleArn: Option[String] = None,
                                  providerClassName: Option[String],
                                  omitDax: Boolean = false): AmazonDynamoDB = {
        val chosenRegion = region.getOrElse(properties.getOrElse("aws.dynamodb.region", "us-east-1"))
        val credentials = getCredentials(chosenRegion, roleArn, providerClassName)

        if (omitDax || daxEndpoint.isEmpty) {
            logger.info("NOT using DAX")
            properties.get("aws.dynamodb.endpoint").map(endpoint => {
                logger.debug(s"Using DynamoDB endpoint ${endpoint}")
                AmazonDynamoDBClientBuilder.standard()
                    .withCredentials(credentials)
                    .withEndpointConfiguration(new EndpointConfiguration(endpoint, chosenRegion))
                    .build()
            }).getOrElse(
                AmazonDynamoDBClientBuilder.standard()
                    .withCredentials(credentials)
                    .withRegion(chosenRegion)
                    .build()
            )
        } else {
            logger.debug(s"Using DAX endpoint ${daxEndpoint}")
            AmazonDaxClientBuilder.standard()
                .withEndpointConfiguration(daxEndpoint)
                .withCredentials(credentials)
                .withRegion(chosenRegion)
                .build()
        }

    }

    def getDynamoDBAsyncClient(region: Option[String] = None,
                               roleArn: Option[String] = None,
                               providerClassName: Option[String] = None,
                               omitDax: Boolean = false): AmazonDynamoDBAsync = {
        val chosenRegion = region.getOrElse(properties.getOrElse("aws.dynamodb.region", "us-east-1"))
        val credentials = getCredentials(chosenRegion, roleArn, providerClassName)

        if (omitDax || daxEndpoint.isEmpty) {
            properties.get("aws.dynamodb.endpoint").map(endpoint => {
                logger.debug(s"Using DynamoDB endpoint ${endpoint}")
                AmazonDynamoDBAsyncClientBuilder.standard()
                    .withCredentials(credentials)
                    .withEndpointConfiguration(new EndpointConfiguration(endpoint, chosenRegion))
                    .build()
            }).getOrElse(
                AmazonDynamoDBAsyncClientBuilder.standard()
                    .withCredentials(credentials)
                    .withRegion(chosenRegion)
                    .build()
            )
        } else {
            logger.debug(s"Using DAX endpoint ${daxEndpoint}")
            AmazonDaxAsyncClientBuilder.standard()
                .withEndpointConfiguration(daxEndpoint)
                .withCredentials(credentials)
                .withRegion(chosenRegion)
                .build()
        }
    }

    /**
     * Get credentials from an instantiated object of the class name given
     * or a passed in arn
     * or from profile
     * or return the default credential provider
     **/
    private def getCredentials(chosenRegion: String, roleArn: Option[String], providerClassName: Option[String]) = {
        providerClassName.map(providerClass => {
            Class.forName(providerClass).newInstance.asInstanceOf[AWSCredentialsProvider]
        }).orElse(roleArn.map(arn => {
            val stsClient = properties.get("aws.sts.endpoint").map(endpoint => {
                AWSSecurityTokenServiceClientBuilder
                    .standard()
                    .withCredentials(new DefaultAWSCredentialsProviderChain)
                    .withEndpointConfiguration(new EndpointConfiguration(endpoint, chosenRegion))
                    .build()
            }).getOrElse(
                // STS without an endpoint will sign from the region, but use the global endpoint
                AWSSecurityTokenServiceClientBuilder
                    .standard()
                    .withCredentials(new DefaultAWSCredentialsProviderChain)
                    .withRegion(chosenRegion)
                    .build()
            )
            val assumeRoleResult = stsClient.assumeRole(
                new AssumeRoleRequest()
                    .withRoleSessionName("DynamoDBAssumed")
                    .withRoleArn(arn)
            )
            val stsCredentials = assumeRoleResult.getCredentials
            val assumeCreds = new BasicSessionCredentials(
                stsCredentials.getAccessKeyId,
                stsCredentials.getSecretAccessKey,
                stsCredentials.getSessionToken
            )
            new AWSStaticCredentialsProvider(assumeCreds)
        })).orElse(properties.get("aws.profile").map(new ProfileCredentialsProvider(_)))
            .getOrElse(new DefaultAWSCredentialsProviderChain)
    }

    val keySchema: KeySchema

    val readLimit: Double

    val itemLimit: Int

    val totalSegments: Int

    val filterPushdownEnabled: Boolean

    val daxEndpoint: String

    def scan(segmentNum: Int, columns: Seq[String], filters: Seq[Filter]): ItemCollection[ScanOutcome]

    def isEmpty: Boolean = itemLimit == 0

    def nonEmpty: Boolean = !isEmpty

}
