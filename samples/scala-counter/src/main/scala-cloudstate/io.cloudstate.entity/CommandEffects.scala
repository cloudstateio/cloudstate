package io.cloudstate.entity

trait CommandEffects[E] {

}

object CommandEffects {
  def accept[E](events: E*): CommandEffectsAccept[E] = {
    val _events = events
    new CommandEffectsAccept[E] {
      override def events: Seq[E] = _events
    }
  }
  def reject[E](reason: String): CommandEffectsReject[E] = {
    val _reason = reason
    new CommandEffectsReject[E] {
      override def reason: String = _reason
    }
  }
}

trait CommandEffectsAccept[E] extends CommandEffects[E] {
  def events: Seq[E]
}

trait CommandEffectsReject[E] extends CommandEffects[E] {
  def reason: String
}
