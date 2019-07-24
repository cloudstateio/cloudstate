package io.cloudstate.proxy

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors.{FieldDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{ByteString, DynamicMessage, descriptor => ScalaPBDescriptorProtos}
import io.cloudstate.entity.Entity
import io.cloudstate.entitykey.EntitykeyProto
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionCommand, UserFunctionReply}

import scala.collection.JavaConverters._
import scala.concurrent.Future

trait UserFunctionTypeSupport {

  def handler: Flow[UserFunctionCommand, UserFunctionReply, NotUsed]

  def handleUnary(command: UserFunctionCommand): Future[UserFunctionReply]

}

trait UserFunctionTypeSupportFactory {
  def build(entity: Entity, serviceDescriptor: ServiceDescriptor): UserFunctionTypeSupport
}

/**
  * Abstract support for any user function type that is entity based (ie, has entity id keys).
  */
abstract class EntityTypeSupportFactory extends UserFunctionTypeSupportFactory {
  override final def build(entity: Entity, serviceDescriptor: ServiceDescriptor): UserFunctionTypeSupport = {
    val idExtractors = serviceDescriptor.getMethods.asScala
      .map(method => method.getName -> new EntityIdExtractor(method))
      .toMap

    new EntityUserFunctionTypeSupport(serviceDescriptor, idExtractors, buildEntityTypeSupport(entity, serviceDescriptor))
  }

  protected def buildEntityTypeSupport(entity: Entity, serviceDescriptor: ServiceDescriptor): EntityTypeSupport

}

private object EntityIdExtractor {
  final val Separator = "-"
}

private final class EntityIdExtractor(method: MethodDescriptor) {
  /**
    * ScalaPB doesn't do this conversion for us unfortunately.
    * By doing it, we can use EntitykeyProto.entityKey.get() to read the entity key nicely.
    */
  private def convertFieldOptions(field: FieldDescriptor): ScalaPBDescriptorProtos.FieldOptions = {
    ScalaPBDescriptorProtos.
      FieldOptions.
      fromJavaProto(field.toProto.getOptions).
      withUnknownFields(scalapb.UnknownFieldSet(field.getOptions.getUnknownFields.asMap.asScala.map {
        case (idx, f) => idx.toInt -> scalapb.UnknownFieldSet.Field(
          varint          = f.getVarintList.asScala.map(_.toLong),
          fixed64         = f.getFixed64List.asScala.map(_.toLong),
          fixed32         = f.getFixed32List.asScala.map(_.toInt),
          lengthDelimited = f.getLengthDelimitedList.asScala
        )
      }.toMap))
  }

  private val fields = method.getInputType.getFields.iterator.asScala.
    filter(field => EntitykeyProto.entityKey.get(convertFieldOptions(field))).
    toArray.sortBy(_.getIndex)

  if (fields.isEmpty) {
    throw EntityDiscoveryException(s"No field marked with [(cloudstate.entity_key) = true] found for in type ${method.getInputType.getName}, this is needed to associate commands sent to ${method.getFullName} with the entities that they are for.")
  }

  def extractId(bytes: ByteString): String = {
    val dm = DynamicMessage.parseFrom(method.getInputType, bytes)

    fields.length match {
      case 1 => dm.getField(fields.head).toString
      case _ => fields.iterator.map(dm.getField).mkString(EntityIdExtractor.Separator)
    }

  }
}

private final class EntityUserFunctionTypeSupport(serviceDescriptor: ServiceDescriptor,
  idExtractors: Map[String, EntityIdExtractor], entityTypeSupport: EntityTypeSupport) extends UserFunctionTypeSupport {

  override def handler: Flow[UserFunctionCommand, UserFunctionReply, NotUsed] =
    Flow[UserFunctionCommand].map(ufToEntityCommand).via(entityTypeSupport.handler)


  override def handleUnary(command: UserFunctionCommand): Future[UserFunctionReply] = {
    entityTypeSupport.handleUnary(ufToEntityCommand(command))
  }

  private def ufToEntityCommand(command: UserFunctionCommand): EntityCommand = {
    idExtractors.get(command.name) match {
      case Some(extractor) =>
        val entityId = extractor.extractId(command.payload.fold(ByteString.EMPTY)(_.value))
        EntityCommand(entityId = entityId, name = command.name, payload = command.payload)
      case None =>
        throw EntityDiscoveryException(s"Unknown command ${command.name} on service ${serviceDescriptor.getFullName}")
    }
  }
}

trait EntityTypeSupport {

  def handler: Flow[EntityCommand, UserFunctionReply, NotUsed]

  def handleUnary(command: EntityCommand): Future[UserFunctionReply]

}
