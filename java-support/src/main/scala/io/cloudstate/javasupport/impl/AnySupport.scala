package io.cloudstate.javasupport.impl

import java.io.ByteArrayOutputStream
import java.util.Locale

import akka.protobuf.WireFormat
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.{ByteString, CodedInputStream, CodedOutputStream, Descriptors, UnsafeByteOperations, Any => JavaProtoAny}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.javasupport.Jsonable
import scalapb.GeneratedMessage

import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag

object AnySupport {

  private final val CloudStatePrimitiveFieldNumber = 1
  private final val CloudStatePrimitive = "p.cloudstate.io/"
  private final val CloudStateJson = "json.cloudstate.io/"
  private final val AnyUrl = "type.googleapis.com/"

  private sealed abstract class Primitive[T: ClassTag] {
    val name = fieldType.getJavaType.name().toLowerCase(Locale.ROOT)
    val fullName = CloudStatePrimitive + name
    final val clazz = implicitly[ClassTag[T]]
    def write(stream: CodedOutputStream, t: T): Unit
    def read(stream: CodedInputStream): T
    def fieldType: WireFormat.FieldType
    def defaultValue: T
    val tag = (CloudStatePrimitiveFieldNumber << 3) | fieldType.getWireType
  }

  private final object StringPrimitive extends Primitive[String] {
    override def fieldType = WireFormat.FieldType.STRING
    override def defaultValue = ""
    override def write(stream: CodedOutputStream, t: String) = stream.writeString(CloudStatePrimitiveFieldNumber, t)
    override def read(stream: CodedInputStream) = stream.readString()
  }

  private final val Primitives = Seq(
    StringPrimitive,
    new Primitive[ByteString] {
      override def fieldType = WireFormat.FieldType.BYTES
      override def defaultValue = ByteString.EMPTY
      override def write(stream: CodedOutputStream, t: ByteString) = stream.writeBytes(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readBytes()
    },
    new Primitive[Integer] {
      override def fieldType = WireFormat.FieldType.INT32
      override def defaultValue = 0
      override def write(stream: CodedOutputStream, t: Integer) = stream.writeInt32(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readInt32()
    },
    new Primitive[java.lang.Long] {
      override def fieldType = WireFormat.FieldType.INT64
      override def defaultValue = 0l
      override def write(stream: CodedOutputStream, t: java.lang.Long) = stream.writeInt64(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readInt64()
    },
    new Primitive[java.lang.Float] {
      override def fieldType = WireFormat.FieldType.FLOAT
      override def defaultValue = 0f
      override def write(stream: CodedOutputStream, t: java.lang.Float) = stream.writeFloat(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readFloat()
    },
    new Primitive[java.lang.Double] {
      override def fieldType = WireFormat.FieldType.DOUBLE
      override def defaultValue = 0d
      override def write(stream: CodedOutputStream, t: java.lang.Double) = stream.writeDouble(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readDouble()
    },
    new Primitive[java.lang.Boolean] {
      override def fieldType = WireFormat.FieldType.BOOL
      override def defaultValue = false
      override def write(stream: CodedOutputStream, t: java.lang.Boolean) = stream.writeBool(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readBool()
    }
  )

  private final val ClassToPrimitives = Primitives.map(p => p.clazz -> p)
    .asInstanceOf[Seq[(Any, Primitive[Any])]].toMap
  private final val NameToPrimitives = Primitives.map(p => p.fullName -> p)
    .asInstanceOf[Seq[(String, Primitive[Any])]].toMap

  private final val objectMapper = new ObjectMapper()

  private def primitiveToBytes[T](primitive: Primitive[T], value: T): ByteString = {
    if (value != primitive.defaultValue) {
      val baos = new ByteArrayOutputStream()
      val stream = CodedOutputStream.newInstance(baos)
      primitive.write(stream, value)
      UnsafeByteOperations.unsafeWrap(baos.toByteArray)
    } else ByteString.EMPTY
  }

  def encode(value: Any): ScalaPbAny = {
    value match {

      case javaProtoMessage: com.google.protobuf.Message =>
        ScalaPbAny.fromJavaProto(JavaProtoAny.pack(javaProtoMessage))

      case scalaPbMessage: GeneratedMessage =>
        ScalaPbAny(
          "type.googleapis.com/" + scalaPbMessage.companion.scalaDescriptor.fullName,
          scalaPbMessage.toByteString
        )

      case _ if ClassToPrimitives.contains(value.getClass) =>
        val primitive = ClassToPrimitives(value.getClass)
        ScalaPbAny(primitive.fullName, primitiveToBytes(primitive, value))

      case _: AnyRef if value.getClass.getAnnotation(classOf[Jsonable]) != null =>
        val json = objectMapper.writeValueAsString(value)
        ScalaPbAny(CloudStateJson + value.getClass, primitiveToBytes(StringPrimitive, json))

      case other =>
        throw SerializationException(s"Don't know how to serialize object of type ${other.getClass}. Try passing a protobuf, using a primitive type, or using a type annotated with @Jsonable.")
    }
  }

  private def bytesToPrimitive[T](primitive: Primitive[T], bytes: ByteString) = {
    val stream = bytes.newCodedInput()
    if (Stream.continually(stream.readTag())
      .takeWhile(_ != 0)
      .exists { tag =>
        if (primitive.tag != tag) {
          stream.skipField(tag)
          false
        } else true
      }) {
      primitive.read(stream)
    } else primitive.defaultValue
  }
}

class AnySupport(descriptors: Seq[Descriptors.FileDescriptor], classLoader: ClassLoader) {
  import scala.collection.JavaConverters._
  import AnySupport._

  private val allTypes = (for {
    descriptor <- flattenDescriptors(Map.empty, descriptors).values
    messageType <- descriptor.getMessageTypes.asScala
  } yield messageType.getFullName -> (descriptor, messageType)).toMap

  private def flattenDescriptors(seenSoFar: Map[String, Descriptors.FileDescriptor], descriptors: Seq[Descriptors.FileDescriptor]): Map[String, Descriptors.FileDescriptor] = {
    descriptors.foldLeft(seenSoFar) {
      case (results, descriptor) =>
        if (results.contains(descriptor.getFullName)) results
        else {
          val withDesc = results.updated(descriptor.getFullName, descriptor)
          flattenDescriptors(withDesc, descriptor.getDependencies.asScala ++ descriptor.getPublicDependencies.asScala)
        }
    }
  }

  private val reflectionCache = TrieMap.empty[String, Class[_]]

  private def findClassForType(typeName: String): Option[Class[_]] = {
    allTypes.get(typeName).map {
      case (fileDescriptor, typeDescriptor) =>
        reflectionCache.getOrElseUpdate(typeName, {
          // todo
          throw SerializationException("blah")
        })
    }
  }

  def encode(value: Any): ScalaPbAny = AnySupport.encode(value)

  def decode(any: ScalaPbAny): Any = {
    if (any.typeUrl.startsWith(CloudStatePrimitive)) {
      NameToPrimitives.get(any.typeUrl) match {
        case Some(primitive) =>
          bytesToPrimitive(primitive, any.value)
        case None =>
          throw SerializationException("Unknown primitive type url: " + any.typeUrl)
      }
    } else if (any.typeUrl.startsWith(CloudStateJson)) {
      val jsonType = any.typeUrl.substring(CloudStateJson.length)
      try {
        // todo maybe cache?
        val jsonClass = classLoader.loadClass(jsonType)
        if (jsonClass.getAnnotation(classOf[Jsonable]) == null) {
          throw SerializationException(s"Illegal CloudEvents json class, no @Jsonable annotation is present: $jsonType")
        }
        objectMapper.readerFor(jsonClass).readValue(bytesToPrimitive(StringPrimitive, any.value))
      } catch {
        case cnfe: ClassNotFoundException => throw SerializationException("Could not load JSON class: " + jsonType, cnfe)
      }
    } else {
      val typeName = any.typeUrl.split("/", 2) match {
        case Array(_, typeName) => typeName
        case _ => any.typeUrl
      }

      findClassForType(typeName) match {
        case Some(clazz: Class[com.google.protobuf.Message]) if classOf[com.google.protobuf.Message].isAssignableFrom(clazz) =>
          ScalaPbAny.toJavaProto(any).unpack(clazz)
        case Some(clazz: Class[_]) if classOf[GeneratedMessage].isAssignableFrom(clazz) =>
          // todo probably wanna cache this lookup
          val companion = clazz.getMethod("defaultInstance").invoke(null)
            .asInstanceOf[scalapb.GeneratedMessage].companion
          companion.parseFrom(any.value.newCodedInput())
        case None =>
          throw SerializationException("Unable to find descriptor for type: " + any.typeUrl)
      }
    }
  }


}

final case class SerializationException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)