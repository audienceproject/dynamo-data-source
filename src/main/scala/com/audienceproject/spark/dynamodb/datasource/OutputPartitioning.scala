package com.audienceproject.spark.dynamodb.datasource

import org.apache.spark.sql.sources.v2.reader.partitioning.{Distribution, Partitioning}

class OutputPartitioning(override val numPartitions: Int) extends Partitioning {

    override def satisfy(distribution: Distribution): Boolean = false

}
