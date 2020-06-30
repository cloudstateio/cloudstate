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

package io.cloudstate.javasupport.impl.controller

import java.lang.reflect.{InvocationTargetException, Method, Type}
import java.util.concurrent.{CompletableFuture, CompletionStage}

import akka.NotUsed
import akka.stream.{javadsl, Materializer}
import akka.stream.javadsl.{AsPublisher, Source}
import akka.stream.scaladsl.{JavaFlowSupport, Sink}
import com.google.protobuf.{Descriptors, Any => JavaPbAny}
import io.cloudstate.javasupport.controller._
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
 * Annotation based implementation of the [[ControllerHandler]].
 */
private[impl] class AnnotationBasedControllerSupport(
    controller: AnyRef,
    anySupport: AnySupport,
    override val resolvedMethods: Map[String, ResolvedServiceMethod[_, _]]
)(implicit mat: Materializer)
    extends ControllerHandler
    with ResolvedEntityFactory {

  def this(controller: AnyRef, anySupport: AnySupport, serviceDescriptor: Descriptors.ServiceDescriptor)(
      implicit mat: Materializer
  ) =
    this(controller, anySupport, anySupport.resolveServiceDescriptor(serviceDescriptor))

  private val behavior = ControllerReflection(controller.getClass, resolvedMethods)

  override def handleUnary(commandName: String,
                           message: MessageEnvelope[JavaPbAny],
                           context: ControllerContext): CompletionStage[ControllerReply[JavaPbAny]] = unwrap {
    behavior.unaryHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(controller, message, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${controller.getClass.getName}"
        )
    }
  }

  override def handleStreamedOut(commandName: String,
                                 message: MessageEnvelope[JavaPbAny],
                                 context: ControllerContext): Source[ControllerReply[JavaPbAny], NotUsed] = unwrap {
    behavior.serverStreamedHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(controller, message, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${controller.getClass.getName}"
        )
    }
  }

  override def handleStreamedIn(commandName: String,
                                stream: Source[MessageEnvelope[JavaPbAny], NotUsed],
                                context: ControllerContext): CompletionStage[ControllerReply[JavaPbAny]] =
    behavior.clientStreamedHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(controller, stream, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${controller.getClass.getName}"
        )
    }

  override def handleStreamed(commandName: String,
                              stream: Source[MessageEnvelope[JavaPbAny], NotUsed],
                              context: ControllerContext): Source[ControllerReply[JavaPbAny], NotUsed] =
    behavior.streamedHandlers.get(commandName) match {
      case Some(handler) =>
        handler.invoke(controller, stream, context)
      case None =>
        throw new RuntimeException(
          s"No call handler found for call $commandName on ${controller.getClass.getName}"
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

private class ControllerReflection(
    val unaryHandlers: Map[String, UnaryCallInvoker],
    val serverStreamedHandlers: Map[String, ServerStreamedCallInvoker],
    val clientStreamedHandlers: Map[String, ClientStreamedCallInvoker],
    val streamedHandlers: Map[String, StreamedCallInvoker]
)

private object ControllerReflection {
  def apply(behaviorClass: Class[_], serviceMethods: Map[String, ResolvedServiceMethod[_, _]])(
      implicit mat: Materializer
  ): ControllerReflection = {

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
      classOf[Controller],
      Set(classOf[CallHandler])
    )

    new ControllerReflection(unaryCallHandlers,
                             serverStreamedCallHandlers,
                             clientStreamedCallHandlers,
                             streamedCallHandlers)
  }

  def getOutputParameterMapper[T](method: String,
                                  resolvedType: ResolvedType[T],
                                  returnType: Type): Any => ControllerReply[JavaPbAny] = {
    val (payloadClass, mapper) = ReflectionHelper.getRawType(returnType) match {
      case envelope if envelope == classOf[MessageEnvelope[_]] =>
        val payload = ReflectionHelper.getFirstParameter(returnType)
        (payload, { any: Any =>
          val envelope = any.asInstanceOf[MessageEnvelope[T]]
          ControllerReply.message(JavaPbAny
                                    .newBuilder()
                                    .setValue(resolvedType.toByteString(envelope.payload))
                                    .setTypeUrl(resolvedType.typeUrl)
                                    .build(),
                                  envelope.metadata)
        })
      case message if message == classOf[ControllerReply[_]] =>
        val payload = ReflectionHelper.getFirstParameter(returnType)
        (payload, { any: Any =>
          val message = any.asInstanceOf[ControllerReply[T]]
          message match {
            case envelope: MessageReply[T] =>
              ControllerReply.message(JavaPbAny
                                        .newBuilder()
                                        .setValue(resolvedType.toByteString(envelope.payload))
                                        .setTypeUrl(resolvedType.typeUrl)
                                        .build(),
                                      envelope.metadata)
            case other => other.asInstanceOf[ControllerReply[JavaPbAny]]
          }
        })
      case payload =>
        (payload, { any: Any =>
          ControllerReply.message(
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
    extends ParameterHandler[MessageEnvelope[JavaPbAny], ControllerContext] {
  override def apply(ctx: InvocationContext[MessageEnvelope[JavaPbAny], ControllerContext]): AnyRef =
    mapper(ctx.mainArgument)
}

private class StreamedPayloadParameterHandler(mapper: javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed] => AnyRef)
    extends ParameterHandler[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ControllerContext] {
  override def apply(
      ctx: InvocationContext[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ControllerContext]
  ): AnyRef =
    mapper(ctx.mainArgument)
}

private trait UnaryInSupport {
  protected val method: Method
  protected val serviceMethod: ResolvedServiceMethod[_, _]

  protected val parameters: Array[ParameterHandler[MessageEnvelope[JavaPbAny], ControllerContext]] =
    ReflectionHelper.getParameterHandlers[MessageEnvelope[JavaPbAny], ControllerContext](method) {
      case payload =>
        new PayloadParameterHandler(
          ControllerReflection
            .getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, payload.genericParameterType)
        )
    }
}

private trait UnaryOutSupport {
  protected val method: Method
  protected val serviceMethod: ResolvedServiceMethod[_, _]

  protected val outputMapper: Any => CompletionStage[ControllerReply[JavaPbAny]] = method.getReturnType match {
    case cstage if cstage == classOf[CompletionStage[_]] =>
      val cstageType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper =
        ControllerReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, cstageType)

      any: Any => any.asInstanceOf[CompletionStage[Any]].thenApply(mapper.apply)
    case _ =>
      val mapper = ControllerReflection.getOutputParameterMapper(serviceMethod.name,
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
      : Array[ParameterHandler[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ControllerContext]] =
    ReflectionHelper.getParameterHandlers[javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed], ControllerContext](
      method
    ) {
      case source if source.parameterType == classOf[javadsl.Source[_, _]] =>
        val sourceType = ReflectionHelper.getGenericFirstParameter(source.genericParameterType)
        val mapper =
          ControllerReflection.getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, sourceType)

        new StreamedPayloadParameterHandler(source => source.map(mapper.apply))

      case rsPublisher if rsPublisher.parameterType == classOf[org.reactivestreams.Publisher[_]] =>
        val publisherType = ReflectionHelper.getGenericFirstParameter(rsPublisher.genericParameterType)
        val mapper =
          ControllerReflection.getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, publisherType)

        new StreamedPayloadParameterHandler(
          source =>
            source.asScala
              .map(mapper.apply)
              .runWith(Sink.asPublisher(false))
        )

      case jdkPublisher if jdkPublisher.parameterType == classOf[java.util.concurrent.Flow.Publisher[_]] =>
        val publisherType = ReflectionHelper.getGenericFirstParameter(jdkPublisher.genericParameterType)
        val mapper =
          ControllerReflection.getInputParameterMapper(serviceMethod.name, serviceMethod.inputType, publisherType)

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

  protected val outputMapper: Any => javadsl.Source[ControllerReply[JavaPbAny], NotUsed] = method.getReturnType match {
    case source if source == classOf[javadsl.Source[_, _]] =>
      val sourceType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper: Any => ControllerReply[JavaPbAny] =
        ControllerReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, sourceType)

      any: Any =>
        any
          .asInstanceOf[javadsl.Source[Any, _]]
          .map(mapper.apply)
          .mapMaterializedValue(_ => NotUsed)

    case rsPublisher if rsPublisher == classOf[org.reactivestreams.Publisher[_]] =>
      val sourceType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper: Any => ControllerReply[JavaPbAny] =
        ControllerReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, sourceType)

      any: Any => {
        javadsl.Source
          .fromPublisher(any.asInstanceOf[org.reactivestreams.Publisher[Any]])
          .map(mapper.apply)
      }

    case jdkPublisher if jdkPublisher == classOf[java.util.concurrent.Flow.Publisher[_]] =>
      val sourceType = ReflectionHelper.getGenericFirstParameter(method.getGenericReturnType)
      val mapper: Any => ControllerReply[JavaPbAny] =
        ControllerReflection.getOutputParameterMapper(serviceMethod.name, serviceMethod.outputType, sourceType)

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

  def invoke(controller: AnyRef,
             message: MessageEnvelope[JavaPbAny],
             context: ControllerContext): CompletionStage[ControllerReply[JavaPbAny]] = {
    val ctx = InvocationContext(message, context)
    val result = method.invoke(controller, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}

private class ServerStreamedCallInvoker(protected val method: Method,
                                        protected val serviceMethod: ResolvedServiceMethod[_, _])
    extends UnaryInSupport
    with StreamedOutSupport {

  def invoke(controller: AnyRef,
             message: MessageEnvelope[JavaPbAny],
             context: ControllerContext): javadsl.Source[ControllerReply[JavaPbAny], NotUsed] = {
    val ctx = InvocationContext(message, context)
    val result = method.invoke(controller, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}

private class ClientStreamedCallInvoker(protected val method: Method,
                                        protected val serviceMethod: ResolvedServiceMethod[_, _],
                                        protected val materializer: Materializer)
    extends UnaryOutSupport
    with StreamedInSupport {

  def invoke(controller: AnyRef,
             stream: javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed],
             context: ControllerContext): CompletionStage[ControllerReply[JavaPbAny]] = {
    val ctx = InvocationContext(stream, context)
    val result = method.invoke(controller, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}

private class StreamedCallInvoker(protected val method: Method,
                                  protected val serviceMethod: ResolvedServiceMethod[_, _],
                                  protected val materializer: Materializer)
    extends StreamedOutSupport
    with StreamedInSupport {

  def invoke(controller: AnyRef,
             stream: javadsl.Source[MessageEnvelope[JavaPbAny], NotUsed],
             context: ControllerContext): javadsl.Source[ControllerReply[JavaPbAny], NotUsed] = {
    val ctx = InvocationContext(stream, context)
    val result = method.invoke(controller, parameters.map(_.apply(ctx)): _*)
    outputMapper(result)
  }

}
