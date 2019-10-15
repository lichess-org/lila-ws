package lila.ws
package sm

import akka.actor.typed.ActorRef
import play.api.libs.json.JsObject

import ipc._

object SimulSM {

  case class State(
      simuls: Map[Simul.ID, SimulState] = Map.empty,
      emit: Option[ClientIn] = None
  )

  case class SimulState(
      version: Int,
      userConns: Map[User.ID, Int]
  ) {
    def connect(user: User) =
      copy(userConns = userConns.updatedWith(user.id)(cur => Some(cur.fold(1)(1 + _))))
    def disconnect(user: User) =
      copy(userConns = userConns.updatedWith(user.id)(_.flatMap(cur => Option(cur - 1).filter(0 < _))))
  }
  val newSimulState = SimulState(0, Map.empty)

  def apply(state: State, input: Input): State = input match {

    case Connect(simul, user) =>
      state.copy(
        simuls = state.simuls.updatedWith(simul.id) { cur =>
          Some(cur.getOrElse(newSimulState) connect user)
        },
        emit = None
      )

    case Disconnect(simul, user) =>
      state.copy(
        simuls = state.simuls.updatedWith(simul.id) { _.map(_ disconnect user) },
        emit = None
      )

    case Version(tpe, data) => ???
  }

  sealed trait Input
  case class Connect(simul: Simul, user: User) extends Input
  case class Disconnect(simul: Simul, user: User) extends Input
  case class Version(tpe: String, data: JsObject) extends Input

  def machine = StateMachine[State, Input, ClientIn](State(), apply _, _.emit.toList)
}
