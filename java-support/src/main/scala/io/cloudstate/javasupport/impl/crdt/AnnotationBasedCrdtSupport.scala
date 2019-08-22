package io.cloudstate.javasupport.impl.crdt

import java.lang.reflect.{Constructor, InvocationTargetException}
import java.util.{Optional, function}
import java.util.function.Consumer

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.Context
import io.cloudstate.javasupport.crdt.{CommandContext, CommandHandler, Crdt, CrdtCreationContext, CrdtEntity, CrdtEntityFactory, CrdtEntityHandler, Flag, GCounter, GSet, LWWRegister, LWWRegisterMap, ORMap, ORSet, PNCounter, PNCounterMap, StreamCancelledContext, StreamedCommandContext, SubscriptionContext, Vote}
import io.cloudstate.javasupport.impl.ReflectionHelper.{CommandHandlerInvoker, InvocationContext, MainArgumentParameterHandler}
import io.cloudstate.javasupport.impl.{AnySupport, ReflectionHelper, ResolvedServiceMethod, ResolvedType}

import scala.reflect.ClassTag


/**
  * Annotation based implementation of the [[io.cloudstate.javasupport.crdt.CrdtEntityFactory]].
  */
private[impl] class AnnotationBasedCrdtSupport(entityClass: Class[_], anySupport: AnySupport,
  serviceMethods: Seq[ResolvedServiceMethod],
  factory: Option[CrdtCreationContext => AnyRef] = None) extends CrdtEntityFactory {

  def this(entityClass: Class[_], anySupport: AnySupport,
    serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entityClass, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))


  private val constructor: CrdtCreationContext => AnyRef = factory.getOrElse {
    entityClass.getConstructors match {
      case Array(single) =>
        new EntityConstructorInvoker(ReflectionHelper.ensureAccessible(single))
      case _ =>
        throw new RuntimeException(s"Only a single constructor is allowed on CRDT entities: $entityClass")
    }
  }

  private val (commandHandlers, streamedCommandHandlers) = {
    val allMethods = ReflectionHelper.getAllDeclaredMethods(entityClass)

    ReflectionHelper.validateNoBadMethods(allMethods, classOf[CrdtEntity], Set(classOf[CommandHandler]))
    val handlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = serviceMethods.find(_.name == name).getOrElse {
          throw new RuntimeException(s"Command handler method ${method.getName} for command $name found, but the service has no command by that name.")
        }
        (ReflectionHelper.ensureAccessible(method), serviceMethod)
      }

    def getHandlers[C <: Context : ClassTag](streamed: Boolean) =
      handlers.filter(_._2.outputStreamed == streamed)
      .map {
        case (method, serviceMethod) => new CommandHandlerInvoker[C](method, serviceMethod)
      }
      .groupBy(_.serviceMethod.name)
      .map {
        case (commandName, Seq(invoker)) => commandName -> invoker
        case (commandName, many) => throw new RuntimeException(s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}")
      }

    (getHandlers[CommandContext](false), getHandlers[StreamedCommandContext[AnyRef]](true))
  }

  override def create(context: CrdtCreationContext): CrdtEntityHandler = {
    val entity = constructor(context)
    new EntityHandler(entity)
  }

  private class EntityHandler(entity: AnyRef) extends CrdtEntityHandler {

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      val maybeResult = commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(s"No command handler found for command [${context.commandName()}] on CRDT entity: $entityClass")
      }
    }

    override def handleStreamedCommand(command: JavaPbAny, context: StreamedCommandContext[JavaPbAny]): Optional[JavaPbAny] = unwrap {
      val maybeResult = commandHandlers.get(context.commandName()).map { handler =>
        val adaptedContext = new AdaptedStreamedCommandContext(context, handler.serviceMethod.outputType.asInstanceOf[ResolvedType[AnyRef]])
        handler.invoke(entity, command, adaptedContext)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(s"No streamed command handler found for command [${context.commandName()}] on CRDT entity: $entityClass")
      }
    }

    private def unwrap[T](block: => T): T = try {
      block
    } catch {
      case ite: InvocationTargetException if ite.getCause != null =>
        throw ite.getCause
    }
  }

}

private final class AdaptedStreamedCommandContext(val delegate: StreamedCommandContext[JavaPbAny], resolvedType: ResolvedType[AnyRef]) extends StreamedCommandContext[AnyRef] {
  override def isStreamed: Boolean = delegate.isStreamed

  def onChange(subscriber: function.Function[SubscriptionContext, Optional[AnyRef]]): Unit = {
    delegate.onChange { ctx =>
      val result = subscriber(ctx)
      if (result.isPresent) {
        Optional.of(JavaPbAny.newBuilder()
            .setTypeUrl(resolvedType.typeUrl)
            .setValue(resolvedType.toByteString(result.get))
            .build()
        )
      } else {
        Optional.empty()
      }
    }
  }

  override def onCancel(effect: Consumer[StreamCancelledContext]): Unit = delegate.onCancel(effect)

  override def commandId(): Long = delegate.commandId()
  override def commandName(): String = delegate.commandName()
  override def delete(): Unit = delegate.delete()

  override def effect(): Unit = delegate.effect()
  override def fail(errorMessage: String): Unit = delegate.fail(errorMessage)
  override def forward(): Unit = delegate.forward()

  override def newGCounter(): GCounter = delegate.newGCounter()
  override def newPNCounter(): PNCounter = delegate.newPNCounter()
  override def newGSet[T](): GSet[T] = delegate.newGSet()
  override def newORSet[T](): ORSet[T] = delegate.newORSet()
  override def newFlag(): Flag = delegate.newFlag()
  override def newLWWRegister[T](value: T): LWWRegister[T] = delegate.newLWWRegister(value)
  override def newORMap[K, V <: Crdt](): ORMap[K, V] = delegate.newORMap()
  override def newVote(): Vote = delegate.newVote()
  override def newLWWRegisterMap[K, V](): LWWRegisterMap[K, V] = delegate.newLWWRegisterMap()
  override def newPNCounterMap[K](): PNCounterMap[K] = delegate.newPNCounterMap()
}

private final class EntityConstructorInvoker(constructor: Constructor[_]) extends (CrdtCreationContext => AnyRef) {
  private val parameters = ReflectionHelper.getParameterHandlers[CrdtCreationContext](constructor)()
  parameters.foreach {
    case MainArgumentParameterHandler(clazz) =>
      throw new RuntimeException(s"Don't know how to handle argument of type $clazz in constructor")
    case _ =>
  }

  def apply(context: CrdtCreationContext): AnyRef = {
    val ctx = InvocationContext("", context)
    constructor.newInstance(parameters.map(_.apply(ctx)): _*).asInstanceOf[AnyRef]
  }
}


