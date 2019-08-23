package io.cloudstate.javasupport.impl

import com.fasterxml.jackson.databind.{ObjectReader, ObjectWriter}
import com.google.protobuf.{ByteString, Descriptors, Parser, UnsafeByteOperations, Message => JavaMessage, Any => JavaPbAny}
import io.cloudstate.javasupport.{ServiceCall, ServiceCallRef}

/**
  * A resolved service method.
  */
final case class ResolvedServiceMethod[I, O](descriptor: Descriptors.MethodDescriptor, inputType: ResolvedType[I],
  outputType: ResolvedType[O]) extends ServiceCallRef[I] {

  def outputStreamed: Boolean = descriptor.isServerStreaming
  def name: String = descriptor.getName

  override def method(): Descriptors.MethodDescriptor = descriptor

  override def createCall(message: I): ServiceCall = {
    ResolvedServiceCall(this, JavaPbAny.newBuilder()
        .setTypeUrl(inputType.typeUrl)
        .setValue(inputType.toByteString(message))
        .build()
    )
  }
}

final case class ResolvedServiceCall(ref: ServiceCallRef[_], message: JavaPbAny) extends ServiceCall

/**
  * A resolved type
  */
trait ResolvedType[T] {
  /**
    * The class for this type.
    */
  def typeClass: Class[T]

  /**
    * The URL for this type.
    */
  def typeUrl: String

  /**
    * Parse the given bytes into this type.
    */
  def parseFrom(bytes: ByteString): T

  /**
    * Convert the given value into a byte string.
    */
  def toByteString(value: T): ByteString
}

private final class JavaPbResolvedType[T <: JavaMessage](
                                                  override val typeClass: Class[T],
                                                  override val typeUrl: String,
                                                  parser: Parser[T]) extends ResolvedType[T] {
  override def parseFrom(bytes: ByteString): T = parser.parseFrom(bytes)
  override def toByteString(value: T): ByteString = value.toByteString
}

private final class ScalaPbResolvedType[T <: scalapb.GeneratedMessage](
                                 override val typeClass: Class[T],
                                 override val typeUrl: String,
                                 companion: scalapb.GeneratedMessageCompanion[_]) extends ResolvedType[T] {
  override def parseFrom(bytes: ByteString): T = companion.parseFrom(bytes.newCodedInput()).asInstanceOf[T]
  override def toByteString(value: T): ByteString = value.toByteString
}

/**
  * Not a real protobuf parser, but is useful none the less.
  */
private final class JacksonResolvedType[T](
                                      override val typeClass: Class[T],
                                      override val typeUrl: String,
                                      reader: ObjectReader,
                                      writer: ObjectWriter) extends ResolvedType[T] {
  override def parseFrom(bytes: ByteString): T = reader.readValue(bytes.toByteArray)
  override def toByteString(value: T): ByteString = UnsafeByteOperations.unsafeWrap(writer.writeValueAsBytes(value))
}

trait ResolvedEntityFactory {
  def resolvedMethods: Map[String, ResolvedServiceMethod[_, _]]
}