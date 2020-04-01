package io.cloudstate.javasupport.impl.eventsourced

import java.lang.reflect.InvocationTargetException
import java.util.Optional

import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.{AnySupport, ResolvedEntityFactory, ResolvedServiceMethod}
import io.cloudstate.javasupport.{EntitySupportFactory, ServiceCallFactory}

/**
 * Annotation based implementation of the [[EventSourcedEntityFactory]].
 */
private[impl] class AnnotationBasedEventSourcedExtensionSupport(
    entitySupportFactory: EntitySupportFactory,
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]],
    factory: Option[EventSourcedEntityCreationContext => AnyRef] = None
) extends EventSourcedEntityFactory
    with ResolvedEntityFactory {

  def this(entitySupportFactory: EntitySupportFactory,
           anySupport: AnySupport,
           serviceDescriptor: Descriptors.ServiceDescriptor) =
    this(entitySupportFactory, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = EventBehaviorReflection(entitySupportFactory.typeClass(), resolvedMethods)

  override def create(context: EventSourcedContext): EventSourcedEntityHandler =
    new EntityHandler(this.entitySupportFactory, context)

  private class EntityHandler(entitySupportFactory: EntitySupportFactory, context: EventSourcedContext)
      extends EventSourcedEntityHandler {
    private val entity = entitySupportFactory.create(new DelegatingEventSourcedContext(context)
                                                     with EventSourcedEntityCreationContext,
                                                     context.entityId())

    override def handleEvent(anyEvent: JavaPbAny, context: EventContext): Unit = unwrap {
      val event = anySupport.decode(anyEvent).asInstanceOf[AnyRef]

      behavior.getCachedEventHandlerForClass(event.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingEventSourcedContext(context) with EventContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, event, ctx)
        case None =>
          throw new RuntimeException(
            s"No event handler found for event ${event.getClass} on $behaviorsString"
          )
      }
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

    override def handleSnapshot(anySnapshot: JavaPbAny, context: SnapshotContext): Unit = unwrap {
      val snapshot = anySupport.decode(anySnapshot).asInstanceOf[AnyRef]

      behavior.getCachedSnapshotHandlerForClass(snapshot.getClass) match {
        case Some(handler) =>
          val ctx = new DelegatingEventSourcedContext(context) with SnapshotContext {
            override def sequenceNumber(): Long = context.sequenceNumber()
          }
          handler.invoke(entity, snapshot, ctx)
        case None =>
          throw new RuntimeException(
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
