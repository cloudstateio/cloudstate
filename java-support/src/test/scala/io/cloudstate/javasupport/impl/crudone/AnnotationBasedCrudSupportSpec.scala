package io.cloudstate.javasupport.impl.crudone

import com.example.shoppingcartcrud.Shoppingcart
import com.google.protobuf.{ByteString, Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.{Context, ServiceCall, ServiceCallFactory, ServiceCallRef}
import io.cloudstate.javasupport.crud.{CommandContext, CrudContext, CrudEventContext}
import io.cloudstate.javasupport.impl.eventsourced.AnnotationBasedEventSourcedSupport
import io.cloudstate.javasupport.impl.{AnySupport, ResolvedServiceMethod, ResolvedType}
import org.scalatest.{Matchers, WordSpec}
import com.google.protobuf.any.{Any => ScalaPbAny}

class AnnotationBasedCrudSupportSpec extends WordSpec with Matchers {
  trait BaseContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = new ServiceCallFactory {
      override def lookup[T](serviceName: String, methodName: String, messageType: Class[T]): ServiceCallRef[T] =
        throw new NoSuchElementException
    }
  }

  object MockContext extends CrudContext with BaseContext {
    override def entityId(): String = "foo"
  }

  class MockCommandContext extends CommandContext with BaseContext {
    var emited = Seq.empty[AnyRef]
    override def sequenceNumber(): Long = 10
    override def commandName(): String = "CreateItem"
    override def commandId(): Long = 20
    override def emit(event: AnyRef): Unit = emited :+= event
    override def entityId(): String = "foo"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
  }

  val eventCtx = new CrudEventContext with BaseContext {
    override def sequenceNumber(): Long = 10
    override def entityId(): String = "foo"
  }

  object WrappedResolvedType extends ResolvedType[Wrapped] {
    override def typeClass: Class[Wrapped] = classOf[Wrapped]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/wrapped"
    override def parseFrom(bytes: ByteString): Wrapped = Wrapped(bytes.toStringUtf8)
    override def toByteString(value: Wrapped): ByteString = ByteString.copyFromUtf8(value.value)
  }

  object StringResolvedType extends ResolvedType[String] {
    override def typeClass: Class[String] = classOf[String]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/string"
    override def parseFrom(bytes: ByteString): String = bytes.toStringUtf8
    override def toByteString(value: String): ByteString = ByteString.copyFromUtf8(value)
  }

  case class Wrapped(value: String)
  val anySupport = new AnySupport(Array(Shoppingcart.getDescriptor), this.getClass.getClassLoader)
  val descriptor = Shoppingcart.getDescriptor
    .findServiceByName("ShoppingCart")
    .findMethodByName("AddItem")
  val method = ResolvedServiceMethod(descriptor, StringResolvedType, WrappedResolvedType)

  def create(behavior: AnyRef, methods: ResolvedServiceMethod[_, _]*) =
    new AnnotationBasedCrudSupport(behavior.getClass,
      anySupport,
      methods.map(m => m.descriptor.getName -> m).toMap,
      Some(_ => behavior)).create(MockContext)

  def create(clazz: Class[_]) =
    new AnnotationBasedCrudSupport(clazz, anySupport, Map.empty, None).create(MockContext)

  def command(str: String) =
    ScalaPbAny.toJavaProto(ScalaPbAny(StringResolvedType.typeUrl, StringResolvedType.toByteString(str)))

  def decodeWrapped(any: JavaPbAny): Wrapped = {
    any.getTypeUrl should ===(WrappedResolvedType.typeUrl)
    WrappedResolvedType.parseFrom(any.getValue)
  }

  def event(any: Any): JavaPbAny = anySupport.encodeJava(any)

  "support command handlers" when {

  }
}
