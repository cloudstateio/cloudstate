package io.cloudstate.proxy

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors.{FieldDescriptor, MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{ByteString, DynamicMessage}
import io.cloudstate.protocol.entity.Entity
import io.cloudstate.entity_key.EntityKeyProto
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionCommand, UserFunctionReply}
import io.cloudstate.proxy.protobuf.Options

import scala.collection.JavaConverters._
import scala.concurrent.Future

trait UserFunctionTypeSupport {

  def handler(command: String): Flow[UserFunctionCommand, UserFunctionReply, NotUsed]

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
      .map(method => method.getName -> new EntityMethodDescriptor(method))
      .toMap

    new EntityUserFunctionTypeSupport(serviceDescriptor,
                                      idExtractors,
                                      buildEntityTypeSupport(entity, serviceDescriptor))
  }

  protected def buildEntityTypeSupport(entity: Entity, serviceDescriptor: ServiceDescriptor): EntityTypeSupport

}

private object EntityMethodDescriptor {
  final val Separator = "-"
}

final class EntityMethodDescriptor(val method: MethodDescriptor) {
  private val fields = method.getInputType.getFields.iterator.asScala
    .filter(field => EntityKeyProto.entityKey.get(Options.convertFieldOptions(field)))
    .toArray
    .sortBy(_.getIndex)

  if (fields.isEmpty) {
    throw EntityDiscoveryException(
      s"No field marked with [(cloudstate.entity_key) = true] found for in type ${method.getInputType.getName}, this is needed to associate commands sent to ${method.getFullName} with the entities that they are for."
    )
  }

  def extractId(bytes: ByteString): String = {
    val dm = DynamicMessage.parseFrom(method.getInputType, bytes)

    fields.length match {
      case 1 => dm.getField(fields.head).toString
      case _ => fields.iterator.map(dm.getField).mkString(EntityMethodDescriptor.Separator)
    }

  }
}

private final class EntityUserFunctionTypeSupport(serviceDescriptor: ServiceDescriptor,
                                                  methodDescriptors: Map[String, EntityMethodDescriptor],
                                                  entityTypeSupport: EntityTypeSupport)
    extends UserFunctionTypeSupport {

  override def handler(name: String): Flow[UserFunctionCommand, UserFunctionReply, NotUsed] = {
    val method = methodDescriptor(name)
    Flow[UserFunctionCommand].map(ufToEntityCommand(method)).via(entityTypeSupport.handler(method))
  }

  override def handleUnary(command: UserFunctionCommand): Future[UserFunctionReply] =
    entityTypeSupport.handleUnary(ufToEntityCommand(methodDescriptor(command.name))(command))

  private def ufToEntityCommand(method: EntityMethodDescriptor): UserFunctionCommand => EntityCommand = { command =>
    val entityId = method.extractId(command.payload.fold(ByteString.EMPTY)(_.value))
    EntityCommand(entityId = entityId,
                  name = command.name,
                  payload = command.payload,
                  streamed = method.method.isServerStreaming)
  }

  private def methodDescriptor(name: String): EntityMethodDescriptor =
    methodDescriptors
      .getOrElse(name,
                 throw EntityDiscoveryException(s"Unknown command $name on service ${serviceDescriptor.getFullName}"))
}

trait EntityTypeSupport {

  def handler(methodDescriptor: EntityMethodDescriptor): Flow[EntityCommand, UserFunctionReply, NotUsed]

  def handleUnary(command: EntityCommand): Future[UserFunctionReply]

}
