package io.cloudstate.javasupport.impl

import java.lang.annotation.Annotation
import java.lang.reflect.{AccessibleObject, Executable, Member, Method}

import io.cloudstate.javasupport.{Context, EntityContext, EntityId}
import io.cloudstate.javasupport.{EntityContext, EntityId}

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
      handlers(i) = if (isWithinBounds(parameter.parameterType, classOf[Context], implicitly[ClassTag[C]].runtimeClass))
        ContextParameterHandler
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
}
