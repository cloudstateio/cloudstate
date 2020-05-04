// #build-kts
import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "1.3.72"
    id("com.google.protobuf") version "0.8.12"
    id("com.google.cloud.tools.jib") version "2.2.0"
    idea
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("io.cloudstate:cloudstate-kotlin-support:$cloudstate.kotlin-support.version$")
    implementation("com.google.api.grpc:proto-google-common-protos:1.17.0")
    implementation("ch.qos.logback:logback-classic:1.2.3")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.9.0"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

jib {
    from {
        image = "adoptopenjdk/openjdk8-openj9:alpine-slim"
    }
    to {
        image = "${repo.name}"
        tags = setOf(project.version.toString())
    }
    container {
        mainClass = "${main.class}"
        jvmFlags = listOf("-XshareClasses", "-Xquickstart", "-XX:+UseG1GC", "-XX:+UseStringDeduplication")
        ports = listOf("8080")
        }
}
// #build-kts