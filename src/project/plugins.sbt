addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.17")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "3.3.0")

addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "0.6.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.4")


addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.9")

libraryDependencies += "org.jsonschema2pojo" % "jsonschema2pojo-core" % "1.0.0"