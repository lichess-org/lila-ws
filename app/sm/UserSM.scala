package lila.ws
package sm

import akka.actor.typed.ActorRef
import play.api.libs.json.JsObject

import ipc._

object UserSM {

  case class State(
      users: Map[User.ID, Set[ActorRef[ClientMsg]]] = Map.empty,
      disconnects: Set[User.ID] = Set.empty,
      emit: Option[LilaIn.Site] = None
  )

  def apply(state: State, input: Input): State = input match {

    case Connect(user, client) => {
      state.users get user.id match {
        case None => state.copy(
          users = state.users + (user.id -> Set(client)),
          emit = Some(LilaIn.ConnectUser(user))
        )
        case Some(clients) => state.copy(
          users = state.users + (user.id -> (clients + client)),
          emit = None
        )
      }
    } match {
      // if a disconnect for that user was buffered,
      // remove it from the buffer and skip the ConnectUser event
      case s @ State(_, discs, Some(LilaIn.ConnectUser(user))) if discs(user.id) => s.copy(
        disconnects = discs - user.id,
        emit = None
      )
      case s => s
    }

    case ConnectSilently(user, client) =>
      apply(state, Connect(user, client)).copy(emit = None)

    case Disconnect(user, client) =>
      state.users get user.id match {
        case None => state.copy(emit = None)
        case Some(clients) =>
          val newClients = clients - client
          if (newClients.isEmpty) state.copy(
            users = state.users - user.id,
            disconnects = state.disconnects + user.id,
            emit = None
          )
          else state.copy(
            users = state.users + (user.id -> newClients),
            emit = None
          )
      }

    case PublishDisconnects => state.copy(
      disconnects = Set.empty,
      emit = Some(LilaIn.DisconnectUsers(state.disconnects))
    )

    case TellOne(userId, payload) =>
      state.users get userId foreach {
        _ foreach { _ ! payload }
      }
      state.copy(emit = None)

    case TellMany(userIds, payload) =>
      userIds flatMap state.users.get foreach {
        _ foreach { _ ! payload }
      }
      state.copy(emit = None)

    case Kick(userId) =>
      state.users get userId foreach {
        _ foreach { _ ! ClientCtrl.Disconnect }
      }
      state.copy(emit = None)
  }

  sealed trait Input
  case class Connect(user: User, client: ActorRef[ClientMsg]) extends Input
  case class ConnectSilently(user: User, client: ActorRef[ClientMsg]) extends Input
  case class Disconnect(user: User, client: ActorRef[ClientMsg]) extends Input
  case class TellOne(userId: User.ID, payload: ClientIn) extends Input
  case class TellMany(userIds: Iterable[User.ID], payload: ClientIn) extends Input
  case class Kick(userId: User.ID) extends Input
  case object PublishDisconnects extends Input

  def machine = StateMachine[State, Input, LilaIn.Site](State(), apply _, _.emit.toList)
}
