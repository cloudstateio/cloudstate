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

package io.cloudstate.javasupport.impl.entity

import java.lang.reflect.{Constructor, InvocationTargetException}
import java.util.Optional

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.{Metadata, ServiceCall, ServiceCallFactory}
import io.cloudstate.javasupport.entity.{
  EntityFactory => ValueEntityFactory,
  EntityHandler => ValueEntityHandler,
  EntityContext => ValueEntityContext
}
import io.cloudstate.javasupport.entity._
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.impl.EntityExceptions.EntityException
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedEntityFactory, ResolvedServiceMethod}

/**
 * Annotation based implementation of the [[EntityFactory]].
 */
private[impl] class AnnotationBasedEntitySupport(
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[EntityCreationContext => AnyRef] = None
) extends ValueEntityFactory
    with ResolvedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = EntityBehaviorReflection(entityClass, resolvedMethods)

  private val constructor: EntityCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on value-based entities: $entityClass")
    }
  }

  override def create(context: EntityContext): ValueEntityHandler =
    new EntityHandler(context)

  private class EntityHandler(context: ValueEntityContext) extends ValueEntityHandler {
    private val entity = {
      constructor(new DelegatingEntityContext(context) with EntityCreationContext {
        override def entityId(): String = context.entityId()
      })
    }

    override def handleCommand(command: JavaPbAny, context: CommandContext[JavaPbAny]): Optional[JavaPbAny] = unwrap {
      behavior.commandHandlers.get(context.commandName()).map { handler =>
        val adaptedContext =
          new AdaptedCommandContext(context, anySupport)
        handler.invoke(entity, command, adaptedContext)
      } getOrElse {
        throw EntityException(
          context,
          s"No command handler found for command [${context.commandName()}] on $behaviorsString"
        )
      }
    }

    private def unwrap[T](block: => T): T =
      try {
        block
      } catch {
        case ite: InvocationTargetException if ite.getCause != null =>
          throw ite.getCause
      }

    private def behaviorsString = entity.getClass.toString
  }

  private abstract class DelegatingEntityContext(delegate: ValueEntityContext) extends ValueEntityContext {
    override def entityId(): String = delegate.entityId()
    override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
  }
}

private class EntityBehaviorReflection(
    val commandHandlers: Map[String, ReflectionHelper.CommandHandlerInvoker[CommandContext[AnyRef]]]
) {}

private object EntityBehaviorReflection {
  def apply(behaviorClass: Class[_],
            serviceMethods: Map[String, ResolvedServiceMethod[_, _]]): EntityBehaviorReflection = {

    val allMethods = ReflectionHelper.getAllDeclaredMethods(behaviorClass)
    val commandHandlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = serviceMethods.getOrElse(name, {
          throw new RuntimeException(
            s"Command handler method ${method.getName} for command $name found, but the service has no command with that name."
          )
        })

        new ReflectionHelper.CommandHandlerInvoker[CommandContext[AnyRef]](ReflectionHelper.ensureAccessible(method),
                                                                           serviceMethod)
      }
      .groupBy(_.serviceMethod.name)
      .map {
        case (commandName, Seq(invoker)) => commandName -> invoker
        case (commandName, many) =>
          throw new RuntimeException(
            s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}"
          )
      }

    ReflectionHelper.validateNoBadMethods(
      allMethods,
      classOf[Entity],
      Set(classOf[CommandHandler])
    )

    new EntityBehaviorReflection(commandHandlers)
  }
}

private class EntityConstructorInvoker(constructor: Constructor[_]) extends (EntityCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[AnyRef, EntityCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: EntityCreationContext): AnyRef = {
    val ctx = InvocationContext(null.asInstanceOf[AnyRef], context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}

/*
 * This class is a conversion bridge between CommandContext[JavaPbAny] and CommandContext[AnyRef].
 * It helps for making the conversion from JavaPbAny to AnyRef and backward.
 */
private class AdaptedCommandContext(val delegate: CommandContext[JavaPbAny], anySupport: AnySupport)
    extends CommandContext[AnyRef] {

  override def getState(): Optional[AnyRef] = {
    val result = delegate.getState
    result.map(anySupport.decode(_).asInstanceOf[AnyRef])
  }

  override def updateState(state: AnyRef): Unit = {
    val encoded = anySupport.encodeJava(state)
    delegate.updateState(encoded)
  }

  override def deleteState(): Unit = delegate.deleteState()

  override def commandName(): String = delegate.commandName()
  override def commandId(): Long = delegate.commandId()
  override def metadata(): Metadata = delegate.metadata()
  override def entityId(): String = delegate.entityId()
  override def effect(effect: ServiceCall, synchronous: Boolean): Unit = delegate.effect(effect, synchronous)
  override def fail(errorMessage: String): RuntimeException = delegate.fail(errorMessage)
  override def forward(to: ServiceCall): Unit = delegate.forward(to)
  override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
}
