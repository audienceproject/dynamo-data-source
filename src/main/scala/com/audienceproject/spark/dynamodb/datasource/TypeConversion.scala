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

import com.amazonaws.services.dynamodbv2.document.Item
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.{ArrayBasedMapData, GenericArrayData}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

import scala.collection.JavaConverters._

private[dynamodb] object TypeConversion {

    def apply(attrName: String, sparkType: DataType): Item => Any =

        sparkType match {
            case BooleanType => nullableGet(_.getBOOL)(attrName)
            case StringType => nullableGet(item => attrName => UTF8String.fromString(item.getString(attrName)))(attrName)
            case IntegerType => nullableGet(_.getInt)(attrName)
            case LongType => nullableGet(_.getLong)(attrName)
            case DoubleType => nullableGet(_.getDouble)(attrName)
            case FloatType => nullableGet(_.getFloat)(attrName)
            case BinaryType => nullableGet(_.getBinary)(attrName)
            case DecimalType() => nullableGet(_.getNumber)(attrName)
            case ArrayType(innerType, _) =>
                nullableGet(_.getList)(attrName).andThen(extractArray(convertValue(innerType)))
            case MapType(keyType, valueType, _) =>
                if (keyType != StringType) throw new IllegalArgumentException(s"Invalid Map key type '${keyType.typeName}'. DynamoDB only supports String as Map key type.")
                nullableGet(_.getRawMap)(attrName).andThen(extractMap(convertValue(valueType)))
            case StructType(fields) =>
                val nestedConversions = fields.collect({ case StructField(name, dataType, _, _) => name -> convertValue(dataType) })
                nullableGet(_.getRawMap)(attrName).andThen(extractStruct(nestedConversions))
            case _ => throw new IllegalArgumentException(s"Spark DataType '${sparkType.typeName}' could not be mapped to a corresponding DynamoDB data type.")
        }

    private val stringConverter = (value: Any) => UTF8String.fromString(value.asInstanceOf[String])

    private def convertValue(sparkType: DataType): Any => Any =

        sparkType match {
            case IntegerType => nullableConvert(_.intValue())
            case LongType => nullableConvert(_.longValue())
            case DoubleType => nullableConvert(_.doubleValue())
            case FloatType => nullableConvert(_.floatValue())
            case DecimalType() => nullableConvert(identity)
            case ArrayType(innerType, _) => extractArray(convertValue(innerType))
            case MapType(keyType, valueType, _) =>
                if (keyType != StringType) throw new IllegalArgumentException(s"Invalid Map key type '${keyType.typeName}'. DynamoDB only supports String as Map key type.")
                extractMap(convertValue(valueType))
            case StructType(fields) =>
                val nestedConversions = fields.collect({ case StructField(name, dataType, _, _) => name -> convertValue(dataType) })
                extractStruct(nestedConversions)
            case BooleanType => {
                case boolean: Boolean => boolean
                case _ => null
            }
            case StringType => {
                case string: String => UTF8String.fromString(string)
                case _ => null
            }
            case BinaryType => {
                case byteArray: Array[Byte] => byteArray
                case _ => null
            }
            case _ => throw new IllegalArgumentException(s"Spark DataType '${sparkType.typeName}' could not be mapped to a corresponding DynamoDB data type.")
        }

    private def nullableGet(getter: Item => String => Any)(attrName: String): Item => Any = {
        case item if item.hasAttribute(attrName) => try getter(item)(attrName) catch {
            case _: NumberFormatException => null
        }
        case _ => null
    }

    private def nullableConvert(converter: java.math.BigDecimal => Any): Any => Any = {
        case item: java.math.BigDecimal => converter(item)
        case _ => null
    }

    private def extractArray(converter: Any => Any): Any => Any = {
        case list: java.util.List[_] => new GenericArrayData(list.asScala.map(converter))
        case set: java.util.Set[_] => new GenericArrayData(set.asScala.map(converter).toSeq)
        case _ => null
    }

    private def extractMap(converter: Any => Any): Any => Any = {
        case map: java.util.Map[_, _] => ArrayBasedMapData(map, stringConverter, converter)
        case _ => null
    }

    private def extractStruct(conversions: Seq[(String, Any => Any)]): Any => Any = {
        case map: java.util.Map[_, _] => InternalRow.fromSeq(conversions.map({
            case (name, conv) => conv(map.get(name))
        }))
        case _ => null
    }

}
