package lila.ws
package sm

import ipc.LilaIn

object CountSM {

  case class State(
      count: Int = 0,
      emit: Option[LilaIn.Site] = None
  )

  def zero = State()

  def apply(state: State, input: Input): State = input match {

    case Connect => state.copy(count = state.count + 1, emit = None)

    case Disconnect => state.copy(count = state.count - 1, emit = None)

    case Publish => state.copy(emit = Some(LilaIn.Connections(state.count)))
  }

  sealed trait Input
  case object Connect extends Input
  case object Disconnect extends Input
  case object Publish extends Input

  def machine = StateMachine[State, Input, LilaIn.Site](State(), apply _, _.emit.toList)
}
