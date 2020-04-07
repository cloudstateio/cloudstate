package io.cloudstate.javasupport.impl.crdt

import java.lang.reflect.{Constructor, Executable}
import java.util.function.Consumer
import java.util.{function, Optional}

import com.google.protobuf.{Any => JavaPbAny}
import io.cloudstate.javasupport.crdt._
import io.cloudstate.javasupport.impl.ReflectionHelper.{
  InvocationContext,
  MainArgumentParameterHandler,
  MethodParameter,
  ParameterHandler
}
import io.cloudstate.javasupport.impl.{ReflectionHelper, ResolvedType}
import io.cloudstate.javasupport.{ServiceCall, ServiceCallFactory}

import scala.reflect.ClassTag

object CrdtHelper {
  object CrdtAnnotationHelper {
    private case class CrdtInjector[C <: Crdt, T](crdtClass: Class[C], create: CrdtFactory => T, wrap: C => T)
    private def simple[C <: Crdt: ClassTag](create: CrdtFactory => C)() = {
      val clazz = implicitly[ClassTag[C]].runtimeClass.asInstanceOf[Class[C]]
      clazz -> CrdtInjector[C, C](clazz, create, identity).asInstanceOf[CrdtInjector[Crdt, AnyRef]]
    }
    private def orMapWrapper[W: ClassTag, C <: Crdt](wrap: ORMap[AnyRef, C] => W) =
      implicitly[ClassTag[W]].runtimeClass
        .asInstanceOf[Class[C]] -> CrdtInjector(classOf[ORMap[AnyRef, C]], f => wrap(f.newORMap()), wrap)
        .asInstanceOf[CrdtInjector[Crdt, AnyRef]]

    private val injectorMap: Map[Class[_], CrdtInjector[Crdt, AnyRef]] = Map(
      simple(_.newGCounter()),
      simple(_.newPNCounter()),
      simple(_.newGSet()),
      simple(_.newORSet()),
      simple(_.newFlag()),
      simple(_.newLWWRegister()),
      simple(_.newORMap()),
      simple(_.newVote()),
      orMapWrapper[LWWRegisterMap[AnyRef, AnyRef], LWWRegister[AnyRef]](new LWWRegisterMap(_)),
      orMapWrapper[PNCounterMap[AnyRef], PNCounter](new PNCounterMap(_))
    )

    private def injector[C <: Crdt, T](clazz: Class[T]): CrdtInjector[C, T] =
      injectorMap.get(clazz) match {
        case Some(injector: CrdtInjector[C, T] @unchecked) => injector
        case None => throw new RuntimeException(s"Don't know how to inject CRDT of type $clazz")
      }

    def crdtParameterHandlers[C <: CrdtContext with CrdtFactory]
        : PartialFunction[MethodParameter, ParameterHandler[C]] = {
      case crdt if injectorMap.contains(crdt.parameterType) =>
        new CrdtParameterHandler[C, Crdt, AnyRef](injectorMap(crdt.parameterType), crdt.method)
      case crdt
          if crdt.parameterType == classOf[Optional[_]] &&
          injectorMap.contains(ReflectionHelper.getFirstParameter(crdt.genericParameterType)) =>
        new OptionalCrdtParameterHandler(
          injectorMap(ReflectionHelper.getFirstParameter(crdt.genericParameterType)),
          crdt.method
        )
    }

    private class CrdtParameterHandler[C <: CrdtContext with CrdtFactory, D <: Crdt, T](injector: CrdtInjector[D, T],
                                                                                        method: Executable)
        extends ParameterHandler[C] {
      override def apply(ctx: InvocationContext[C]): AnyRef = {
        val state = ctx.context.state(injector.crdtClass)
        if (state.isPresent) {
          injector.wrap(state.get()).asInstanceOf[AnyRef]
        } else {
          injector.create(ctx.context).asInstanceOf[AnyRef]
        }
      }
    }

    private class OptionalCrdtParameterHandler[C <: Crdt, T](injector: CrdtInjector[C, T], method: Executable)
        extends ParameterHandler[CrdtContext] {

      import scala.compat.java8.OptionConverters._
      override def apply(ctx: InvocationContext[CrdtContext]): AnyRef =
        ctx.context.state(injector.crdtClass).asScala.map(injector.wrap).asJava
    }

  }

  final class AdaptedStreamedCommandContext(val delegate: StreamedCommandContext[JavaPbAny],
                                            resolvedType: ResolvedType[AnyRef])
      extends StreamedCommandContext[AnyRef] {
    override def isStreamed: Boolean = delegate.isStreamed

    def onChange(subscriber: function.Function[SubscriptionContext, Optional[AnyRef]]): Unit =
      delegate.onChange { ctx =>
        val result = subscriber(ctx)
        if (result.isPresent) {
          Optional.of(
            JavaPbAny
              .newBuilder()
              .setTypeUrl(resolvedType.typeUrl)
              .setValue(resolvedType.toByteString(result.get))
              .build()
          )
        } else {
          Optional.empty()
        }
      }

    override def onCancel(effect: Consumer[StreamCancelledContext]): Unit = delegate.onCancel(effect)

    override def serviceCallFactory(): ServiceCallFactory = delegate.serviceCallFactory()
    override def entityId(): String = delegate.entityId()
    override def commandId(): Long = delegate.commandId()
    override def commandName(): String = delegate.commandName()

    override def state[T <: Crdt](crdtClass: Class[T]): Optional[T] = delegate.state(crdtClass)
    override def delete(): Unit = delegate.delete()

    override def forward(to: ServiceCall): Unit = delegate.forward(to)
    override def fail(errorMessage: String): RuntimeException = delegate.fail(errorMessage)
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = delegate.effect(effect, synchronous)

    override def newGCounter(): GCounter = delegate.newGCounter()
    override def newPNCounter(): PNCounter = delegate.newPNCounter()
    override def newGSet[T](): GSet[T] = delegate.newGSet()
    override def newORSet[T](): ORSet[T] = delegate.newORSet()
    override def newFlag(): Flag = delegate.newFlag()
    override def newLWWRegister[T](value: T): LWWRegister[T] = delegate.newLWWRegister(value)
    override def newORMap[K, V <: Crdt](): ORMap[K, V] = delegate.newORMap()
    override def newVote(): Vote = delegate.newVote()
  }

  final class EntityConstructorInvoker(constructor: Constructor[_]) extends (CrdtCreationContext => AnyRef) {
    private val parameters =
      ReflectionHelper.getParameterHandlers[CrdtCreationContext](constructor)(
        CrdtAnnotationHelper.crdtParameterHandlers
      )
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
}
