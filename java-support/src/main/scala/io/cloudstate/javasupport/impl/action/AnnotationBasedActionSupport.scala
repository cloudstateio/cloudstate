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

package io.cloudstate.javasupport.impl.action

import java.lang.reflect.{InvocationTargetException, Method, Type}
import java.util.concurrent.{CompletableFuture, CompletionStage}

import akka.NotUsed
import akka.stream.{javadsl, Materializer}
import akka.stream.javadsl.{AsPublisher, Source}
import akka.stream.scaladsl.{JavaFlowSupport, Sink}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.action._
import io.cloudstate.javasupport.impl.ReflectionHelper.{InvocationContext, ParameterHandler}
import io.cloudstate.javasupport.impl.{
  AnySupport,
  ReflectionHelper,
  ResolvedEntityFactory,
  ResolvedServiceMethod,
  ResolvedType
}
import io.cloudstate.javasupport.Metadata

/**
 * Annotation based implementation of the [[ActionHandler]].
 */
private[impl] class AnnotationBasedActionSupport(
    action: AnyRef,
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]]
)(implicit mat: Materializer)
    extends ActionHandler
    with ResolvedEntityFactory {

  def this(action: AnyRef, anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor)(
      implicit mat: Materializer
  ) =
    this(action, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = ActionReflection(action.getClass, resolvedMethods)

  override def handleUnary(commandName: String,
                           message: MessageEnvelope[JavaPbAny],
                           context: ActionContext): CompletionStage[ActionReply[JavaPbAny]] = unwrap {
    behavior.unaryHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(action, message, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${action.getClass.getName}"
        )
    }
  }

  override def handleStreamedOut(commandName: String,
                                 message: MessageEnvelope[JavaPbAny],
                                 context: ActionContext): Source[ActionReply[JavaPbAny], NotUsed] = unwrap {
    behavior.serverStreamedHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(action, message, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${action.getClass.getName}"
        )
    }
  }

  override def handleStreamedIn(commandName: String,
                                stream: Source[MessageEnvelope[JavaPbAny], NotUsed],
                                context: ActionContext): CompletionStage[ActionReply[JavaPbAny]] =
    behavior.clientStreamedHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(action, stream, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${action.getClass.getName}"
        )
    }

  override def handleStreamed(commandName: String,
                              stream: Source[MessageEnvelope[JavaPbAny], NotUsed],
                              context: ActionContext): Source[ActionReply[JavaPbAny], NotUsed] =
    behavior.streamedHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(action, stream, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${action.getClass.getName}"
        )
    }

  private def unwrap[T](block: => T): T =
    try {
      block
    } catch {
      case ite: InvocationTargetException if ite.getCause != null =>
        throw ite.getCause
    }
}

private class ActionReflection(
    val unaryHandlers: Map[String, UnaryCallInvoker],
    val serverStreamedHandlers: Map[String, ServerStreamedCallInvoker],
    val clientStreamedHandlers: Map[String, ClientStreamedCallInvoker],
    val streamedHandlers: Map[String, StreamedCallInvoker]
)

private object ActionReflection {
  def apply(behaviorClass: Class[_], serviceMethods: Map[String, ResolvedServiceMethod[_, _]])(
      implicit mat: Materializer
  ): ActionReflection = {

    val allMethods = ReflectionHelper.getAllDeclaredMethods(behaviorClass)

    // First, find all the call handler methods, and match them with corresponding service methods
    val allCallHandlers = allMethods
      .filter(_.getAnnotation(classOf[CallHandler]) != null)
      .map { method =>
        method.setAccessible(true)
        val annotation = method.getAnnotation(classOf[CallHandler])
        val name: String = if (annotation.name().isEmpty) {
          ReflectionHelper.getCapitalizedName(method)
        } else annotation.name()

        val serviceMethod = serviceMethods.getOrElse(name, {
          throw new RuntimeException(
            s"Command handler method ${method.getName} for command $name found, but the service has no command by that name."
          )
        })

        (method, serviceMethod)
      }
      .groupBy(_._2.name)
      .map {
        case (commandName, Seq((method, serviceMethod))) => (commandName, method, serviceMethod)
        case (commandName, many) =>
          throw new RuntimeException(
            s"Multiple methods found for handling command of name $commandName: ${many.map(_._1.getName).mkString(", ")}"
          )
      }

    val unaryCallHandlers = allCallHandlers.collect {
      case (commandName, method, serviceMethod)
          if !serviceMethod.descriptor.isClientStreaming && !serviceMethod.descriptor.isServerStreaming =>
        commandName -> new UnaryCallInvoker(method, serviceMethod)
    }.toMap

    val serverStreamedCallHandlers = allCallHandlers.collect {
      case (commandName, method, serviceMethod)
          if !serviceMethod.descriptor.isClientStreaming && serviceMethod.descriptor.isServerStreaming =>
        commandName -> new ServerStreamedCallInvoker(method, serviceMethod)
    }.toMap

    val clientStreamedCallHandlers = allCallHandlers.collect {
      case (commandName, method, serviceMethod)
          if serviceMethod.descriptor.isClientStreaming && !serviceMethod.descriptor.isServerStreaming =>
        commandName -> new ClientStreamedCallInvoker(method, serviceMethod, mat)
    }.toMap

    val streamedCallHandlers = allCallHandlers.collect {
      case (commandName, method, serviceMethod)
          if serviceMethod.descriptor.isClientStreaming && serviceMethod.descriptor.isServerStreaming =>
        commandName -> new StreamedCallInvoker(method, serviceMethod, mat)
    }.toMap

    ReflectionHelper.validateNoBadMethods(
      allMethods,
      classOf[Action],
      Set(classOf[CallHandler])
    )

    new ActionReflection(unaryCallHandlers,
                         serverStreamedCallHandlers,
                         clientStreamedCallHandlers,
                         streamedCallHandlers)
  }

  def getOutputParameterMapper[T](method: String,
                                  resolvedType: ResolvedType[T],
                                  returnType: Type): Any => ActionReply[JavaPbAny] = {
    val (payloadClass, mapper) = ReflectionHelper.getRawType(returnType) match {
      case envelope if envelope == classOf[MessageEnvelope[_]] =>
        val payload = ReflectionHelper.getFirstParameter(returnType)
        (payload, { any: Any =>
          val envelope = any.asInstanceOf[MessageEnvelope[T]]
          ActionReply.message(JavaPbAny
                                .newBuilder()
                                .setValue(resolvedType.toByteString(envelope.payload))
                                .setTypeUrl(resolvedType.typeUrl)
                                .build(),
                              envelope.metadata)
        })
      case message if message == classOf[ActionReply[_]] =>
        val payload = ReflectionHelper.getFirstParameter(returnType)
        (payload, { any: Any =>
          val message = any.asInstanceOf[ActionReply[T]]
          message match {
            case envelope: MessageReply[T] =>
              ActionReply.message(JavaPbAny
                                    .newBuilder()
                                    .setValue(resolvedType.toByteString(envelope.payload))
                                    .setTypeUrl(resolvedType.typeUrl)
                                    .build(),
                                  envelope.metadata)
            case other => other.asInstanceOf[ActionReply[JavaPbAny]]
          }
        })
      case payload =>
        (payload, { any: Any =>
          ActionReply.message(
            JavaPbAny
              .newBuilder()
              .setValue(resolvedType.toByteString(any.asInstanceOf[T]))
              .setTypeUrl(resolvedType.typeUrl)
              .build()
          )
        })
    }

    if (payloadClass != resolvedType.typeClass) {
      throw new RuntimeException(
        s"Incompatible return type $payloadClass for call $method, expected ${resolvedType.typeClass}"
      )
    }
    mapper
  }

  def getInputParameterMapper(method: String,
                              resolvedType: ResolvedType[_],
                              parameterType: Type): MessageEnvelope[JavaPbAny] => AnyRef =
    ReflectionHelper.getRawType(parameterType) match {
      case envelope if envelope == classOf[MessageEnvelope[_]] =>
        val messageType = ReflectionHelper.getFirstParameter(parameterType)
        if (messageType != resolvedType.typeClass) {
          throw new RuntimeException(
            s"Incompatible message class $messageType for call $method, expected ${resolvedType.typeClass}"
          )
        } else { envelope =>
          MessageEnvelope.of(
            resolvedType.parseFrom(envelope.payload.getValue).asInstanceOf[AnyRef],
            envelope.metadata
          )
        }
      case payload =>
        if (payload != resolvedType.typeClass) {
          throw new RuntimeException(
            s"Incompatible message class $payload for call $method, expected ${resolvedType.typeClass}"
          )
        } else { envelope =>
          resolvedType.parseFrom(envelope.payload.getValue).asInstanceOf[AnyRef]
        }
    }
}

private class PayloadParameterHandler(mapper: MessageEnvelope[JavaPbAny] => AnyRef)
    extends ParameterHandler[MessageEnvelope[JavaPbAny], ActionContext] {
  override def apply(ctx: InvocationContext[MessageEnvelope[JavaPbAny], ActionContext]): AnyRef =
    mapper(ctx.mainArgument)
}

private class StreamedPayloadParameterHandler(mapper: javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed] => AnyRef)
    extends ParameterHandler[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ActionContext] {
  override def apply(
      ctx: InvocationContext[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ActionContext]
  ): AnyRef =
    mapper(ctx.mainArgument)
}

private trait UnaryInSupport {
  protected val method: Method
  protected val serviceMethod: ResolvedServiceMethod[_, _]

  protected val parameters: Array[ParameterHandler[MessageEnvelope[JavaPbAny], ActionContext]] =
    ReflectionHelper.getParameterHandlers[MessageEnvelope[JavaPbAny], ActionContext](method) {
      case payload =>
        new PayloadParameterHandler(
          ActionReflection
            .getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, payload.genericParameterType)
        )
    }
}

private trait UnaryOutSupport {
  protected val method: Method
  protected val serviceMethod: ResolvedServiceMethod[_, _]

  protected val outputMapper: Any => CompletionStage[ActionReply[JavaPbAny]] = method.getReturnType match {
    case cstage if cstage == classOf[CompletionStage[_]] =>
      val cstageType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper =
        ActionReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, cstageType)

      any: Any => any.asInstanceOf[CompletionStage[Any]].thenApply(mapper.apply)
    case _ =>
      val mapper = ActionReflection.getOutputParameterMapper(serviceMethod.name,
                                                             serviceMethod.outputType,
                                                             method.getGenericReturnType)

      any: Any => CompletableFuture.completedFuture(mapper(any))
  }
}

private trait StreamedInSupport {
  protected val method: Method
  protected val serviceMethod: ResolvedServiceMethod[_, _]
  implicit protected val materializer: Materializer

  protected val parameters
      : Array[ParameterHandler[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ActionContext]] =
    ReflectionHelper.getParameterHandlers[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ActionContext](
      method
    ) {
      case source if source.parameterType == classOf[javadsl.Source[_, _]] =>
        val sourceType = ReflectionHelper.getGenericFirstParameter(source.genericParameterType)
        val mapper =
          ActionReflection.getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, sourceType)

        new StreamedPayloadParameterHandler(source => source.map(mapper.apply))

      case rsPublisher if rsPublisher.parameterType == classOf[org.reactivestreams.Publisher[_]] =>
        val publisherType = ReflectionHelper.getGenericFirstParameter(rsPublisher.genericParameterType)
        val mapper =
          ActionReflection.getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, publisherType)

        new StreamedPayloadParameterHandler(
          source =>
            source.asScala
              .map(mapper.apply)
              .runWith(Sink.asPublisher(false))
        )

      case jdkPublisher if jdkPublisher.parameterType == classOf[java.util.concurrent.Flow.Publisher[_]] =>
        val publisherType = ReflectionHelper.getGenericFirstParameter(jdkPublisher.genericParameterType)
        val mapper =
          ActionReflection.getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, publisherType)

        new StreamedPayloadParameterHandler(
          source =>
            source.asScala
              .map(mapper.apply)
              .runWith(JavaFlowSupport.Sink.asPublisher(false))
        )

      case other =>
        throw new RuntimeException(
          s"Unknown input parameter of type $other. Streamed call ${serviceMethod.name} must accept a ${classOf[
            javadsl.Source[_, _]
          ]} or ${classOf[org.reactivestreams.Publisher[_]]}."
        )
    }

  if (parameters.count(_.isInstanceOf[StreamedPayloadParameterHandler]) != 1) {
    throw new RuntimeException(
      s"Streamed call ${serviceMethod.name} must accept exactly one parameter of type ${classOf[javadsl.Source[_, _]]} or ${classOf[org.reactivestreams.Publisher[_]]}"
    )
  }
}

private trait StreamedOutSupport {
  protected val method: Method
  protected val serviceMethod: ResolvedServiceMethod[_, _]

  protected val outputMapper: Any => javadsl.Source[ActionReply[JavaPbAny], NotUsed] = method.getReturnType match {
    case source if source == classOf[javadsl.Source[_, _]] =>
      val sourceType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper: Any => ActionReply[JavaPbAny] =
        ActionReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, sourceType)

      any: Any =>
        any
          .asInstanceOf[javadsl.Source[Any, _]]
          .map(mapper.apply)
          .mapMaterializedValue(_ => NotUsed)

    case rsPublisher if rsPublisher == classOf[org.reactivestreams.Publisher[_]] =>
      val sourceType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper: Any => ActionReply[JavaPbAny] =
        ActionReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, sourceType)

      any: Any => {
        javadsl.Source
          .fromPublisher(any.asInstanceOf[org.reactivestreams.Publisher[Any]])
          .map(mapper.apply)
      }

    case jdkPublisher if jdkPublisher == classOf[java.util.concurrent.Flow.Publisher[_]] =>
      val sourceType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper: Any => ActionReply[JavaPbAny] =
        ActionReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, sourceType)

      any: Any => {
        JavaFlowSupport.Source
          .fromPublisher(any.asInstanceOf[java.util.concurrent.Flow.Publisher[Any]])
          .map(mapper.apply)
          .asJava
      }

    case _ =>
      throw new RuntimeException(
        s"Streamed call ${serviceMethod.name} must return a ${classOf[javadsl.Source[_, _]]} or ${classOf[org.reactivestreams.Publisher[_]]}."
      )
  }
}

private class UnaryCallInvoker(protected val method: Method, protected val serviceMethod: ResolvedServiceMethod[_, _])
    extends UnaryInSupport
    with UnaryOutSupport {

  def invoke(action: AnyRef,
             message: MessageEnvelope[JavaPbAny],
             context: ActionContext): CompletionStage[ActionReply[JavaPbAny]] = {
    val ctx = InvocationContext(message, context)
    val result = method.invoke(action, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}

private class ServerStreamedCallInvoker(protected val method: Method,
                                        protected val serviceMethod: ResolvedServiceMethod[_, _])
    extends UnaryInSupport
    with StreamedOutSupport {

  def invoke(action: AnyRef,
             message: MessageEnvelope[JavaPbAny],
             context: ActionContext): javadsl.Source[ActionReply[JavaPbAny], NotUsed] = {
    val ctx = InvocationContext(message, context)
    val result = method.invoke(action, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}

private class ClientStreamedCallInvoker(protected val method: Method,
                                        protected val serviceMethod: ResolvedServiceMethod[_, _],
                                        protected val materializer: Materializer)
    extends UnaryOutSupport
    with StreamedInSupport {

  def invoke(action: AnyRef,
             stream: javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed],
             context: ActionContext): CompletionStage[ActionReply[JavaPbAny]] = {
    val ctx = InvocationContext(stream, context)
    val result = method.invoke(action, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}

private class StreamedCallInvoker(protected val method: Method,
                                  protected val serviceMethod: ResolvedServiceMethod[_, _],
                                  protected val materializer: Materializer)
    extends StreamedOutSupport
    with StreamedInSupport {

  def invoke(action: AnyRef,
             stream: javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed],
             context: ActionContext): javadsl.Source[ActionReply[JavaPbAny], NotUsed] = {
    val ctx = InvocationContext(stream, context)
    val result = method.invoke(action, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}
