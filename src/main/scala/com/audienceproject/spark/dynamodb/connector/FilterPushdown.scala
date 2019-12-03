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

import com.amazonaws.services.dynamodbv2.xspec.ExpressionSpecBuilder.{BOOL => newBOOL, N => newN, S => newS, _}
import com.amazonaws.services.dynamodbv2.xspec._
import org.apache.spark.sql.sources._

private[dynamodb] object FilterPushdown {

    def apply(filters: Seq[Filter]): Condition =
        filters.map(buildCondition).map(parenthesize).reduce[Condition](_ and _)

    /**
      * Accepts only filters that would be considered valid input to FilterPushdown.apply()
      *
      * @param filters input list which may contain both valid and invalid filters
      * @return a (valid, invalid) partitioning of the input filters
      */
    def acceptFilters(filters: Array[Filter]): (Array[Filter], Array[Filter]) =
        filters.partition(checkFilter)

    private def checkFilter(filter: Filter): Boolean = filter match {
        case _: StringEndsWith => false
        case And(left, right) => checkFilter(left) && checkFilter(right)
        case Or(left, right) => checkFilter(left) && checkFilter(right)
        case Not(f) => checkFilter(f)
        case _ => true
    }

    private def buildCondition(filter: Filter): Condition = filter match {
        case EqualTo(path, value: Boolean) => newBOOL(path).eq(value)
        case EqualTo(path, value) => coerceAndApply(_ eq _, _ eq _)(path, value)

        case GreaterThan(path, value) => coerceAndApply(_ gt _, _ gt _)(path, value)
        case GreaterThanOrEqual(path, value) => coerceAndApply(_ ge _, _ ge _)(path, value)

        case LessThan(path, value) => coerceAndApply(_ lt _, _ lt _)(path, value)
        case LessThanOrEqual(path, value) => coerceAndApply(_ le _, _ le _)(path, value)

        case In(path, values) =>
            val valueList = values.toList
            valueList match {
                case (_: String) :: _ => newS(path).in(valueList.asInstanceOf[List[String]]: _*)
                case (_: Boolean) :: _ => newBOOL(path).in(valueList.asInstanceOf[List[Boolean]]: _*)
                case (_: Int) :: _ => newN(path).in(valueList.map(_.asInstanceOf[Number]): _*)
                case (_: Long) :: _ => newN(path).in(valueList.map(_.asInstanceOf[Number]): _*)
                case (_: Short) :: _ => newN(path).in(valueList.map(_.asInstanceOf[Number]): _*)
                case (_: Float) :: _ => newN(path).in(valueList.map(_.asInstanceOf[Number]): _*)
                case (_: Double) :: _ => newN(path).in(valueList.map(_.asInstanceOf[Number]): _*)
                case Nil => throw new IllegalArgumentException("Unable to apply `In` filter with empty value list")
                case _ => throw new IllegalArgumentException(s"Type of values supplied to `In` filter on attribute $path not supported by filter pushdown")
            }

        case IsNull(path) => attribute_not_exists(path)
        case IsNotNull(path) => attribute_exists(path)

        case StringStartsWith(path, value) => newS(path).beginsWith(value)
        case StringContains(path, value) => newS(path).contains(value)
        case StringEndsWith(_, _) => throw new UnsupportedOperationException("Filter `StringEndsWith` is not supported by DynamoDB")

        case And(left, right) => parenthesize(buildCondition(left)) and parenthesize(buildCondition(right))
        case Or(left, right) => parenthesize(buildCondition(left)) or parenthesize(buildCondition(right))
        case Not(f) => parenthesize(buildCondition(f)).negate()
    }

    private def coerceAndApply(stringOp: (S, String) => Condition, numOp: (N, Number) => Condition)
                              (path: String, value: Any): Condition = value match {
        case string: String => stringOp(newS(path), string)
        case number: Int => numOp(newN(path), number)
        case number: Long => numOp(newN(path), number)
        case number: Short => numOp(newN(path), number)
        case number: Float => numOp(newN(path), number)
        case number: Double => numOp(newN(path), number)
        case _ => throw new IllegalArgumentException(s"Type of operand given to filter on attribute $path not supported by filter pushdown")
    }

}
