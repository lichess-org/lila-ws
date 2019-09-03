package lila.ws

import ipc._

object LobbySM {

  case class State(
      bus: Bus,
      nbMembers: Int = 0,
      nbRounds: Int = 0
  )

  def apply(state: State, input: Input): State = input match {

    case SetNbMembers(nb) =>
      state.copy(nbMembers = nb)

    case SetNbRounds(nb) =>
      val newState = state.copy(nbRounds = nb)
      state.bus.publish(Bus.msg(ClientIn.LobbyPong(state.nbMembers, state.nbRounds), _.lobby))
      newState
  }

  sealed trait Input
  case class SetNbMembers(members: Int) extends Input
  case class SetNbRounds(rounds: Int) extends Input
}

