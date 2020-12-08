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
package com.audienceproject.spark.dynamodb.reflect

import com.audienceproject.spark.dynamodb.attribute
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.types.{Metadata, MetadataBuilder, StructField, StructType}

import scala.reflect.ClassTag
import scala.reflect.runtime.{universe => ru}

/**
  * Uses reflection to perform a static analysis that can derive a Spark schema from a case class of type `T`.
  */
private[dynamodb] object SchemaAnalysis {

    def apply[T <: Product : ClassTag : ru.TypeTag]: (StructType, Map[String, String]) = {

        val runtimeMirror = ru.runtimeMirror(getClass.getClassLoader)

        val classObj = scala.reflect.classTag[T].runtimeClass
        val classSymbol = runtimeMirror.classSymbol(classObj)

        val sparkFieldsWithAliasMap = classSymbol.primaryConstructor.typeSignature.paramLists.head.map(field => {
            val sparkType = ScalaReflection.schemaFor(field.typeSignature).dataType

            // Black magic from here:
            // https://stackoverflow.com/questions/23046958/accessing-an-annotation-value-in-scala
            val attrName = field.annotations.collectFirst({
                case ann: ru.AnnotationApi if ann.tree.tpe =:= ru.typeOf[attribute] =>
                    ann.tree.children.tail.collectFirst({
                        case ru.Literal(ru.Constant(name: String)) => name
                    })
            }).flatten

            if (attrName.isDefined) {
                (StructField(attrName.get, sparkType, nullable = true), Option(attrName.get -> field.name.toString))
            } else {
                (StructField(field.name.toString, sparkType, nullable = true), Option.empty)
            }
        })

        (StructType(sparkFieldsWithAliasMap.map(_._1)), sparkFieldsWithAliasMap.map(_._2).filter(_.isDefined).map(_.get).toMap)

    }

}
