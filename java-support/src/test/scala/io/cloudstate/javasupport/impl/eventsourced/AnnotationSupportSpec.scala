package io.cloudstate.javasupport.impl.eventsourced

import io.cloudstate.javasupport.EntityId
import io.cloudstate.javasupport.eventsourced._
import io.cloudstate.javasupport.impl.ResolvedServiceMethod
import org.scalatest.{Matchers, WordSpec}

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

  "Event sourced annotation support" should {
    "support entity construction" when {

      "there is a noarg constructor" in {
        new AnnotationSupport(classOf[NoArgConstructorTest], Nil).create(MockContext)
      }

      "there is a constructor with an EntityId annotated parameter" in {
        new AnnotationSupport(classOf[EntityIdArgConstructorTest], Nil).create(MockContext)
      }

      "there is a constructor with a EventSourcedEntityCreationContext parameter" in {
        new AnnotationSupport(classOf[CreationContextArgConstructorTest], Nil).create(MockContext)
      }

      "there is a constructor with multiple parameters" in {
        new AnnotationSupport(classOf[MultiArgConstructorTest], Nil).create(MockContext)
      }

      "fail if the constructor contains an unsupported parameter" in {
        a[RuntimeException] should be thrownBy new AnnotationSupport(classOf[UnsupportedConstructorParameter], Nil)
      }

    }

    "support event handlers" when {
      val eventCtx = new EventContext {
        override def sequenceNumber(): Long = 10
        override def entityId(): String = "foo"
      }

      def create(behavior: AnyRef) = {
        new AnnotationSupport(behavior.getClass, Nil, Some(_ => behavior)).create(MockContext)
      }

      "no arg event handler" in {
        var invoked = false
        val handler = create(new {
          @EventHandler(eventClass = classOf[String])
          def handle() = invoked = true
        })
        handler.handleEvent("my-event", eventCtx)
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
        handler.handleEvent("my-event", eventCtx)
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
        handler.handleEvent("my-event", eventCtx)
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
        handler.handleEvent("my-event", eventCtx)
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
        handler.handleEvent("my-event", eventCtx)
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
        handler.handleEvent("event-one", eventCtx)
        invoked1 shouldBe true
        handler.handleEvent("event-two", eventCtx)
        invoked1 shouldBe true
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

    }

    "support command handlers" when {

      case class Wrapped(value: String)
      val method = ResolvedServiceMethod("Wrap", classOf[String], classOf[Wrapped])

      def create(behavior: AnyRef, methods: ResolvedServiceMethod*) = {
        new AnnotationSupport(behavior.getClass, methods, Some(_ => behavior)).create(MockContext)
      }

      "no arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def wrap() = Wrapped("blah")
        }, method)
        handler.handleCommand("nothing", new MockCommandContext) should ===(Wrapped("blah"))
      }

      "single arg command handler" in {
        val handler = create(new {
          @CommandHandler
          def wrap(msg: String) = Wrapped(msg)
        }, method)
        handler.handleCommand("blah", new MockCommandContext) should ===(Wrapped("blah"))
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
        handler.handleCommand("blah", new MockCommandContext) should ===(Wrapped("blah"))
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
        handler.handleCommand("blah", ctx) should ===(Wrapped("blah"))
        ctx.emited should ===(Seq("blah event"))
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


