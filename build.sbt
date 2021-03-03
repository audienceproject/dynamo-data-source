organization := "com.audienceproject"

name := "spark-dynamodb"

version := "1.1.3-SNAPSHOT"

description := "Plug-and-play implementation of an Apache Spark custom data source for AWS DynamoDB."

scalaVersion := "2.12.12"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
scalacOptions += "-target:jvm-1.8"

initialize := {
    val _ = initialize.value
    val required = "1.8"
    val current  = sys.props("java.specification.version")
    assert(current == required, s"Unsupported JDK: java.specification.version $current != $required")
}

compileOrder := CompileOrder.JavaThenScala

resolvers += "DynamoDBLocal" at "https://s3-us-west-2.amazonaws.com/dynamodb-local/release"

libraryDependencies += "com.amazonaws" % "aws-java-sdk-sts" % "1.11.955"
libraryDependencies += "com.amazonaws" % "aws-java-sdk-dynamodb" % "1.11.955"
// This should be "optional" scope after updating SBT to a version that supports it
libraryDependencies += "com.amazonaws" % "amazon-dax-client" % "1.0.208233.0"
libraryDependencies += "com.amazonaws" % "DynamoDBLocal" % "[1.11,2.0)" % "test" exclude("com.google.guava", "guava")

libraryDependencies += "org.apache.spark" %% "spark-sql" % "3.0.0" % "provided"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % "test"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.25"

libraryDependencies ++= {
    val log4j2Version = "2.11.1"
    Seq(
        "org.apache.logging.log4j" % "log4j-api" % log4j2Version % "test",
        "org.apache.logging.log4j" % "log4j-core" % log4j2Version % "test",
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4j2Version % "test"
    )
}

libraryDependencies += "com.almworks.sqlite4java" % "sqlite4java" % "1.0.392" % "test"

retrieveManaged := true

fork in Test := true

val libManaged = "lib_managed"
val libManagedSqlite = s"${libManaged}_sqlite4java"

javaOptions in Test ++= Seq(s"-Djava.library.path=./$libManagedSqlite", "-Daws.dynamodb.endpoint=http://localhost:8000")

/**
  * Put all sqlite4java dependencies in [[libManagedSqlite]] for easy reference when configuring java.library.path.
  */
Test / resourceGenerators += Def.task {
    import java.nio.file.{Files, Path}
    import java.util.function.Predicate
    import java.util.stream.Collectors
    import scala.collection.JavaConverters._

    def log(msg: Any): Unit = println(s"[℣₳ℒ𐎅] $msg") //stand out in the crowd

    val theOnesWeLookFor = Set(
        "libsqlite4java-linux-amd64-1.0.392.so",
        "libsqlite4java-linux-i386-1.0.392.so ",
        "libsqlite4java-osx-1.0.392.dylib     ",
        "sqlite4java-1.0.392.jar              ",
        "sqlite4java-win32-x64-1.0.392.dll    ",
        "sqlite4java-win32-x86-1.0.392.dll    "
    ).map(_.trim)

    val isOneOfTheOnes = new Predicate[Path] {
        override def test(p: Path) = theOnesWeLookFor exists (p endsWith _)
    }

    val theOnesWeCouldFind: Set[Path] = Files
        .walk(new File(libManaged).toPath)
        .filter(isOneOfTheOnes)
        .collect(Collectors.toSet[Path])
        .asScala.toSet

    theOnesWeCouldFind foreach { path =>
        log(s"found: ${path.toFile.getName}")
    }

    assert(theOnesWeCouldFind.size == theOnesWeLookFor.size)

    val libManagedSqliteDir = new File(s"$libManagedSqlite")
    sbt.IO delete libManagedSqliteDir
    sbt.IO createDirectory libManagedSqliteDir
    log(libManagedSqliteDir.getAbsolutePath)

    theOnesWeCouldFind
        .map { path =>
            val source: File = path.toFile
            val target: File = libManagedSqliteDir / source.getName
            log(s"copying from $source to $target")
            sbt.IO.copyFile(source, target)
            target
        }
        .toSeq
}.taskValue

/**
  * Maven specific settings for publishing to Maven central.
  */
publishMavenStyle := true
publishArtifact in Test := false
pomIncludeRepository := { _ => false }
publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
    else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
pomExtra := <url>https://github.com/audienceproject/spark-dynamodb</url>
    <licenses>
        <license>
            <name>Apache License, Version 2.0</name>
            <url>https://opensource.org/licenses/apache-2.0</url>
        </license>
    </licenses>
    <scm>
        <url>git@github.com:audienceproject/spark-dynamodb.git</url>
        <connection>scm:git:git//github.com/audienceproject/spark-dynamodb.git</connection>
        <developerConnection>scm:git:ssh://github.com:audienceproject/spark-dynamodb.git</developerConnection>
    </scm>
    <developers>
        <developer>
            <id>jacobfi</id>
            <name>Jacob Fischer</name>
            <email>jacob.fischer@audienceproject.com</email>
            <organization>AudienceProject</organization>
            <organizationUrl>https://www.audienceproject.com</organizationUrl>
        </developer>
        <developer>
            <id>johsbk</id>
            <name>Johs Kristoffersen</name>
            <email>johs.kristoffersen@audienceproject.com</email>
            <organization>AudienceProject</organization>
            <organizationUrl>https://www.audienceproject.com</organizationUrl>
        </developer>
    </developers>
