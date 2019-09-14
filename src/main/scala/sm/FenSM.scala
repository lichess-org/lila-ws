package lila.ws
package sm

import akka.actor.typed.ActorRef
import chess.format.{ FEN, Uci }

import ipc._

/* Manages subscriptions to FEN updates */
object FenSM {

  case class State(
      games: Map[Game.ID, Watched] = Map.empty,
      emit: List[LilaIn.Site] = Nil
  )

  def apply(state: State, input: Input): State = input match {

    // client starts watching
    case Watch(gameIds, client) =>
      gameIds.foldLeft(state.copy(emit = Nil)) {
        case (s, gameId) => s.games get gameId match {
          case Some(Watched(position, clients)) =>
            position foreach {
              case Position(lastUci, fen) => client ! ClientIn.Fen(gameId, lastUci, fen)
            }
            s.copy(
              games = s.games + (gameId -> Watched(position, clients + client))
            )
          case None => s.copy(
            games = s.games + (gameId -> Watched(None, Set(client))),
            emit = LilaIn.Watch(gameId) :: s.emit
          )
        }
      }

    // when a client disconnects
    case Unwatch(gameIds, client) =>
      gameIds.foldLeft(state.copy(emit = Nil)) {
        case (s, gameId) => s.games.get(gameId).fold(s) {
          case Watched(position, clients) =>
            val newClients = clients - client
            if (newClients.isEmpty) s.copy(
              games = s.games - gameId,
              emit = LilaIn.Unwatch(gameId) :: s.emit
            )
            else s.copy(
              games = s.games + (gameId -> Watched(position, newClients))
            )
        }
      }

    // move comes from the server
    case Move(LilaOut.Move(gameId, lastUci, fen)) =>
      state.games.get(gameId).fold(state.copy(emit = Nil)) {
        case w @ Watched(_, clients) =>
          val msg = ClientIn.Fen(gameId, lastUci, fen)
          clients foreach { _ ! msg }
          state.copy(
            games = state.games + (gameId -> w.copy(position = Some(Position(lastUci, fen)))),
            emit = Nil
          )
      }
  }

  sealed trait Input
  case class Watch(gameIds: Iterable[Game.ID], client: ActorRef[ClientMsg]) extends Input
  case class Unwatch(gameIds: Iterable[Game.ID], client: ActorRef[ClientMsg]) extends Input
  case class Move(move: LilaOut.Move) extends Input

  case class Position(lastUci: Uci, fen: FEN)
  case class Watched(position: Option[Position], clients: Set[ActorRef[ClientMsg]])

  def machine = StateMachine[State, Input, LilaIn.Site](State(), apply _, _.emit)
}
