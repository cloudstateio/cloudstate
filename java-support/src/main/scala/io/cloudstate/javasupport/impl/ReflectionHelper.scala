package io.cloudstate.javasupport.impl

import java.lang.annotation.Annotation
import java.lang.reflect.{AccessibleObject, Executable, Member, Method, ParameterizedType, Type, WildcardType}
import java.util.Optional

import io.cloudstate.javasupport.Context
import io.cloudstate.javasupport.{EntityContext, EntityId}
import com.google.protobuf.{Any => JavaPbAny}

import scala.reflect.ClassTag

private[impl] object ReflectionHelper {

  def getAllDeclaredMethods(clazz: Class[_]): Seq[Method] = {
    if (clazz.getSuperclass == null || clazz.getSuperclass == classOf[Object]) {
      clazz.getDeclaredMethods
    } else {
      clazz.getDeclaredMethods.toVector ++ getAllDeclaredMethods(clazz.getSuperclass)
    }
  }

  def isWithinBounds(clazz: Class[_], upper: Class[_], lower: Class[_]): Boolean = {
    upper.isAssignableFrom(clazz) && clazz.isAssignableFrom(lower)
  }

  def ensureAccessible[T <: AccessibleObject](accessible: T): T = {
    if (!accessible.isAccessible) {
      accessible.setAccessible(true)
    }
    accessible
  }

  def getCapitalizedName(member: Member): String = {
    // These use unicode upper/lower case definitions, rather than locale sensitive,
    // which is what we want.
    if (member.getName.charAt(0).isLower) {
      member.getName.charAt(0).toUpper + member.getName.drop(1)
    } else member.getName
  }

  final case class InvocationContext[C <: Context](mainArgument: AnyRef, context: C)
  trait ParameterHandler[C <: Context] extends (InvocationContext[C] => AnyRef)
  case object ContextParameterHandler extends ParameterHandler[Context] {
    override def apply(ctx: InvocationContext[Context]): AnyRef = ctx.context.asInstanceOf[AnyRef]
  }
  final case class MainArgumentParameterHandler[C <: Context](argumentType: Class[_]) extends ParameterHandler[C] {
    override def apply(ctx: InvocationContext[C]): AnyRef = ctx.mainArgument
  }
  final case object EntityIdParameterHandler extends ParameterHandler[EntityContext] {
    override def apply(ctx: InvocationContext[EntityContext]): AnyRef = ctx.context.entityId()
  }

  final case class MethodParameter(method: Executable, param: Int) {
    def parameterType: Class[_] = method.getParameterTypes()(param)
    def annotation[A <: Annotation: ClassTag] = method.getParameterAnnotations()(param)
      .find(a => implicitly[ClassTag[A]].runtimeClass.isInstance(a))
  }

  def getParameterHandlers[C <: Context: ClassTag](method: Executable)(extras: PartialFunction[MethodParameter, ParameterHandler[C]] = PartialFunction.empty): Array[ParameterHandler[C]] = {
    val handlers = Array.ofDim[ParameterHandler[_]](method.getParameterCount)
    for (i <- 0 until method.getParameterCount) {
      val parameter = MethodParameter(method, i)
      // First match things that we can be specific about
      val contextClass = implicitly[ClassTag[C]].runtimeClass
      handlers(i) = if (isWithinBounds(parameter.parameterType, classOf[Context], contextClass))
        ContextParameterHandler
      else if (classOf[Context].isAssignableFrom(parameter.parameterType))
        // It's a context parameter who is not within the lower bound of the contexts supported by this method
        throw new RuntimeException(s"Unsupported context parameter on ${method.getName}, ${parameter.parameterType} must be the same or a super type of $contextClass")
      else if (parameter.annotation[EntityId].isDefined) {
        if (parameter.parameterType != classOf[String]) {
          throw new RuntimeException(s"@EntityId annotated parameter on method ${method.getName} has type ${parameter.parameterType}, must be String.")
        }
        EntityIdParameterHandler
      } else
        extras.applyOrElse(parameter, (p: MethodParameter) => MainArgumentParameterHandler(p.parameterType))
    }
    handlers.asInstanceOf[Array[ParameterHandler[C]]]
  }

  final class CommandHandlerInvoker[CommandContext <: Context : ClassTag](val method: Method, val serviceMethod: ResolvedServiceMethod[_, _]) {
    private val name = serviceMethod.descriptor.getFullName
    private val parameters = ReflectionHelper.getParameterHandlers[CommandContext](method)()

    if (parameters.count(_.isInstanceOf[MainArgumentParameterHandler[_]]) > 1) {
      throw new RuntimeException(s"CommandHandler method $method must defined at most one non context parameter to handle commands, the parameters defined were: ${parameters.collect { case MainArgumentParameterHandler(clazz) => clazz.getName }.mkString(",")}")
    }
    parameters.foreach {
      case MainArgumentParameterHandler(inClass) if !inClass.isAssignableFrom(serviceMethod.inputType.typeClass) =>
        throw new RuntimeException(s"Incompatible command class $inClass for command $name, expected ${serviceMethod.inputType.typeClass}")
      case _ =>
    }

    private def serialize(result: AnyRef) = {
      JavaPbAny.newBuilder().setTypeUrl(serviceMethod.outputType.typeUrl)
        .setValue(serviceMethod.outputType.asInstanceOf[ResolvedType[Any]].toByteString(result))
        .build()
    }

    private def getRawType(t: Type): Class[_] = t match {
      case clazz: Class[_] => clazz
      case pt: ParameterizedType => getRawType(pt.getRawType)
      case wct: WildcardType => getRawType(wct.getUpperBounds.headOption.getOrElse(classOf[Object]))
      case _ => throw new RuntimeException(s"Cannot resolve return type ${method.getGenericReturnType} for command $name")
    }

    private def verifyOutputType(t: Type): Unit = {
      if (!serviceMethod.outputType.typeClass.isAssignableFrom(getRawType(t))) {
        throw new RuntimeException(s"Incompatible return class $t for command $name, expected ${serviceMethod.outputType.typeClass}")
      }
    }

    private val handleResult: AnyRef => Optional[JavaPbAny] = if (method.getReturnType == classOf[Void]) {
      _ => Optional.empty()
    } else if (method.getReturnType == classOf[Optional[_]]) {
      method.getGenericReturnType match {
        case pt: ParameterizedType =>
          verifyOutputType(pt.getActualTypeArguments()(0))
        case _ =>
          throw new RuntimeException(s"Cannot resolve return type ${method.getGenericReturnType} for command $name")
      }

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

  /**
    * Verifies that none of the given methods have CloudState annotations that are not allowed.
    *
    * This is designed to eagerly catch mistakes such as importing the wrong CommandHandler annotation.
    */
  def validateNoBadMethods(methods: Seq[Method], entity: Class[_ <: Annotation], allowed: Set[Class[_ <: Annotation]]): Unit = {
    methods.foreach { method =>
      method.getAnnotations.foreach { annotation =>
        if (annotation.annotationType().getAnnotation(classOf[CloudStateAnnotation]) != null && !allowed(annotation.annotationType())) {
          val maybeAlternative = allowed.find(_.getSimpleName == annotation.annotationType().getSimpleName)
          throw new RuntimeException(s"Annotation @${annotation.annotationType().getName} on method ${method.getDeclaringClass.getName}." +
            s"${method.getName} not allowed in @${entity.getName} annotated entity." +
            maybeAlternative.fold("")(alterative => s" Did you mean to use @${alterative.getName}?")
          )
        }
      }
    }
  }
}
