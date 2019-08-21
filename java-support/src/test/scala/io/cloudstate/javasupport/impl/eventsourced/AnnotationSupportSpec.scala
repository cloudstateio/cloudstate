package io.cloudstate.javasupport.impl.eventsourced

import java.util.Optional

import com.example.shoppingcart.Shoppingcart
import com.google.protobuf.{ByteString, Any => JavaPbAny}
import io.cloudstate.javasupport.EntityId
import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.{AnySupport, ResolvedServiceMethod, ResolvedType}
import org.scalatest.{Matchers, WordSpec}
import com.google.protobuf.any.{Any => ScalaPbAny}

class AnnotationSupportSpec extends WordSpec with Matchers {
  object MockContext extends EventSourcedContext {
    override def entityId(): String = "foo"
  }

  class MockCommandContext extends CommandContext {
    var emited = Seq.empty[AnyRef]
    override def sequenceNumber(): Long = 10
    override def commandName(): String = "Wrap"
    override def commandId(): Long = 20
    override def emit(event: AnyRef): Unit = emited :+= event
    override def entityId(): String = "foo"
    override def fail(errorMessage: String): Unit = ???
    override def forward(): Unit = ???
    override def effect(): Unit = ???
  }

  val eventCtx = new EventContext {
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
  val method = ResolvedServiceMethod("Wrap", StringResolvedType, WrappedResolvedType)

  def create(behavior: AnyRef, methods: ResolvedServiceMethod*) = {
    new AnnotationSupport(behavior.getClass, anySupport, methods, Some(_ => behavior)).create(MockContext)
  }

  def create(clazz: Class[_]) = {
    new AnnotationSupport(clazz, anySupport, Nil, None).create(MockContext)
  }

  def command(str: String) = {
    ScalaPbAny.toJavaProto(ScalaPbAny(StringResolvedType.typeUrl, StringResolvedType.toByteString(str)))
  }

  def decodeWrapped(any: JavaPbAny) = {
    any.getTypeUrl should ===(WrappedResolvedType.typeUrl)
    WrappedResolvedType.parseFrom(any.getValue)
  }

  def event(any: Any) = anySupport.encodeJava(any)

  "Event sourced annotation support" should {
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

    "support event handlers" when {
      "no arg event handler" in {
        var invoked = false
        val handler = create(new {
          @EventHandler(eventClass = classOf[String])
          def handle() = invoked = true
        })
        handler.handleEvent(event("my-event"), eventCtx)
        invoked shouldBe true
      }

      "single arg event handler" in {
        var invoked = false
        val handler = create(new {
          @EventHandler
          def handle(event: String) = {
            event should ===("my-event")
            invoked = true
          }
        })
        handler.handleEvent(event("my-event"), eventCtx)
        invoked shouldBe true
      }

      "multi arg event handler" in {
        var invoked = false
        val handler = create(new {
          @EventHandler
          def handle(@EntityId eid: String, event: String, ctx: EventContext) = {
            event should ===("my-event")
            eid should===("foo")
            ctx.sequenceNumber() shouldBe 10
            invoked = true
          }
        })
        handler.handleEvent(event("my-event"), eventCtx)
        invoked shouldBe true
      }

      "handle events of a subclass" in {
        var invoked = false
        val handler = create(new {
          @EventHandler
          def handle(event: AnyRef) = {
            event should ===("my-event")
            invoked = true
          }
        })
        handler.handleEvent(event("my-event"), eventCtx)
        invoked shouldBe true
      }

      "handle events of a sub interface" in {
        var invoked = false
        val handler = create(new {
          @EventHandler
          def handle(event: java.io.Serializable) = {
            event should ===("my-event")
            invoked = true
          }
        })
        handler.handleEvent(event("my-event"), eventCtx)
        invoked shouldBe true
      }

      "allow changing behavior" in {
        var invoked1 = false
        var invoked2 = false
        val handler = create(new {
          @EventHandler
          def handle(event: String, ctx: EventBehaviorContext) = {
            event should ===("event-one")
            ctx.become(new {
              @EventHandler
              def handle(event: String) = {
                event should ===("event-two")
                invoked2 = true
              }
            })
            invoked1 = true
          }
        })
        handler.handleEvent(event("event-one"), eventCtx)
        invoked1 shouldBe true
        handler.handleEvent(event("event-two"), eventCtx)
        invoked2 shouldBe true
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @EventHandler
          def handle(event: String, ctx: CommandContext) = ()
        })
      }

      "fail if the event handler class conflicts with the event class" in {
        a[RuntimeException] should be thrownBy create(new {
          @EventHandler(eventClass = classOf[Integer])
          def handle(event: String) = ()
        })
      }

      "fail if there are two event handlers for the same type" in {
        a[RuntimeException] should be thrownBy create(new {
          @EventHandler
          def handle1(event: String) = ()

          @EventHandler
          def handle2(event: String) = ()
        })
      }

      "fail if an EntityId annotated parameter is not a string" in {
        a[RuntimeException] should be thrownBy create(new {
          @EventHandler
          def handle(event: String, @EntityId entityId: Int) = ()
        })
      }

    }

    "support command handlers" when {

      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def wrap() = Wrapped("blah")
        }, method)
        decodeWrapped(handler.handleCommand(command("nothing"), new MockCommandContext)) should ===(Wrapped("blah"))
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def wrap(msg: String) = Wrapped(msg)
        }, method)
        decodeWrapped(handler.handleCommand(command("blah"), new MockCommandContext)) should ===(Wrapped("blah"))
      }

      "multi arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def wrap(msg: String, @EntityId eid: String, ctx: CommandContext) = {
            eid should ===("foo")
            ctx.commandName() should ===("Wrap")
            Wrapped(msg)
          }
        }, method)
        decodeWrapped(handler.handleCommand(command("blah"), new MockCommandContext)) should ===(Wrapped("blah"))
      }

      "allow emiting events" in {
        val handler = create(new {
          @CommandHandler
          def wrap(msg: String, ctx: CommandContext) = {
            ctx.emit(msg + " event")
            ctx.commandName() should ===("Wrap")
            Wrapped(msg)
          }
        }, method)
        val ctx = new MockCommandContext
        decodeWrapped(handler.handleCommand(command("blah"), ctx)) should ===(Wrapped("blah"))
        ctx.emited should ===(Seq("blah event"))
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrap(msg: String, ctx: EventContext) = {
            Wrapped(msg)
          }
        }, method)
      }

      "fail if there's two command handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def wrap(msg: String, ctx: CommandContext) = {
            Wrapped(msg)
          }
          @CommandHandler
          def wrap(msg: String) = {
            Wrapped(msg)
          }
        }, method)
      }

      "fail if there's no command with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @CommandHandler
          def doWrap(msg: String) = {
            Wrapped(msg)
          }
        }, method)
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @CommandHandler
          def wrap(): Wrapped = throw new RuntimeException("foo")
        }, method)
        val ex = the [RuntimeException] thrownBy handler.handleCommand(command("nothing"), new MockCommandContext)
        ex.getMessage should ===("foo")
      }

    }

    "support snapshots" when {
      val ctx = new SnapshotContext {
        override def sequenceNumber(): Long = 10
        override def entityId(): String = "foo"
      }

      "no arg parameter" in {
        val handler = create(new {
          @Snapshot
          def createSnapshot: String = "snap!"
        })
        val snapshot = handler.snapshot(ctx)
        snapshot.isPresent shouldBe true
        anySupport.decode(snapshot.get) should ===("snap!")
      }

      "context parameter" in {
        val handler = create(new {
          @Snapshot
          def createSnapshot(ctx: SnapshotContext): String = {
            ctx.entityId() should ===("foo")
            "snap!"
          }
        })
        val snapshot = handler.snapshot(ctx)
        snapshot.isPresent shouldBe true
        anySupport.decode(snapshot.get) should ===("snap!")
      }

      "fail if there's two snapshot methods" in {
        a[RuntimeException] should be thrownBy create(new {
          @Snapshot
          def createSnapshot1: String = "snap!"
          @Snapshot
          def createSnapshot2: String = "snap!"
        })
      }

      "fail if there's a bad context" in {
        a[RuntimeException] should be thrownBy create(new {
          @Snapshot
          def createSnapshot(context: EventContext): String = "snap!"
        })
      }

    }

    "support snapshot handlers" when {
      val ctx = new SnapshotContext {
        override def sequenceNumber(): Long = 10
        override def entityId(): String = "foo"
      }

      "single parameter" in {
        var invoked = false
        val handler = create(new {
          @SnapshotHandler
          def handleSnapshot(snapshot: String) = {
            snapshot should ===("snap!")
            invoked = true
          }
        })
        handler.handleSnapshot(event("snap!"), ctx)
        invoked shouldBe true
      }

      "context parameter" in {
        var invoked = false
        val handler = create(new {
          @SnapshotHandler
          def handleSnapshot(snapshot: String, context: SnapshotBehaviorContext) = {
            snapshot should ===("snap!")
            context.sequenceNumber() should ===(10)
            invoked = true
          }
        })
        handler.handleSnapshot(event("snap!"), ctx)
        invoked shouldBe true
      }

      "changing behavior" in {
        var invoked = false
        var invoked2 = false
        val handler = create(new {
          @SnapshotHandler
          def handleSnapshot(snapshot: String, context: SnapshotBehaviorContext) = {
            snapshot should ===("snap!")
            context.sequenceNumber() should ===(10)
            context.become(new {
              @EventHandler
              def handleEvent(event: String) = {
                event should ===("my-event")
                invoked2 = true
              }
            })
            invoked = true
          }
        })
        handler.handleSnapshot(event("snap!"), ctx)
        invoked shouldBe true
        handler.handleEvent(event("my-event"), eventCtx)
        invoked2 shouldBe true
      }

      "fail if there's a bad context" in {
        a[RuntimeException] should be thrownBy create(new {
          @SnapshotHandler
          def handleSnapshot(snapshot: String, context: EventContext) = ()
        })
      }

      "fail if there's no snapshot parameter" in {
        a[RuntimeException] should be thrownBy create(new {
          @SnapshotHandler
          def handleSnapshot(context: SnapshotContext) = ()
        })
      }

      "fail if there's no snapshot handler for the given type" in {
        val handler = create(new {
          @SnapshotHandler
          def handleSnapshot(snapshot: Int) = ()
        })
        a[RuntimeException] should be thrownBy handler.handleSnapshot(event(10), ctx)
      }

    }

  }

}

import Matchers._

@EventSourcedEntity
private class NoArgConstructorTest() {}

@EventSourcedEntity
private class EntityIdArgConstructorTest(@EntityId entityId: String) {
  entityId should ===("foo")
}

@EventSourcedEntity
private class CreationContextArgConstructorTest(ctx: EventSourcedEntityCreationContext) {
  ctx.entityId should ===("foo")
}

@EventSourcedEntity
private class MultiArgConstructorTest(ctx: EventSourcedContext, @EntityId entityId: String) {
  ctx.entityId should ===("foo")
  entityId should ===("foo")
}

@EventSourcedEntity
private class UnsupportedConstructorParameter(foo: String)


