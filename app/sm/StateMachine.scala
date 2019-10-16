package lila.ws.sm

import lila.ws.ipc.LilaIn

case class StateMachine[State, Input, Emit](
    zero: State,
    apply: (State, Input) => State,
    emit: State => List[Emit]
)

object StateMachine {

  def debug[State, Input](apply: (State, Input) => State): (State, Input) => State =
    (state, input) => {
      println(state)
      println(input)
      val s = apply(state, input)
      println(s)
      s
    }
}
