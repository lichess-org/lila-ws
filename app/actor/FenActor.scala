package lila.ws

import akka.actor._
import scala.collection.mutable.AnyRefMap

import ipc._

/* Manages subscriptions to FEN updates */
final class FenActor(lilaIn: LilaIn => Unit) extends Actor {

  import FenActor._

  val games = AnyRefMap.empty[Game.ID, Watched]

  def receive = {

    // client starts watching
    case ClientOut.Watch(gameIds) => gameIds foreach { gameId =>
      games.put(gameId, games get gameId match {
        case Some(Watched(position, by)) =>
          position foreach {
            case Position(lastUci, fen) => sender ! ClientIn.Fen(gameId, lastUci, fen)
          }
          Watched(position, by + sender)
        case None =>
          lilaIn(LilaIn.Watch(gameId))
          Watched(None, Set(sender))
      })
    }

    // when a client disconnects
    case FenActor.Unwatch(gameIds) => gameIds foreach { id =>
      games get id foreach {
        case Watched(position, by) =>
          val newBy = by - sender
          if (newBy.isEmpty) {
            games.remove(id)
            lilaIn(LilaIn.Unwatch(id))
          }
          else games.put(id, Watched(position, newBy))
      }
    }

    // move comes from the server
    case LilaOut.Move(gameId, lastUci, fen) => games get gameId foreach {
      case Watched(_, by) =>
        val msg = ClientIn.Fen(gameId, lastUci, fen)
        by foreach { _ ! msg }
        games.put(gameId, Watched(Some(Position(lastUci, fen)), by))
    }
  }
}

object FenActor {

  case class Watch(gameIds: Iterable[Game.ID])
  case class Unwatch(gameIds: Iterable[Game.ID])

  case class Position(lastUci: String, fen: String)
  case class Watched(position: Option[Position], by: Set[ActorRef])

  def props(lilaIn: LilaIn => Unit) = Props(new FenActor(lilaIn))
}
