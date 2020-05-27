package io.cloudstate.proxy

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.descriptor.FieldOptions
import com.google.protobuf.{ByteString, Descriptors, DynamicMessage}
import io.cloudstate.crud_command_type.CrudCommandTypeProto
import io.cloudstate.entity_key.EntityKeyProto
import io.cloudstate.protocol.entity.Entity
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionCommand, UserFunctionReply}
import io.cloudstate.proxy.protobuf.Options
import io.cloudstate.sub_entity_key.SubEntityKeyProto
import scalapb.GeneratedExtension

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
    require(serviceDescriptor != null,
            "ServiceDescriptor not found, please verify the spelling and package name provided when looking it up")

    val methods = serviceDescriptor.getMethods.asScala
      .map(method => method.getName -> new EntityMethodDescriptor(method))
      .toMap

    new EntityUserFunctionTypeSupport(serviceDescriptor,
                                      methods,
                                      buildEntityTypeSupport(entity, serviceDescriptor, methods))
  }

  protected def buildEntityTypeSupport(entity: Entity,
                                       serviceDescriptor: ServiceDescriptor,
                                       methodDescriptors: Map[String, EntityMethodDescriptor]): EntityTypeSupport

}

private object EntityMethodDescriptor {
  final val Separator = "-"
}

final class EntityMethodDescriptor(val method: MethodDescriptor) {

  /** represents cloudstate entity key for all entities */
  private[this] val keyFields = commandFieldOptions(EntityKeyProto.entityKey)

  /** represents cloudstate sub entity key for crud entity */
  private[this] val crudSubEntityKeyFields = commandFieldOptions(SubEntityKeyProto.subEntityKey)

  /** represents cloudstate command type for crud entity */
  private[this] val crudCommandTypeFields = commandFieldOptions(CrudCommandTypeProto.crudCommandType)

  def keyFieldsCount: Int = keyFields.length

  def crudSubEntityKeyFieldsCount: Int = crudSubEntityKeyFields.length

  def crudCommandTypeFieldsCount: Int = crudCommandTypeFields.length

  def extractId(bytes: ByteString): String = extract(keyFields, bytes)

  def extractCrudSubEntityId(bytes: ByteString): String = extract(crudSubEntityKeyFields, bytes)

  def extractCrudCommandType(bytes: ByteString): String = extract(crudCommandTypeFields, bytes)

  private def extract(fieldOptions: Array[Descriptors.FieldDescriptor], bytes: ByteString): String =
    fieldOptions.length match {
      case 0 =>
        ""
      case 1 =>
        val dm = DynamicMessage.parseFrom(method.getInputType, bytes)
        dm.getField(fieldOptions.head).toString
      case _ =>
        val dm = DynamicMessage.parseFrom(method.getInputType, bytes)
        fieldOptions.iterator.map(dm.getField).mkString(EntityMethodDescriptor.Separator)
    }

  private def commandFieldOptions(
      optionType: GeneratedExtension[FieldOptions, Boolean]
  ): Array[Descriptors.FieldDescriptor] =
    method.getInputType.getFields.iterator.asScala
      .filter(field => optionType.get(Options.convertFieldOptions(field)))
      .toArray
      .sortBy(_.getIndex)
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
