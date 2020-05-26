package io.cloudstate.javasupport.impl.crud

import com.example.crud.shoppingcart.Shoppingcart
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{ByteString, Any => JavaPbAny}
import io.cloudstate.javasupport.crud.{
  CommandContext,
  CommandHandler,
  CrudContext,
  CrudEntity,
  CrudEntityCreationContext,
  SnapshotContext,
  SnapshotHandler
}
import io.cloudstate.javasupport.impl.{AnySupport, ResolvedServiceMethod, ResolvedType}
import io.cloudstate.javasupport.{Context, EntityId, ServiceCall, ServiceCallFactory, ServiceCallRef}
import org.scalatest.{Matchers, WordSpec}

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
    var emitted = Seq.empty[AnyRef]
    override def sequenceNumber(): Long = 10
    override def commandName(): String = "AddItem"
    override def commandId(): Long = 20
    override def emit(event: AnyRef): Unit = emitted :+= event
    override def entityId(): String = "foo"
    override def fail(errorMessage: String): RuntimeException = ???
    override def forward(to: ServiceCall): Unit = ???
    override def effect(effect: ServiceCall, synchronous: Boolean): Unit = ???
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

  def state(any: Any): JavaPbAny = anySupport.encodeJava(any)

  "Crud annotation support" should {
    "support entity construction" when {

      "there is a noarg constructor" in {
        create(classOf[NoArgConstructorTest])
      }

      "there is a constructor with an EntityId annotated parameter" in {
        create(classOf[EntityIdArgConstructorTest])
      }

      "there is a constructor with a EventSourcedEntityCreationContext parameter" in {
        create(classOf[CreationContextArgConstructorTest])
      }

      "there is a constructor with multiple parameters" in {
        create(classOf[MultiArgConstructorTest])
      }

      "fail if the constructor contains an unsupported parameter" in {
        a[RuntimeException] should be thrownBy create(classOf[UnsupportedConstructorParameter])
      }

    }

    "support command handlers" when {

      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItem() = Wrapped("blah")
        }, method)
        decodeWrapped(handler.handleCommand(command("nothing"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def addItem(msg: String) = Wrapped(msg)
        }, method)
        decodeWrapped(handler.handleCommand(command("blah"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(
          new {
            @CommandHandler
            def addItem(msg: String, @EntityId eid: String, ctx: CommandContext): Wrapped = {
              eid should ===("foo")
              ctx.commandName() should ===("AddItem")
              Wrapped(msg)
            }
          },
          method
        )
        decodeWrapped(handler.handleCommand(command("blah"), new MockCommandContext).get) should ===(Wrapped("blah"))
      }

      "allow emitting events" in {
        val handler = create(
          new {
            @CommandHandler
            def addItem(msg: String, ctx: CommandContext): Wrapped = {
              ctx.emit(msg + " event")
              ctx.commandName() should ===("AddItem")
              Wrapped(msg)
            }
          },
          method
        )
        val ctx = new MockCommandContext
        decodeWrapped(handler.handleCommand(command("blah"), ctx).get) should ===(Wrapped("blah"))
        ctx.emitted should ===(Seq("blah event"))
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItem(msg: String, ctx: SnapshotContext) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def addItem(msg: String, ctx: CommandContext) =
            Wrapped(msg)
          @CommandHandler
          def addItem(msg: String) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrongName(msg: String) =
            Wrapped(msg)
        }, method)
      }

      "fail if there's a CRDT command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @io.cloudstate.javasupport.crdt.CommandHandler
            def addItem(msg: String) =
              Wrapped(msg)
          }, method)
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[CommandHandler].getName)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def addItem(): Wrapped = throw new RuntimeException("foo")
        }, method)
        val ex = the[RuntimeException] thrownBy handler.handleCommand(command("nothing"), new MockCommandContext)
        ex.getMessage should ===("foo")
      }

    }

    "support state handlers" when {
      val ctx = new SnapshotContext with BaseContext {
        override def sequenceNumber(): Long = 10
        override def entityId(): String = "foo"
      }

      "single parameter" in {
        var invoked = false
        val handler = create(new {
          @SnapshotHandler
          def handleState(state: String): Unit = {
            state should ===("snap!")
            invoked = true
          }
        })
        handler.handleState(state("snap!"), ctx)
        invoked shouldBe true
      }

      "context parameter" in {
        var invoked = false
        val handler = create(new {
          @SnapshotHandler
          def handleState(state: String, context: SnapshotContext): Unit = {
            state should ===("snap!")
            context.sequenceNumber() should ===(10)
            invoked = true
          }
        })
        handler.handleState(state("snap!"), ctx)
        invoked shouldBe true
      }

      "fail if there's a bad context" in {
        a[RuntimeException] should be thrownBy create(new {
          @SnapshotHandler
          def handleState(state: String, context: CommandContext) = ()
        })
      }

      "fail if there's no snapshot parameter" in {
        a[RuntimeException] should be thrownBy create(new {
          @SnapshotHandler
          def handleState(context: SnapshotContext) = ()
        })
      }

      "fail if there's no snapshot handler for the given type" in {
        val handler = create(new {
          @SnapshotHandler
          def handleState(state: Int) = ()
        })
        a[RuntimeException] should be thrownBy handler.handleState(state(10), ctx)
      }

    }
  }
}

import Matchers._

@CrudEntity
private class NoArgConstructorTest() {}

@CrudEntity
private class EntityIdArgConstructorTest(@EntityId entityId: String) {
  entityId should ===("foo")
}

@CrudEntity
private class CreationContextArgConstructorTest(ctx: CrudEntityCreationContext) {
  ctx.entityId should ===("foo")
}

@CrudEntity
private class MultiArgConstructorTest(ctx: CrudContext, @EntityId entityId: String) {
  ctx.entityId should ===("foo")
  entityId should ===("foo")
}

@CrudEntity
private class UnsupportedConstructorParameter(foo: String)
