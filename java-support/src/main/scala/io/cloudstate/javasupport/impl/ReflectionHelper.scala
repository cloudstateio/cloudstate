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

package io.cloudstate.javasupport.impl

import java.lang.annotation.Annotation
import java.lang.reflect.{AccessibleObject, Executable, Member, Method, ParameterizedType, Type, WildcardType}
import java.util.Optional

import io.cloudstate.javasupport.{
  CloudEvent,
  Context,
  EntityContext,
  EntityId,
  Metadata,
  MetadataContext,
  ServiceCallFactory
}
import com.google.protobuf.{Any => JavaPbAny}

import scala.reflect.ClassTag

/**
 * How we do reflection:
 *
 * Where possible, all reflection should be done up front, parameter handlers should be calculated, return type
 * mappers should be calculated, and everything stored in maps for fast lookup in request hot paths.
 *
 * Where this isn't possible, eg because some things may be routed based on type, and supertypes may be supported,
 * and the full type hierarchy isn't known up front, then the results of reflection should be cached.
 *
 * The general approach to reflective invocations is that each type of method (eg, command handler, event handler,
 * etc) should have an invoker defined for it. This invoker is responsible for working out how to invoke the method,
 * given a set of input parameters, and what to do with its result.
 *
 * Each invoker should store an array of parameter handlers. A parameter handler takes an input context, and converts
 * it to the thing that needs to be passed to the method. When invoking the method, this array of handlers is mapped
 * to the array of parameters, to be used in the reflective invocation. Determining the right parameter handler for
 * a given parameter type is done by partial functions, the case statements check if the parameter type is of a
 * particular type or has a particular annotation, and if it does, returns the handler for that. If nothing matches,
 * the fall back is to treat that parameter as the "main argument", this is the command message or event message that
 * is being handled by the method. If possible, validation is done on the main argument to ensure it is of the
 * expected type.
 *
 * An invoker may also need to do some processing on the return type. It should, up front, define a mapping function
 * up front that converts the type returned by the method to the type that the invoker needs to return.
 *
 * Invokers themselves are stored in a map - the key of the map depends on the type of invoker, so for example event
 * handlers are looked up by type, so the key of the map will be the type of event that the invoker handles. Command
 * handlers though are looked up by command name, so the key of the map will be the name of the command that the
 * the handler handles.
 *
 * This helper class provides shared functionality for achieving the above, including some shared parameter handlers,
 * and the common logic for command invokers. The helper methods in here are used by the various service types
 * annotation support classes.
 */
private[impl] object ReflectionHelper {

  def getAllDeclaredMethods(clazz: Class[_]): Seq[Method] =
    if (clazz.getSuperclass == null || clazz.getSuperclass == classOf[Object]) {
      clazz.getDeclaredMethods
    } else {
      clazz.getDeclaredMethods.toVector ++ getAllDeclaredMethods(clazz.getSuperclass)
    }

  def isWithinBounds(clazz: Class[_], upper: Class[_], lower: Class[_]): Boolean =
    upper.isAssignableFrom(clazz) && clazz.isAssignableFrom(lower)

  def ensureAccessible[T <: AccessibleObject](accessible: T): T = {
    if (!accessible.isAccessible) {
      accessible.setAccessible(true)
    }
    accessible
  }

  def getCapitalizedName(member: Member): String =
    // These use unicode upper/lower case definitions, rather than locale sensitive,
    // which is what we want.
    if (member.getName.charAt(0).isLower) {
      member.getName.charAt(0).toUpper + member.getName.drop(1)
    } else member.getName

  final case class InvocationContext[M, +C <: Context](mainArgument: M, context: C)
  trait ParameterHandler[M, -C <: Context] extends (InvocationContext[M, C] => AnyRef)
  case object ContextParameterHandler extends ParameterHandler[Nothing, Context] {
    override def apply(ctx: InvocationContext[Nothing, Context]): AnyRef = ctx.context.asInstanceOf[AnyRef]
  }
  final case class MainArgumentParameterHandler[M <: AnyRef, C <: Context](argumentType: Class[M])
      extends ParameterHandler[M, C] {
    override def apply(ctx: InvocationContext[M, C]): AnyRef = ctx.mainArgument
  }
  final case object EntityIdParameterHandler extends ParameterHandler[Nothing, EntityContext] {
    override def apply(ctx: InvocationContext[Nothing, EntityContext]): AnyRef = ctx.context.entityId()
  }
  final case object ServiceCallFactoryParameterHandler extends ParameterHandler[Nothing, Context] {
    override def apply(ctx: InvocationContext[Nothing, Context]): AnyRef = ctx.context.serviceCallFactory()
  }
  final case object MetadataParameterHandler extends ParameterHandler[Nothing, MetadataContext] {
    override def apply(ctx: InvocationContext[Nothing, MetadataContext]): AnyRef =
      ctx.context.metadata
  }
  final case object CloudEventParameterHandler extends ParameterHandler[Nothing, MetadataContext] {
    override def apply(ctx: InvocationContext[Nothing, MetadataContext]): AnyRef =
      ctx.context.metadata.asCloudEvent
  }
  final case object OptionalCloudEventParameterHandler extends ParameterHandler[Nothing, MetadataContext] {
    override def apply(ctx: InvocationContext[Nothing, MetadataContext]): AnyRef =
      if (ctx.context.metadata.isCloudEvent) {
        Optional.of(ctx.context.metadata.asCloudEvent)
      } else {
        Optional.empty()
      }
  }

  final case class MethodParameter(method: Executable, param: Int) {
    def parameterType: Class[_] = method.getParameterTypes()(param)
    def genericParameterType: Type = method.getGenericParameterTypes()(param)
    def annotation[A <: Annotation: ClassTag] =
      method
        .getParameterAnnotations()(param)
        .find(a => implicitly[ClassTag[A]].runtimeClass.isInstance(a))
  }

  /**
   * Determine the parameter handler for the given method.
   *
   * @param method The method (or constructor).
   * @param extras A partial function for any additional argument handlers other than the default one.
   * @tparam M The type of the main argument.
   * @tparam C The context type for this method.
   * @return An array of parameter handlers the same length as the number of parameters accepted by this method.
   */
  def getParameterHandlers[M <: AnyRef, C <: Context: ClassTag](method: Executable)(
      extras: PartialFunction[MethodParameter, ParameterHandler[M, C]] = PartialFunction.empty
  ): Array[ParameterHandler[M, C]] = {
    val handlers = Array.ofDim[ParameterHandler[_, _]](method.getParameterCount)
    val contextClass = implicitly[ClassTag[C]].runtimeClass
    val metadataContext = classOf[MetadataContext].isAssignableFrom(contextClass)
    for (i <- 0 until method.getParameterCount) {
      val parameter = MethodParameter(method, i)
      // First match things that we can be specific about
      handlers(i) =
        if (isWithinBounds(parameter.parameterType, classOf[Context], contextClass))
          ContextParameterHandler
        else if (classOf[Context].isAssignableFrom(parameter.parameterType))
          // It's a context parameter who is not within the lower bound of the contexts supported by this method
          throw new RuntimeException(
            s"Unsupported context parameter on ${method.getName}, ${parameter.parameterType} must be the same or a super type of $contextClass"
          )
        else if (parameter.parameterType == classOf[ServiceCallFactory])
          ServiceCallFactoryParameterHandler
        else if (parameter.annotation[EntityId].isDefined && classOf[EntityContext].isAssignableFrom(contextClass)) {
          if (parameter.parameterType != classOf[String]) {
            throw new RuntimeException(
              s"@EntityId annotated parameter on method ${method.getName} has type ${parameter.parameterType}, must be String."
            )
          }
          EntityIdParameterHandler
        } else if (metadataContext && parameter.parameterType == classOf[Metadata])
          MetadataParameterHandler
        else if (metadataContext && parameter.parameterType == classOf[CloudEvent])
          CloudEventParameterHandler
        else if (metadataContext && parameter.parameterType == classOf[Optional[_]] &&
                 getFirstParameter(parameter.genericParameterType) == classOf[CloudEvent])
          OptionalCloudEventParameterHandler
        else
          extras.applyOrElse(
            parameter,
            (p: MethodParameter) => MainArgumentParameterHandler(p.parameterType.asInstanceOf[Class[M]])
          )
    }
    handlers.asInstanceOf[Array[ParameterHandler[M, C]]]
  }

  def verifyAtMostOneMainArgument[M, C <: Context](name: String,
                                                   method: Method,
                                                   parameters: Array[ParameterHandler[M, C]]) =
    if (parameters.count(_.isInstanceOf[MainArgumentParameterHandler[_, _]]) > 1) {
      throw new RuntimeException(
        s"$name method $method must defined at most one non context parameter to handle commands, the parameters defined were: ${parameters
          .collect { case MainArgumentParameterHandler(clazz) => clazz.getName }
          .mkString(",")}"
      )
    }

  final class CommandHandlerInvoker[CommandContext <: Context: ClassTag](
      val method: Method,
      val serviceMethod: ResolvedServiceMethod[_, _],
      extraParameters: PartialFunction[MethodParameter, ParameterHandler[AnyRef, CommandContext]] =
        PartialFunction.empty
  ) {

    private val name = serviceMethod.descriptor.getFullName
    private val parameters = ReflectionHelper.getParameterHandlers[AnyRef, CommandContext](method)(extraParameters)

    verifyAtMostOneMainArgument("CommandHandler", method, parameters)

    parameters.foreach {
      case MainArgumentParameterHandler(inClass) if !inClass.isAssignableFrom(serviceMethod.inputType.typeClass) =>
        throw new RuntimeException(
          s"Incompatible command class $inClass for command $name, expected ${serviceMethod.inputType.typeClass}"
        )
      case _ =>
    }

    private def serialize(result: AnyRef) =
      JavaPbAny
        .newBuilder()
        .setTypeUrl(serviceMethod.outputType.typeUrl)
        .setValue(serviceMethod.outputType.asInstanceOf[ResolvedType[Any]].toByteString(result))
        .build()

    private def verifyOutputType(t: Type): Unit =
      if (!serviceMethod.outputType.typeClass.isAssignableFrom(getRawType(t))) {
        throw new RuntimeException(
          s"Incompatible return class $t for command $name, expected ${serviceMethod.outputType.typeClass}"
        )
      }

    private val handleResult: AnyRef => Optional[JavaPbAny] = if (method.getReturnType == Void.TYPE) { _ =>
      Optional.empty()
    } else if (method.getReturnType == classOf[Optional[_]]) {
      verifyOutputType(getFirstParameter(method.getGenericReturnType))

      { result =>
        val asOptional = result.asInstanceOf[Optional[AnyRef]]
        if (asOptional.isPresent) {
          Optional.of(serialize(asOptional.get()))
        } else {
          Optional.empty()
        }
      }
    } else {
      verifyOutputType(method.getReturnType)
      result => Optional.of(serialize(result))
    }

    def invoke(obj: AnyRef, command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = {
      val decodedCommand = serviceMethod.inputType.parseFrom(command.getValue).asInstanceOf[AnyRef]
      val ctx = InvocationContext(decodedCommand, context)
      val result = method.invoke(obj, parameters.map(_.apply(ctx)): _*)
      handleResult(result)
    }
  }

  def getRawType(t: Type): Class[_] = t match {
    case clazz: Class[_] => clazz
    case pt: ParameterizedType => getRawType(pt.getRawType)
    case wct: WildcardType => getRawType(wct.getUpperBounds.headOption.getOrElse(classOf[Object]))
    case _ => classOf[Object]
  }

  /**
   * Get the type of the first parameter of this parameterized type.
   *
   * If it's not a parameterized type, AnyRef is returned.
   */
  def getGenericFirstParameter(t: Type): Type =
    t match {
      case pt: ParameterizedType =>
        pt.getActualTypeArguments()(0)
      case _ =>
        classOf[AnyRef]
    }

  /**
   * Get the class of the first parameter of this parameterized type.
   *
   * If it's not a parameterized type, AnyRef is returned.
   *
   * This is useful if, for example, you have a type who's raw type equals say java.util.Optional,
   * and you want to find out what it's an optional of.
   */
  def getFirstParameter(t: Type): Class[_] = getRawType(getGenericFirstParameter(t))

  /**
   * Verifies that none of the given methods have CloudState annotations that are not allowed.
   *
   * This is designed to eagerly catch mistakes such as importing the wrong CommandHandler annotation.
   */
  def validateNoBadMethods(methods: Seq[Method],
                           entity: Class[_ <: Annotation],
                           allowed: Set[Class[_ <: Annotation]]): Unit =
    methods.foreach { method =>
      method.getAnnotations.foreach { annotation =>
        if (annotation.annotationType().getAnnotation(classOf[CloudStateAnnotation]) != null && !allowed(
              annotation.annotationType()
            )) {
          val maybeAlternative = allowed.find(_.getSimpleName == annotation.annotationType().getSimpleName)
          throw new RuntimeException(
            s"Annotation @${annotation.annotationType().getName} on method ${method.getDeclaringClass.getName}." +
            s"${method.getName} not allowed in @${entity.getName} annotated service." +
            maybeAlternative.fold("")(alterative => s" Did you mean to use @${alterative.getName}?")
          )
        }
      }
    }
}
