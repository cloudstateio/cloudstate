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
import io.cloudstate.protocol.entity.Entity
import io.cloudstate.entity_key.EntityKeyProto
import io.cloudstate.proxy.entity.{EntityCommand, UserFunctionCommand, UserFunctionReply}
import io.cloudstate.proxy.protobuf.Options

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

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
                  streamed = method.method.isServerStreaming,
                  metadata = command.metadata)
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
