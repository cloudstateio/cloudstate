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

package io.cloudstate.javasupport.impl.crud

import java.lang.reflect.{Constructor, InvocationTargetException, Method}
import java.util.Optional

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.ServiceCallFactory
import io.cloudstate.javasupport.crud.{
  CommandContext,
  CommandHandler,
  CrudContext,
  CrudEntity,
  CrudEntityCreationContext,
  CrudEntityFactory,
  CrudEntityHandler,
  StateContext,
  StateHandler
}
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedEntityFactory, ResolvedServiceMethod}

import scala.collection.concurrent.TrieMap

/**
 * Annotation based implementation of the [[CrudEntityFactory]].
 */
private[impl] class AnnotationBasedCrudSupport(
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[CrudEntityCreationContext => AnyRef] = None
) extends CrudEntityFactory
    with ResolvedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = CrudBehaviorReflection(entityClass, resolvedMethods)

  private val constructor: CrudEntityCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on CRUD entities: $entityClass")
    }
  }

  override def create(context: CrudContext): CrudEntityHandler =
    new EntityHandler(context)

  private class EntityHandler(context: CrudContext) extends CrudEntityHandler {
    private val entity = {
      constructor(new DelegatingCrudContext(context) with CrudEntityCreationContext {
        override def entityId(): String = context.entityId()
      })
    }

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      behavior.commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      } getOrElse {
        throw new RuntimeException(
          s"No command handler found for command [${context.commandName()}] on $behaviorsString"
        )
      }
    }

    override def handleState(anyState: Optional[JavaPbAny], context: StateContext): Unit = unwrap {
      import scala.compat.java8.OptionConverters._
      val state = anyState.asScala.map(s => anySupport.decode(s)).asJava.asInstanceOf[AnyRef]

      behavior.getCachedStateHandlerForClass(state.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingCrudContext(context) with StateContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, state, ctx)
        case None =>
          throw new RuntimeException(
            s"No state handler found for state ${state.getClass} on $behaviorsString"
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

  private abstract class DelegatingCrudContext(delegate: CrudContext) extends CrudContext {
    override def entityId(): String = delegate.entityId()
    override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
  }
}

private class CrudBehaviorReflection(
    val commandHandlers: Map[String, ReflectionHelper.CommandHandlerInvoker[CommandContext]],
    val stateHandlers: Map[Class[_], StateHandlerInvoker]
) {

  private val stateHandlerCache = TrieMap.empty[Class[_], Option[StateHandlerInvoker]]

  def getCachedStateHandlerForClass(clazz: Class[_]): Option[StateHandlerInvoker] =
    stateHandlerCache.getOrElseUpdate(clazz, getHandlerForClass(stateHandlers)(clazz))

  private def getHandlerForClass[T](handlers: Map[Class[_], T])(clazz: Class[_]): Option[T] =
    handlers.get(clazz) match {
      case some @ Some(_) => some
      case None =>
        clazz.getInterfaces.collectFirst(Function.unlift(getHandlerForClass(handlers))) match {
          case some @ Some(_) => some
          case None if clazz.getSuperclass != null => getHandlerForClass(handlers)(clazz.getSuperclass)
          case None => None
        }
    }
}

private object CrudBehaviorReflection {
  def apply(behaviorClass: Class[_],
            serviceMethods: Map[String, ResolvedServiceMethod[_, _]]): CrudBehaviorReflection = {

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

        new ReflectionHelper.CommandHandlerInvoker[CommandContext](ReflectionHelper.ensureAccessible(method),
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

    val stateHandlers = allMethods
      .filter(_.getAnnotation(classOf[StateHandler]) != null)
      .map { method =>
        new StateHandlerInvoker(ReflectionHelper.ensureAccessible(method))
      }
      .groupBy(_.snapshotClass)
      .map {
        case (snapshotClass, Seq(invoker)) => (snapshotClass: Any) -> invoker
        case (clazz, many) =>
          throw new RuntimeException(
            s"Multiple methods found for handling snapshot of type $clazz: ${many.map(_.method.getName)}"
          )
      }
      .asInstanceOf[Map[Class[_], StateHandlerInvoker]]

    ReflectionHelper.validateNoBadMethods(
      allMethods,
      classOf[CrudEntity],
      Set(classOf[CommandHandler], classOf[StateHandler])
    )

    new CrudBehaviorReflection(commandHandlers, stateHandlers)
  }
}

private class EntityConstructorInvoker(constructor: Constructor[_]) extends (CrudEntityCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[CrudEntityCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: CrudEntityCreationContext): AnyRef = {
    val ctx = InvocationContext("", context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}

private class StateHandlerInvoker(val method: Method) {
  private val parameters = ReflectionHelper.getParameterHandlers[StateContext](method)()

  // Verify that there is at most one state handler
  val snapshotClass: Class[_] = parameters.collect {
    case MainArgumentParameterHandler(clazz) => clazz
  } match {
    case Array(handlerClass) => handlerClass
    case other =>
      throw new RuntimeException(
        s"StateHandler method $method must defined at most one non context parameter to handle state, the parameters defined were: ${other
          .mkString(",")}"
      )
  }

  def invoke(obj: AnyRef, snapshot: AnyRef, context: StateContext): Unit = {
    val ctx = InvocationContext(snapshot, context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }
}
