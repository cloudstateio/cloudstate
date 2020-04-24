package io.cloudstate.graaltools

import com.oracle.svm.core.annotate.AutomaticFeature
import org.graalvm.nativeimage.hosted.Feature.QueryReachabilityAccess
import org.graalvm.nativeimage.hosted.{Feature, RuntimeReflection}

import scala.collection.JavaConverters._
import java.lang.reflect.Modifier.isAbstract
import java.util.concurrent.ConcurrentHashMap

@AutomaticFeature
final class AkkaSerializerRegisterFeature extends Feature {
  private[this] final val cache = ConcurrentHashMap.newKeySet[String]
  private[this] final val registerTheseSerializers = {
    val c = ConcurrentHashMap.newKeySet[String]
    // FIXME OBTAIN THIS LIST DYNAMICALLY BY GETTING:
    // SerializationExtension(system).serializerByIdentity.values.map(_.getClass.getName)
    c.add("akka.serialization.NullSerializer$")
    c.add("akka.cluster.protobuf.ClusterMessageSerializer")
    c.add("io.cloudstate.proxy.ProtobufAnySerializer")
    c.add("akka.cluster.singleton.protobuf.ClusterSingletonMessageSerializer")
    c.add("akka.remote.serialization.StringSerializer")
    c.add("akka.serialization.JavaSerializer")
    c.add("akka.remote.serialization.MessageContainerSerializer")
    c.add("akka.remote.serialization.ByteStringSerializer")
    c.add("akka.cluster.pubsub.protobuf.DistributedPubSubMessageSerializer")
    c.add("akka.cluster.sharding.protobuf.ClusterShardingMessageSerializer")
    c.add("akka.remote.serialization.ProtobufSerializer")
    c.add("akka.remote.serialization.ArteryMessageSerializer")
    c.add("akka.remote.serialization.SystemMessageSerializer")
    c.add("akka.cluster.ddata.protobuf.ReplicatorMessageSerializer")
    c.add("akka.persistence.serialization.MessageSerializer")
    c.add("akka.remote.serialization.DaemonMsgCreateSerializer")
    c.add("akka.serialization.BooleanSerializer")
    c.add("akka.remote.serialization.LongSerializer")
    c.add("akka.remote.serialization.MiscMessageSerializer")
    c.add("akka.cluster.ddata.protobuf.ReplicatedDataSerializer")
    c.add("akka.persistence.serialization.SnapshotSerializer")
    c.add("io.cloudstate.proxy.crdt.CrdtSerializers")
    c.add("akka.stream.serialization.StreamRefSerializer")
    c.add("akka.remote.serialization.IntSerializer")
    c.add("akka.serialization.ByteArraySerializer")
    c.add("akka.cluster.client.protobuf.ClusterClientMessageSerializer")
    c
  }

  override final def isInConfiguration(access: Feature.IsInConfigurationAccess): Boolean = false

  override final def duringAnalysis(access: Feature.DuringAnalysisAccess): Unit =
    registerTheseSerializers.forEach { className =>
      registerSerializerClassByName(access, className)
    }

  final def registerSerializerClassByName(access: Feature.DuringAnalysisAccess, className: String): Unit =
    registerSerializerClass(access, access.findClassByName(className))

  final def registerSerializerClass(access: Feature.DuringAnalysisAccess, cls: Class[_]): Unit =
    for {
      cls <- Option(cls).filter(access.isReachable)
      if cls != null && !cls.isInterface && !isAbstract(cls.getModifiers) && cache.add(cls.getName)
      ctor <- getDeclaredConstructor(cls, classOf[akka.actor.ExtendedActorSystem]) orElse getDeclaredConstructor(
        cls
      )
      _ = println("Automatically registering serializer class for reflection purposes: " + cls.getName)
    } {
      RuntimeReflection.register(cls)
      RuntimeReflection.register(ctor)
      registerTheseSerializers.remove(cls.getName)
    }

  final def registerSubtypesOf(access: Feature.DuringAnalysisAccess, className: String): Unit = {
    val akkaSerializerClass = access.findClassByName(className)
    if (akkaSerializerClass != null && access.isReachable(akkaSerializerClass)) {
      access.reachableSubtypes(akkaSerializerClass).iterator.asScala.foreach { subtype =>
        registerSerializerClass(access, subtype)
      }
    }
  }

  private[this] final def getDeclaredConstructor(cls: Class[_], argumentTypes: Class[_]*) =
    try Option(cls.getDeclaredConstructor(argumentTypes: _*))
    catch { case _: NoSuchMethodException => None }
}
