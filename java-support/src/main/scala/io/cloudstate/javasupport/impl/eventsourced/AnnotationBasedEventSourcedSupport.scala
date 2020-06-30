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

package io.cloudstate.javasupport.impl.eventsourced

import java.lang.reflect.{Constructor, InvocationTargetException, Method}
import java.util.Optional

import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedEntityFactory, ResolvedServiceMethod}

import scala.collection.concurrent.TrieMap
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.impl.eventsourced.EventSourcedImpl.EntityException
import io.cloudstate.javasupport.{EntityFactory, ServiceCallFactory}

/**
 * Annotation based implementation of the [[EventSourcedEntityFactory]].
 */
private[impl] class AnnotationBasedEventSourcedSupport(
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[EventSourcedEntityCreationContext => AnyRef] = None
) extends EventSourcedEntityFactory
    with ResolvedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  def this(factory: EntityFactory, anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(factory.entityClass,
         anySupport,
         anySupport.resolveServiceDescriptor(serviceDescriptor),
         Some(context => factory.create(context)))

  private val behavior = EventBehaviorReflection(entityClass, resolvedMethods)

  override def create(context: EventSourcedContext): EventSourcedEntityHandler =
    new EntityHandler(context)

  private val constructor: EventSourcedEntityCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on event sourced entities: $entityClass")
    }
  }

  private class EntityHandler(context: EventSourcedContext) extends EventSourcedEntityHandler {
    private val entity = {
      constructor(new DelegatingEventSourcedContext(context) with EventSourcedEntityCreationContext {
        override def entityId(): String = context.entityId()
      })
    }

    override def handleEvent(anyEvent: JavaPbAny, context: EventContext): Unit = unwrap {
      val event = anySupport.decode(anyEvent).asInstanceOf[AnyRef]

      behavior.getCachedEventHandlerForClass(event.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingEventSourcedContext(context) with EventContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, event, ctx)
        case None =>
          throw EntityException(
            s"No event handler found for event ${event.getClass} on $behaviorsString"
          )
      }
    }

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      behavior.commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      } getOrElse {
        throw EntityException(
          context,
          s"No command handler found for command [${context.commandName()}] on $behaviorsString"
        )
      }
    }

    override def handleSnapshot(anySnapshot: JavaPbAny, context: SnapshotContext): Unit = unwrap {
      val snapshot = anySupport.decode(anySnapshot).asInstanceOf[AnyRef]

      behavior.getCachedSnapshotHandlerForClass(snapshot.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingEventSourcedContext(context) with SnapshotContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, snapshot, ctx)
        case None =>
          throw EntityException(
            s"No snapshot handler found for snapshot ${snapshot.getClass} on $behaviorsString"
          )
      }
    }

    override def snapshot(context: SnapshotContext): Optional[JavaPbAny] = unwrap {
      behavior.snapshotInvoker.map { invoker =>
        invoker.invoke(entity, context)
      } match {
        case Some(invoker) => Optional.ofNullable(anySupport.encodeJava(invoker))
        case None => Optional.empty()
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

  private abstract class DelegatingEventSourcedContext(delegate: EventSourcedContext) extends EventSourcedContext {
    override def entityId(): String = delegate.entityId()
    override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
  }
}

private class EventBehaviorReflection(
    eventHandlers: Map[Class[_], EventHandlerInvoker],
    val commandHandlers: Map[String, ReflectionHelper.CommandHandlerInvoker[CommandContext]],
    snapshotHandlers: Map[Class[_], SnapshotHandlerInvoker],
    val snapshotInvoker: Option[SnapshotInvoker]
) {

  /**
   * We use a cache in addition to the info we've discovered by reflection so that an event handler can be declared
   * for a superclass of an event.
   */
  private val eventHandlerCache = TrieMap.empty[Class[_], Option[EventHandlerInvoker]]
  private val snapshotHandlerCache = TrieMap.empty[Class[_], Option[SnapshotHandlerInvoker]]

  def getCachedEventHandlerForClass(clazz: Class[_]): Option[EventHandlerInvoker] =
    eventHandlerCache.getOrElseUpdate(clazz, getHandlerForClass(eventHandlers)(clazz))

  def getCachedSnapshotHandlerForClass(clazz: Class[_]): Option[SnapshotHandlerInvoker] =
    snapshotHandlerCache.getOrElseUpdate(clazz, getHandlerForClass(snapshotHandlers)(clazz))

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

private object EventBehaviorReflection {
  def apply(behaviorClass: Class[_],
            serviceMethods: Map[String, ResolvedServiceMethod[_, _]]): EventBehaviorReflection = {

    val allMethods = ReflectionHelper.getAllDeclaredMethods(behaviorClass)
    val eventHandlers = allMethods
      .filter(_.getAnnotation(classOf[EventHandler]) != null)
      .map { method =>
        new EventHandlerInvoker(ReflectionHelper.ensureAccessible(method))
      }
      .groupBy(_.eventClass)
      .map {
        case (eventClass, Seq(invoker)) => (eventClass: Any) -> invoker
        case (clazz, many) =>
          throw new RuntimeException(
            s"Multiple methods found for handling event of type $clazz: ${many.map(_.method.getName)}"
          )
      }
      .asInstanceOf[Map[Class[_], EventHandlerInvoker]]

    val commandHandlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = serviceMethods.getOrElse(name, {
          throw new RuntimeException(
            s"Command handler method ${method.getName} for command $name found, but the service has no command by that name."
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

    val snapshotHandlers = allMethods
      .filter(_.getAnnotation(classOf[SnapshotHandler]) != null)
      .map { method =>
        new SnapshotHandlerInvoker(ReflectionHelper.ensureAccessible(method))
      }
      .groupBy(_.snapshotClass)
      .map {
        case (snapshotClass, Seq(invoker)) => (snapshotClass: Any) -> invoker
        case (clazz, many) =>
          throw new RuntimeException(
            s"Multiple methods found for handling snapshot of type $clazz: ${many.map(_.method.getName)}"
          )
      }
      .asInstanceOf[Map[Class[_], SnapshotHandlerInvoker]]

    val snapshotInvoker = allMethods
      .filter(_.getAnnotation(classOf[Snapshot]) != null)
      .map { method =>
        new SnapshotInvoker(ReflectionHelper.ensureAccessible(method))
      } match {
      case Seq() => None
      case Seq(single) =>
        Some(single)
      case _ =>
        throw new RuntimeException(s"Multiple snapshoting methods found on behavior $behaviorClass")
    }

    ReflectionHelper.validateNoBadMethods(
      allMethods,
      classOf[EventSourcedEntity],
      Set(classOf[EventHandler], classOf[CommandHandler], classOf[SnapshotHandler], classOf[Snapshot])
    )

    new EventBehaviorReflection(eventHandlers, commandHandlers, snapshotHandlers, snapshotInvoker)
  }
}

private class EntityConstructorInvoker(constructor: Constructor[_])
    extends (EventSourcedEntityCreationContext => AnyRef) {
  private val parameters =
    ReflectionHelper.getParameterHandlers[AnyRef, EventSourcedEntityCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: EventSourcedEntityCreationContext): AnyRef = {
    val ctx = InvocationContext(null.asInstanceOf[AnyRef], context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}

private class EventHandlerInvoker(val method: Method) {

  private val annotation = method.getAnnotation(classOf[EventHandler])

  private val parameters = ReflectionHelper.getParameterHandlers[AnyRef, EventContext](method)()

  private def annotationEventClass = annotation.eventClass() match {
    case obj if obj == classOf[Object] => None
    case clazz => Some(clazz)
  }

  // Verify that there is at most one event handler
  val eventClass: Class[_] = parameters.collect {
    case MainArgumentParameterHandler(clazz) => clazz
  } match {
    case Array() => annotationEventClass.getOrElse(classOf[Object])
    case Array(handlerClass) =>
      annotationEventClass match {
        case None => handlerClass
        case Some(annotated) if handlerClass.isAssignableFrom(annotated) || annotated.isInterface =>
          annotated
        case Some(nonAssignable) =>
          throw new RuntimeException(
            s"EventHandler method $method has defined an eventHandler class $nonAssignable that can never be assignable from it's parameter $handlerClass"
          )
      }
    case other =>
      throw new RuntimeException(
        s"EventHandler method $method must defined at most one non context parameter to handle events, the parameters defined were: ${other
          .mkString(",")}"
      )
  }

  def invoke(obj: AnyRef, event: AnyRef, context: EventContext): Unit = {
    val ctx = InvocationContext(event, context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }
}

private class SnapshotHandlerInvoker(val method: Method) {
  private val annotation = method.getAnnotation(classOf[SnapshotHandler])

  private val parameters = ReflectionHelper.getParameterHandlers[AnyRef, SnapshotContext](method)()

  // Verify that there is at most one event handler
  val snapshotClass: Class[_] = parameters.collect {
    case MainArgumentParameterHandler(clazz) => clazz
  } match {
    case Array(handlerClass) => handlerClass
    case other =>
      throw new RuntimeException(
        s"SnapshotHandler method $method must defined at most one non context parameter to handle snapshots, the parameters defined were: ${other
          .mkString(",")}"
      )
  }

  def invoke(obj: AnyRef, snapshot: AnyRef, context: SnapshotContext): Unit = {
    val ctx = InvocationContext(snapshot, context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }
}

private class SnapshotInvoker(val method: Method) {

  private val parameters = ReflectionHelper.getParameterHandlers[AnyRef, SnapshotContext](method)()

  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(
        s"Don't know how to handle argument of type $clazz in snapshot method: " + method.getName
      )
    case _ =>
  }

  def invoke(obj: AnyRef, context: SnapshotContext): AnyRef = {
    val ctx = InvocationContext(null.asInstanceOf[AnyRef], context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }

}
