# Spark+DynamoDB
Plug-and-play implementation of an Apache Spark custom data source for AWS DynamoDB.

We published a small article about the project, check it out here:
https://www.audienceproject.com/blog/tech/sparkdynamodb-using-aws-dynamodb-data-source-apache-spark/

## News

* 2021-01-28: Added option `inferSchema=false` which is useful when writing to a table with many columns
* 2020-07-23: Releasing version 1.1.0 which supports Spark 3.0.0 and Scala 2.12. Future releases will no longer be compatible with Scala 2.11 and Spark 2.x.x.
* 2020-04-28: Releasing version 1.0.4. Includes support for assuming AWS roles through custom STS endpoint (credits @jhulten).
* 2020-04-09: We are releasing version 1.0.3 of the Spark+DynamoDB connector. Added option to `delete` records (thank you @rhelmstetter). Fixes (thank you @juanyunism for #46).
* 2019-11-25: We are releasing version 1.0.0 of the Spark+DynamoDB connector, which is based on the Spark Data Source V2 API. Out-of-the-box throughput calculations, parallelism and partition planning should now be more reliable. We have also pulled out the external dependency on Guava, which was causing a lot of compatibility issues.

## Features

- Distributed, parallel scan with lazy evaluation
- Throughput control by rate limiting on target fraction of provisioned table/index capacity
- Schema discovery to suit your needs
  - Dynamic inference
  - Static analysis of case class
- Column and filter pushdown
- Global secondary index support
- Write support

## Getting The Dependency

The library is available from [Maven Central](https://mvnrepository.com/artifact/com.audienceproject/spark-dynamodb). Add the dependency in SBT as ```"com.audienceproject" %% "spark-dynamodb" % "latest"```

Spark is used in the library as a "provided" dependency, which means Spark has to be installed separately on the container where the application is running, such as is the case on AWS EMR.

## Quick Start Guide

### Scala
```scala
import com.audienceproject.spark.dynamodb.implicits._
import org.apache.spark.sql.SparkSession

val spark = SparkSession.builder().getOrCreate()

// Load a DataFrame from a Dynamo table. Only incurs the cost of a single scan for schema inference.
val dynamoDf = spark.read.dynamodb("SomeTableName") // <-- DataFrame of Row objects with inferred schema.

// Scan the table for the first 100 items (the order is arbitrary) and print them.
dynamoDf.show(100)

// write to some other table overwriting existing item with same keys
dynamoDf.write.dynamodb("SomeOtherTable")

// Case class representing the items in our table.
import com.audienceproject.spark.dynamodb.attribute
case class Vegetable (name: String, color: String, @attribute("weight_kg") weightKg: Double)

// Load a Dataset[Vegetable]. Notice the @attribute annotation on the case class - we imagine the weight attribute is named with an underscore in DynamoDB.
import org.apache.spark.sql.functions._
import spark.implicits._
val vegetableDs = spark.read.dynamodbAs[Vegetable]("VegeTable")
val avgWeightByColor = vegetableDs.agg($"color", avg($"weightKg")) // The column is called 'weightKg' in the Dataset.
```

### Python
```python
# Load a DataFrame from a Dynamo table. Only incurs the cost of a single scan for schema inference.
dynamoDf = spark.read.option("tableName", "SomeTableName") \
                     .mode(SaveMode.Append) \
                     .format("dynamodb") \
                     .load() # <-- DataFrame of Row objects with inferred schema.

# Scan the table for the first 100 items (the order is arbitrary) and print them.
dynamoDf.show(100)

# write to some other table overwriting existing item with same keys
dynamoDf.write.option("tableName", "SomeOtherTable") \
              .mode(SaveMode.Append) \
              .format("dynamodb") \
              .save()
```

*Note:* When running from `pyspark` shell, you can add the library as:
```bash
pyspark --packages com.audienceproject:spark-dynamodb_<spark-scala-version>:<version>
```

## Parameters
The following parameters can be set as options on the Spark reader and writer object before loading/saving.
- `region` sets the region where the dynamodb table. Default is environment specific.
- `roleArn` sets an IAM role to assume. This allows for access to a DynamoDB in a different account than the Spark cluster. Defaults to the standard role configuration.
- `daxEndpoint` if not blank, reads and writes will interact with the provided DAX cluster endpoint instead of DynamoDB
directly. Default is empty string.

The following parameters can be set as options on the Spark reader object before loading.

- `readPartitions` number of partitions to split the initial RDD when loading the data into Spark. Defaults to the size of the DynamoDB table divided into chunks of `maxPartitionBytes`
- `maxPartitionBytes` the maximum size of a single input partition. Default 128 MB
- `defaultParallelism` the number of input partitions that can be read from or written to DynamoDB simultaneously.
Read/write throughput will be limited by dividing it by this number. Set this to the number of CPU cores in your
Spark job. Defaults to the value of `SparkContext#defaultParallelism`.
- `targetCapacity` fraction of provisioned read capacity on the table (or index) to consume for reading, enforced by
a rate limiter. Default 1 (i.e. 100% capacity).
- `stronglyConsistentReads` whether or not to use strongly consistent reads. Default false.
- `bytesPerRCU` number of bytes that can be read per second with a single Read Capacity Unit. Default 4000 (4 KB). This value is multiplied by two when `stronglyConsistentReads=false`
- `filterPushdown` whether or not to use filter pushdown to DynamoDB on scan requests. Default true.
- `throughput` the desired read throughput to use. It overwrites any calculation used by the package. It is intended to be used with tables that are on-demand. Defaults to 100 for on-demand.

The following parameters can be set as options on the Spark writer object before saving. By default, items will be
written using PutItem grouped into BatchWriteItem operations.

- `writeBatchSize` number of items to send per call to DynamoDB BatchWriteItem. Default 25.
- `targetCapacity` fraction of provisioned write capacity on the table to consume for writing or updating, enforced
by a rate limiter. Default 1 (i.e. 100% capacity).
- `update` if true, items will be written using UpdateItem on keys rather than BatchWriteItem. Default false.
- `delete` if true, items will be deleted using BatchWriteItem. Default false.
- `throughput` the desired write throughput to use. It overwrites any calculation used by the package. It is intended to be used with tables that are on-demand. Defaults to 100 for on-demand.
- `inferSchema` Requires permission to use "Scan" on the table. If no items are present in the table, the inferred
schema will be empty. If false, will not automatically infer schema - this is useful when writing to a table with
many columns. Default true.

## System Properties
The following Java system properties are available for configuration.

- `aws.profile` IAM profile to use for default credentials provider.
- `aws.dynamodb.region` region in which to access the AWS APIs.
- `aws.dynamodb.endpoint` endpoint to use for accessing the DynamoDB API.
- `aws.sts.endpoint` endpoint to use for accessing the STS API when assuming the role indicated by the `roleArn` parameter.

## Acknowledgements
Usage of parallel scan and rate limiter inspired by work in https://github.com/traviscrawford/spark-dynamodb
