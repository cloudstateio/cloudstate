package io.cloudstate.javasupport.impl

import io.cloudstate.javasupport.{ServiceCallFactory, ServiceCallRef, StatefulService}

class ResolvedServiceCallFactory(services: Map[String, StatefulService]) extends ServiceCallFactory {
  override def lookup[T](serviceName: String, methodName: String): ServiceCallRef[T] = {
    services.get(serviceName) match {
      case Some(service) =>
        service.resolvedMethods match {
          case Some(resolvedMethods) =>
            resolvedMethods.get(methodName) match {
              case Some(method) =>
                method.asInstanceOf[ServiceCallRef[T]]
              case None =>
                throw new NoSuchElementException(s"No method named $methodName found on service $serviceName")
            }
          case None =>
            throw new IllegalStateException(s"Service $serviceName does not provide resolved methods and so can't be looked up by this factory")
        }
      case _ =>
        throw new NoSuchElementException(s"No service named $serviceName is being handled by this stateful service")
    }
  }
}
