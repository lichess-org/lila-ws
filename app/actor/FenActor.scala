package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import chess.format.{ FEN, Uci }

import ipc._

/* Manages subscriptions to FEN updates */
object FenActor {

  def empty(lilaIn: LilaIn => Unit) = apply(Map.empty, lilaIn)

  private def apply(games: Map[Game.ID, Watched], lilaIn: LilaIn => Unit): Behavior[Input] = Behaviors.receiveMessage {

    // client starts watching
    case Watch(gameIds, client) => apply(
      gameIds.foldLeft(games) {
        case (games, gameId) => games + (gameId -> (games get gameId match {
          case Some(Watched(position, clients)) =>
            position foreach {
              case Position(lastUci, fen) => client ! ClientIn.Fen(gameId, lastUci, fen)
            }
            Watched(position, clients + client)
          case None =>
            lilaIn(LilaIn.Watch(gameId))
            Watched(None, Set(client))
        }))
      },
      lilaIn
    )

    // when a client disconnects
    case Unwatch(gameIds, client) => apply(
      gameIds.foldLeft(games) {
        case (games, gameId) => games.get(gameId).fold(games) {
          case Watched(position, clients) =>
            val newClients = clients - client
            if (newClients.isEmpty) {
              lilaIn(LilaIn.Unwatch(gameId))
              games - gameId
            }
            else games + (gameId -> Watched(position, newClients))
        }
      },
      lilaIn
    )

    // move comes from the server
    case Move(LilaOut.Move(gameId, lastUci, fen)) => apply(
      games.get(gameId).fold(games) {
        case Watched(_, clients) =>
          val msg = ClientIn.Fen(gameId, lastUci, fen)
          clients foreach { _ ! msg }
          games + (gameId -> Watched(Some(Position(lastUci, fen)), clients))
      },
      lilaIn
    )
  }

  sealed trait Input
  case class Watch(gameIds: Iterable[Game.ID], client: ActorRef[ClientMsg]) extends Input
  case class Unwatch(gameIds: Iterable[Game.ID], client: ActorRef[ClientMsg]) extends Input
  case class Move(move: LilaOut.Move) extends Input

  case class Position(lastUci: Uci, fen: FEN)
  case class Watched(position: Option[Position], clients: Set[ActorRef[ClientMsg]])
}
