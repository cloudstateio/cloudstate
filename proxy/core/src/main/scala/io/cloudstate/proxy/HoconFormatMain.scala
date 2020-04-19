package io.cloudstate.proxy

import java.io.File

object HoconFormatMain {
  val all = List(
    "src/main/resources/META-INF/native-image/io.grpc/grpc-core/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/io.grpc/grpc-netty-shaded/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe/config/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/net.java.openjdk/base/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/io.cloudstate/cloudstate-proxy-core/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.lightbend.akka.management/akka-management-cluster-bootstrap/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.lightbend.akka.management/akka-management/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-cluster/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-slf4j/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-stream/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-http-core/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-remote/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-distributed-data/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-cluster-sharding/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-http2-support/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-persistence/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-actor/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.typesafe.akka/akka-cluster-tools/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.thesamet.scalapb/scalapb-runtime/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.google.protobuf/protobuf-java/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/org.agrona/agrona/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/com.lightbend.akka.discovery/akka-discovery-kubernetes-api/reflect-config.json.conf",
    "src/main/resources/META-INF/native-image/org.scala-lang/scala-library/reflect-config.json.conf",
    "../postgres/src/main/resources/META-INF/native-image/com.zaxxer/HikariCP/reflect-config.json.conf",
    "../postgres/src/main/resources/META-INF/native-image/com.github.dnvriend/akka-persistence-jdbc/reflect-config.json.conf",
    "../postgres/src/main/resources/META-INF/native-image/org.postgresql/postgresql/reflect-config.json.conf",
    "../postgres/src/main/resources/META-INF/native-image/net.java.openjdk/base/reflect-config.json.conf",
    "../postgres/src/main/resources/META-INF/native-image/com.typesafe.slick/slick/reflect-config.json.conf",
    "../postgres/src/main/resources/META-INF/native-image/com.typesafe.slick/slick-hikaricp/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/com.datastax.cassandra/cassandra-driver-core/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/io.netty/netty-handler/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/io.netty/netty-codec/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/io.netty/netty-common/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/io.netty/netty-transport/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/io.netty/netty-buffer/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/net.java.openjdk/base/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/com.typesafe.akka/akka-persistence-cassandra/reflect-config.json.conf",
    "../cassandra/src/main/resources/META-INF/native-image/com.google.guava/guava/reflect-config.json.conf"
  )

  def main(args: Array[String]): Unit = reformatAll(args)

  def reformatAll(strings: Seq[String]): Unit =
    (if (strings.isEmpty) all else strings).foreach(s => HoconFormat.reformat(new File(s)))
}
