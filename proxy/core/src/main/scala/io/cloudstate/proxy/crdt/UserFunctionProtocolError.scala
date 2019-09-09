package io.cloudstate.proxy.crdt

private[crdt] case class UserFunctionProtocolError(message: String)
    extends RuntimeException(message, null, false, false)
