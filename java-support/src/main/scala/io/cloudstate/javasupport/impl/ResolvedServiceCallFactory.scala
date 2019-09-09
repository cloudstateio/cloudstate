package io.cloudstate.javasupport.impl

import io.cloudstate.javasupport.{ServiceCallFactory, ServiceCallRef, StatefulService}

class ResolvedServiceCallFactory(services: Map[String, StatefulService]) extends ServiceCallFactory {
  override def lookup[T](serviceName: String, methodName: String, methodType: Class[T]): ServiceCallRef[T] =
    services.get(serviceName) match {
      case Some(service) =>
        service.resolvedMethods match {
          case Some(resolvedMethods) =>
            resolvedMethods.get(methodName) match {
              case Some(method) if method.inputType.typeClass.isAssignableFrom(methodType) =>
                method.asInstanceOf[ServiceCallRef[T]]
              case Some(badTypedMethod) =>
                throw new IllegalArgumentException(
                  s"The input type ${badTypedMethod.inputType.typeClass.getName} of $serviceName.$methodName does not match the requested message type ${methodType.getName}"
                )
              case None =>
                throw new NoSuchElementException(s"No method named $methodName found on service $serviceName")
            }
          case None =>
            throw new IllegalStateException(
              s"Service $serviceName does not provide resolved methods and so can't be looked up by this factory"
            )
        }
      case _ =>
        throw new NoSuchElementException(s"No service named $serviceName is being handled by this stateful service")
    }
}
