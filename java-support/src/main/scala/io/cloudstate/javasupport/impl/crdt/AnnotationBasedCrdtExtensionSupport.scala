package io.cloudstate.javasupport.impl.crdt

import java.lang.reflect.InvocationTargetException
import java.util.Optional

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.EntitySupportFactory
import io.cloudstate.javasupport.crdt._
import io.cloudstate.javasupport.impl.ReflectionHelper._
import io.cloudstate.javasupport.impl._

import scala.reflect.ClassTag

/**
 * Annotation based implementation of the [[io.cloudstate.javasupport.crdt.CrdtEntityFactory]].
 */
private[impl] class AnnotationBasedCrdtExtensionSupport(
    entitySupportFactory: EntitySupportFactory,
    entityClass: Class[_],
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[CrdtCreationContext => AnyRef] = None
) extends CrdtEntityFactory
    with ResolvedEntityFactory {
  // TODO JavaDoc
  def this(entitySupportFactory: EntitySupportFactory,
           anySupport: AnySupport,
           serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entitySupportFactory,
         entitySupportFactory.typeClass(),
         anySupport,
         anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val (commandHandlers, streamedCommandHandlers) = {
    val allMethods = ReflectionHelper.getAllDeclaredMethods(entitySupportFactory.typeClass())

    ReflectionHelper.validateNoBadMethods(allMethods, classOf[CrdtEntity], Set(classOf[CommandHandler]))
    val handlers = allMethods
      .filter(_.getAnnotation(classOf[CommandHandler]) != null)
      .map { method =>
        val annotation = method.getAnnotation(classOf[CommandHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = resolvedMethods.getOrElse(name, {
          throw new RuntimeException(
            s"Command handler method ${method.getName} for command $name found, but the service has no command by that name."
          )
        })
        (ReflectionHelper.ensureAccessible(method), serviceMethod)
      }

    def getHandlers[C <: CrdtContext with CrdtFactory: ClassTag](streamed: Boolean) =
      handlers
        .filter(_._2.outputStreamed == streamed)
        .map {
          case (method, serviceMethod) =>
            new CommandHandlerInvoker[C](method, serviceMethod, CrdtAnnotationHelper.crdtParameterHandlers[C])
        }
        .groupBy(_.serviceMethod.name)
        .map {
          case (commandName, Seq(invoker)) => commandName -> invoker
          case (commandName, many) =>
            throw new RuntimeException(
              s"Multiple methods found for handling command of name $commandName: ${many.map(_.method.getName)}"
            )
        }

    (getHandlers[CommandContext](false), getHandlers[StreamedCommandContext[AnyRef]](true))
  }

  // TODO JavaDoc
  override def create(context: CrdtCreationContext): CrdtEntityHandler = {
    val entity = entitySupportFactory.create(context, null)
    new EntityHandler(entity)
  }

  private class EntityHandler(entity: AnyRef) extends CrdtEntityHandler {

    override def handleCommand(command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = unwrap {
      val maybeResult = commandHandlers.get(context.commandName()).map { handler =>
        handler.invoke(entity, command, context)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(
          s"No command handler found for command [${context.commandName()}] on CRDT entity: $entityClass"
        )
      }
    }

    override def handleStreamedCommand(command: JavaPbAny,
                                       context: StreamedCommandContext[JavaPbAny]): Optional[JavaPbAny] = unwrap {
      val maybeResult = streamedCommandHandlers.get(context.commandName()).map { handler =>
        val adaptedContext =
          new AdaptedStreamedCommandContext(context,
                                            handler.serviceMethod.outputType.asInstanceOf[ResolvedType[AnyRef]])
        handler.invoke(entity, command, adaptedContext)
      }

      maybeResult.getOrElse {
        throw new RuntimeException(
          s"No streamed command handler found for command [${context.commandName()}] on CRDT entity: $entityClass"
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
  }

}
