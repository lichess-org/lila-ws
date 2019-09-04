package lila.ws.sm

import lila.ws.ipc.LilaIn

case class StateMachine[State, Input](
    zero: State,
    apply: (State, Input) => State,
    emit: State => List[LilaIn]
)
