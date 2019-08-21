package io.cloudstate.javasupport.impl.eventsourced

import java.lang.reflect.{Constructor, InvocationTargetException, Method}
import java.util.Optional

import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedServiceMethod, ResolvedType}

import scala.collection.concurrent.TrieMap
import com.google.protobuf.{Descriptors, Any => JavaPbAny}

/**
  * Annotation based implementation of the [[EventSourcedEntityFactory]].
  */
private[impl] class AnnotationSupport(entityClass: Class[_], anySupport: AnySupport,
                                      serviceMethods: Seq[ResolvedServiceMethod],
                                      factory: Option[EventSourcedEntityCreationContext => AnyRef] = None) extends EventSourcedEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport,
    serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behaviorReflectionCache = TrieMap.empty[Class[_], EventBehaviorReflection]
  // Eagerly reflect over/validate the entity class
  behaviorReflectionCache.put(entityClass, EventBehaviorReflection(entityClass, serviceMethods))

  override def create(context: EventSourcedContext): EventSourcedEntityHandler = {
    new EntityHandler(context)
  }

  private val constructor: EventSourcedEntityCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on event sourced entities: $entityClass")
    }
  }

  private def getCachedBehaviorReflection(behavior: AnyRef) = {
    behaviorReflectionCache.getOrElseUpdate(behavior.getClass, EventBehaviorReflection(behavior.getClass, serviceMethods))
  }

  private def validateBehaviors(behaviors: Seq[AnyRef]): Seq[AnyRef] = {
    behaviors.foreach(getCachedBehaviorReflection)
    // todo maybe validate that every command is served?
    behaviors
  }

  private class EntityHandler(context: EventSourcedContext) extends EventSourcedEntityHandler {
    private var currentBehaviors = {
      var explicitlySetBehaviors: Option[Seq[AnyRef]] = None
      var active = true
      val ctx = new DelegatingEventSourcedContext(context) with EventSourcedEntityCreationContext {
        override def become(behaviors: AnyRef*): Unit = {
          if (!active) throw new IllegalStateException("Context is not active!")
          explicitlySetBehaviors = Some(validateBehaviors(behaviors))
        }
        override def entityId(): String = context.entityId()
      }

      val entity = constructor(ctx)
      active = false
      explicitlySetBehaviors match {
        case Some(behaviors) => behaviors
        case None => Seq(entity)
      }
    }

    override def handleEvent(anyEvent: JavaPbAny, context: EventContext): Unit = unwrap {
      val event = anySupport.decode(anyEvent).asInstanceOf[AnyRef]

      if (!currentBehaviors.exists { behavior =>
        getCachedBehaviorReflection(behavior).getCachedEventHandlerForClass(event.getClass) match {
          case Some(handler) =>
            var active = true
            val ctx = new DelegatingEventSourcedContext(context) with EventBehaviorContext {
              override def become(behavior: AnyRef*): Unit = {
                if (!active) throw new IllegalStateException("Context is not active!")
                currentBehaviors = validateBehaviors(behavior)
              }
              override def sequenceNumber(): Long = context.sequenceNumber()
            }
            handler.invoke(behavior, event, ctx)
            active = false
            true
          case None =>
            false
        }
      }) {
        throw new RuntimeException(s"No event handler found for event ${event.getClass} on any of the current behaviors: $behaviorsString")
      }
    }

    override def handleCommand(command: JavaPbAny, context: CommandContext): JavaPbAny = unwrap {
      val maybeResult = currentBehaviors.collectFirst(Function.unlift { behavior =>
        getCachedBehaviorReflection(behavior).commandHandlers.get(context.commandName()).map { handler =>
          handler.invoke(behavior, command, context)
        }
      })

      maybeResult.getOrElse {
        throw new RuntimeException(s"No command handler found for command [${context.commandName()}] on any of the current behaviors: $behaviorsString")
      }
    }

    override def handleSnapshot(anySnapshot: JavaPbAny, context: SnapshotContext): Unit = unwrap {
      val snapshot = anySupport.decode(anySnapshot).asInstanceOf[AnyRef]
      if (!currentBehaviors.exists { behavior =>
        getCachedBehaviorReflection(behavior).getCachedSnapshotHandlerForClass(snapshot.getClass) match {
          case Some(handler) =>
            var active = true
            val ctx = new DelegatingEventSourcedContext(context) with SnapshotBehaviorContext {
              override def become(behavior: AnyRef*): Unit = {
                if (!active) throw new IllegalStateException("Context is not active!")
                currentBehaviors = validateBehaviors(behavior)
              }
              override def sequenceNumber(): Long = context.sequenceNumber()
            }
            handler.invoke(behavior, snapshot, ctx)
            active = false
            true

          case None =>
            false
        }
      }) {
        throw new RuntimeException(s"No snapshot handler found for snapshot ${snapshot.getClass} on any of the current behaviors $behaviorsString")
      }
    }

    override def snapshot(context: SnapshotContext): Optional[JavaPbAny] = unwrap {
      currentBehaviors.collectFirst(Function.unlift { behavior =>
        getCachedBehaviorReflection(behavior).snapshotInvoker.map { invoker =>
          invoker.invoke(behavior, context)
        }
      }) match {
        case Some(invoker) =>
          Optional.ofNullable(anySupport.encodeJava(invoker))
        case None => Optional.empty()
      }
    }

    private def unwrap[T](block: => T): T = try {
      block
    } catch {
      case ite: InvocationTargetException if ite.getCause != null =>
        throw ite.getCause
    }

    private def behaviorsString = currentBehaviors.map(_.getClass).mkString(", ")
  }


  private abstract class DelegatingEventSourcedContext(delegate: EventSourcedContext) extends EventSourcedContext {
    override def entityId(): String = delegate.entityId()
  }
}

private class EventBehaviorReflection(eventHandlers: Map[Class[_], EventHandlerInvoker],
                                      val commandHandlers: Map[String, CommandHandlerInvoker],
                                      snapshotHandlers: Map[Class[_], SnapshotHandlerInvoker],
                                      val snapshotInvoker: Option[SnapshotInvoker]) {

  /**
    * We use a cache in addition to the info we've discovered by reflection so that an event handler can be declared
    * for a superclass of an event.
    */
  private val eventHandlerCache = TrieMap.empty[Class[_], Option[EventHandlerInvoker]]
  private val snapshotHandlerCache = TrieMap.empty[Class[_], Option[SnapshotHandlerInvoker]]

  def getCachedEventHandlerForClass(clazz: Class[_]): Option[EventHandlerInvoker] = {
    eventHandlerCache.getOrElseUpdate(clazz, getHandlerForClass(eventHandlers)(clazz))
  }

  def getCachedSnapshotHandlerForClass(clazz: Class[_]): Option[SnapshotHandlerInvoker] = {
    snapshotHandlerCache.getOrElseUpdate(clazz, getHandlerForClass(snapshotHandlers)(clazz))
  }

  private def getHandlerForClass[T](handlers: Map[Class[_], T])(clazz: Class[_]): Option[T] = {
    handlers.get(clazz) match {
      case some@ Some(_) => some
      case None =>
        clazz.getInterfaces.collectFirst(Function.unlift(getHandlerForClass(handlers))) match {
          case some@ Some(_) => some
          case None if clazz.getSuperclass != null => getHandlerForClass(handlers)(clazz.getSuperclass)
          case None => None
        }
    }
  }

}

private object EventBehaviorReflection {
  def apply(behaviorClass: Class[_], serviceMethods: Seq[ResolvedServiceMethod]): EventBehaviorReflection = {

    val allMethods = ReflectionHelper.getAllDeclaredMethods(behaviorClass)
    val eventHandlers = allMethods
      .filter(_.getAnnotation(classOf[EventHandler]) != null)
      .map { method =>
        new EventHandlerInvoker(ReflectionHelper.ensureAccessible(method))
      }.groupBy(_.eventClass)
      .map {
        case (eventClass, Seq(invoker)) => (eventClass: Any) -> invoker
        case (clazz, many) =>
          throw new RuntimeException(s"Multiple methods found for handling event of type $clazz: ${many.map(_.method.getName)}")
      }.asInstanceOf[Map[Class[_], EventHandlerInvoker]]

    val commandHandlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = serviceMethods.find(_.name == name).getOrElse {
          throw new RuntimeException(s"Command handler method ${method.getName} for command $name found, but the service has no command by that name.")
        }

        new CommandHandlerInvoker(ReflectionHelper.ensureAccessible(method), serviceMethod)
      }.groupBy(_.serviceMethod.name)
      .map {
        case (commandName, Seq(invoker)) => commandName -> invoker
        case (commandName, many) => throw new RuntimeException(s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}")
      }

    val snapshotHandlers = allMethods
      .filter(_.getAnnotation(classOf[SnapshotHandler]) != null)
      .map { method =>
        new SnapshotHandlerInvoker(ReflectionHelper.ensureAccessible(method))
      }.groupBy(_.snapshotClass)
      .map {
        case (snapshotClass, Seq(invoker)) => (snapshotClass: Any) -> invoker
        case (clazz, many) => throw new RuntimeException(s"Multiple methods found for handling snapshot of type $clazz: ${many.map(_.method.getName)}")
      }.asInstanceOf[Map[Class[_], SnapshotHandlerInvoker]]

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

    new EventBehaviorReflection(eventHandlers, commandHandlers, snapshotHandlers, snapshotInvoker)
  }
}

private class EntityConstructorInvoker(constructor: Constructor[_]) extends (EventSourcedEntityCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[EventSourcedEntityCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: EventSourcedEntityCreationContext): AnyRef = {
    val ctx = InvocationContext("", context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}

private class EventHandlerInvoker(val method: Method) {

  private val annotation = method.getAnnotation(classOf[EventHandler])

  private val parameters = ReflectionHelper.getParameterHandlers[EventBehaviorContext](method)()

  private def annotationEventClass = annotation.eventClass() match {
    case obj if obj == classOf[Object] => None
    case clazz => Some(clazz)
  }

  // Verify that there is at most one event handler
  val eventClass: Class[_] = parameters.collect {
    case MainArgumentParameterHandler(clazz) => clazz
  } match {
    case Array() => annotationEventClass.getOrElse(classOf[Object])
    case Array(handlerClass) => annotationEventClass match {
      case None => handlerClass
      case Some(annotated) if handlerClass.isAssignableFrom(annotated) || annotated.isInterface =>
        annotated
      case Some(nonAssignable) =>
        throw new RuntimeException(s"EventHandler method $method has defined an eventHandler class $nonAssignable that can never be assignable from it's parameter $handlerClass")
    }
    case other =>
      throw new RuntimeException(s"EventHandler method $method must defined at most one non context parameter to handle events, the parameters defined were: ${other.mkString(",")}")
  }

  def invoke(obj: AnyRef, event: AnyRef, context: EventBehaviorContext): Unit = {
    val ctx = InvocationContext(event, context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }
}

private class CommandHandlerInvoker(val method: Method, val serviceMethod: ResolvedServiceMethod) {
  private val annotation = method.getAnnotation(classOf[CommandHandler])

  private val parameters = ReflectionHelper.getParameterHandlers[CommandContext](method)()

  if (parameters.count(_.isInstanceOf[MainArgumentParameterHandler[_]]) > 1) {
    throw new RuntimeException(s"CommandHandler method $method must defined at most one non context parameter to handle commands, the parameters defined were: ${parameters.collect { case MainArgumentParameterHandler(clazz) => clazz.getName }.mkString(",")}")
  }
  parameters.foreach {
    case MainArgumentParameterHandler(inClass) if !inClass.isAssignableFrom(serviceMethod.inputType.typeClass) =>
      throw new RuntimeException(s"Incompatible command class $inClass for command ${serviceMethod.name}, expected ${serviceMethod.inputType.typeClass}")
    case _ =>
  }
  if (!serviceMethod.outputType.typeClass.isAssignableFrom(method.getReturnType)) {
    throw new RuntimeException(s"Incompatible return class ${method.getReturnType} for command ${serviceMethod.name}, expected ${serviceMethod.outputType.typeClass}")
  }

  def invoke(obj: AnyRef, command: JavaPbAny, context: CommandContext): JavaPbAny = {
    val decodedCommand = serviceMethod.inputType.parseFrom(command.getValue).asInstanceOf[AnyRef]
    val ctx = InvocationContext(decodedCommand, context)
    val result = method.invoke(obj, parameters.map(_.apply(ctx)): _*)
    JavaPbAny.newBuilder().setTypeUrl(serviceMethod.outputType.typeUrl)
      .setValue(serviceMethod.outputType.asInstanceOf[ResolvedType[Any]].toByteString(result))
      .build()
  }
}

private class SnapshotHandlerInvoker(val method: Method) {
  private val annotation = method.getAnnotation(classOf[SnapshotHandler])

  private val parameters = ReflectionHelper.getParameterHandlers[SnapshotBehaviorContext](method)()

  // Verify that there is at most one event handler
  val snapshotClass: Class[_] = parameters.collect {
    case MainArgumentParameterHandler(clazz) => clazz
  } match {
    case Array(handlerClass) => handlerClass
    case other =>
      throw new RuntimeException(s"SnapshotHandler method $method must defined at most one non context parameter to handle snapshots, the parameters defined were: ${other.mkString(",")}")
  }

  def invoke(obj: AnyRef, snapshot: AnyRef, context: SnapshotBehaviorContext): Unit = {
    val ctx = InvocationContext(snapshot, context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }
}

private class SnapshotInvoker(val method: Method) {

  private val parameters = ReflectionHelper.getParameterHandlers[SnapshotContext](method)()

  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in snapshot method: " + method.getName)
    case _ =>
  }

  def invoke(obj: AnyRef, context: SnapshotContext): AnyRef = {
    val ctx = InvocationContext("", context)
    method.invoke(obj, parameters.map(_.apply(ctx)): _*)
  }

}

