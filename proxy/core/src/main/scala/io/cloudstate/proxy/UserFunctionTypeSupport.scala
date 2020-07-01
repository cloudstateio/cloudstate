/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.proxy

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors.{MethodDescriptor, ServiceDescriptor}
import com.google.protobuf.{ByteString, DynamicMessage}
import io.cloudstate.protocol.entity.{Entity, Metadata}
import io.cloudstate.entity_key.EntityKeyProto
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionReply}
import io.cloudstate.proxy.protobuf.Options

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

trait UserFunctionTypeSupport {

  /**
   * Get a handler as a Flow.
   *
   * This may be used for both unary and streamed calls. If unary, then it would be expected that only one message
   * is passed to the flow, but if more than one message is passed to the flow, then the implementation should treat
   * that as multiple unary calls, with replies returned in order.
   *
   * There are two types of metadata, stream level metadata, message level metadata. Depending on transport, these
   * may or may not exist. And depending on the entity type, how these are handled may be different. Stream level
   * metadata is passed as an argument to this handler, while message level metadata is attached to the
   * UserFunctionCommand.
   *
   * For example, gRPC has metadata associated with the call, not individual messages, so this will be stream level
   * metadata, passed in the metadata parameter, while the UserFunctionCommand would have empty metadata. Meanwhile,
   * event sources don't have stream level metadata, but they do have per message metadata, so their metadata will be
   * attached to the command.
   *
   * How implementations handle the metadata depends on the implementation. Entity implementations, where different
   * messages are sent to different entities, might not have a concept of a stream, and so they should merge the
   * stream level metadata with the metadata attached to each command before passing it on.
   */
  def handler(command: String, metadata: Metadata): Flow[UserFunctionRouter.Message, UserFunctionReply, NotUsed]

  def handleUnary(command: String, message: UserFunctionRouter.Message): Future[UserFunctionReply]

}

object UserFunctionTypeSupport {
  def mergeStreamLevelMetadata(metadata: Metadata, command: UserFunctionRouter.Message) =
    if (metadata.entries.nonEmpty) {
      command.copy(
        metadata = Metadata(metadata.entries ++ command.metadata.entries)
      )
    } else command
}

trait UserFunctionTypeSupportFactory {
  def build(entity: Entity, serviceDescriptor: ServiceDescriptor): UserFunctionTypeSupport
}

/**
 * Abstract support for any user function type that is entity based (ie, has entity id keys).
 */
abstract class EntityTypeSupportFactory(implicit ec: ExecutionContext) extends UserFunctionTypeSupportFactory {
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
  private[this] val keyFields = method.getInputType.getFields.iterator.asScala
    .filter(
      field => EntityKeyProto.entityKey.get(Options.convertFieldOptions(field))
    )
    .toArray
    .sortBy(_.getIndex)

  def keyFieldsCount: Int = keyFields.length

  def extractId(bytes: ByteString): String =
    keyFields.length match {
      case 0 =>
        ""
      case 1 =>
        val dm = DynamicMessage.parseFrom(method.getInputType, bytes)
        dm.getField(keyFields.head).toString
      case _ =>
        val dm = DynamicMessage.parseFrom(method.getInputType, bytes)
        keyFields.iterator.map(dm.getField).mkString(EntityMethodDescriptor.Separator)
    }

}

private final class EntityUserFunctionTypeSupport(serviceDescriptor: ServiceDescriptor,
                                                  methodDescriptors: Map[String, EntityMethodDescriptor],
                                                  entityTypeSupport: EntityTypeSupport)(implicit ec: ExecutionContext)
    extends UserFunctionTypeSupport {

  override def handler(name: String,
                       metadata: Metadata): Flow[UserFunctionRouter.Message, UserFunctionReply, NotUsed] = {
    val method = methodDescriptor(name)
    Flow[UserFunctionRouter.Message].map(ufToEntityCommand(method)).via(entityTypeSupport.handler(method, metadata))
  }

  override def handleUnary(commandName: String, message: UserFunctionRouter.Message): Future[UserFunctionReply] =
    entityTypeSupport.handleUnary(ufToEntityCommand(methodDescriptor(commandName))(message))

  private def ufToEntityCommand(method: EntityMethodDescriptor): UserFunctionRouter.Message => EntityCommand = {
    command =>
      val entityId = method.extractId(command.payload.value)
      EntityCommand(entityId = entityId,
                    name = method.method.getName,
                    payload = Some(command.payload),
                    streamed = method.method.isServerStreaming,
                    metadata = Some(command.metadata))
  }

  private def methodDescriptor(name: String): EntityMethodDescriptor =
    methodDescriptors
      .getOrElse(name,
                 throw EntityDiscoveryException(s"Unknown command $name on service ${serviceDescriptor.getFullName}"))
}

trait EntityTypeSupport {

  def handler(methodDescriptor: EntityMethodDescriptor,
              metadata: Metadata): Flow[EntityCommand, UserFunctionReply, NotUsed]

  def handleUnary(command: EntityCommand): Future[UserFunctionReply]

}

object EntityTypeSupport {
  def mergeStreamLevelMetadata(metadata: Metadata, command: EntityCommand) =
    if (metadata.entries.nonEmpty) {
      command.copy(
        metadata = Some(Metadata(command.metadata.fold(metadata.entries)(m => metadata.entries ++ m.entries)))
      )
    } else command
}
