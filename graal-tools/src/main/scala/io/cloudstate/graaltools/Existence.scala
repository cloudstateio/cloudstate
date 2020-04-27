package io.cloudstate.graaltools

import java.util.function.Predicate

final class Existence extends Predicate[String] {
  def test(s: String) =
    try {
      Class.forName(s, false, Thread.currentThread.getContextClassLoader)
      true
    } catch { case _: ClassNotFoundException | _: NoClassDefFoundError => false }
}
