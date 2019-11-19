package lila.ws

import akka.actor.typed.ActorRef
import chess.format.{ FEN, Uci }
import java.util.concurrent.ConcurrentHashMap
import javax.inject._

import ipc._

/* Manages subscriptions to FEN updates */
@Singleton
final class Fens @Inject() (lila: Lila) {

  import Fens._

  private val games = new ConcurrentHashMap[Game.Id, Watched](1024)

  private val lilaIn = lila.emit.site

  // client starts watching
  def watch(gameIds: Iterable[Game.Id], client: Client): Unit =
    gameIds foreach { gameId =>
      games.compute(gameId, {
        case (_, null) =>
          lilaIn(LilaIn.Watch(gameId))
          Watched(None, Set(client))
        case (_, Watched(position, clients)) =>
          Watched(position, clients + client)
      }).position foreach {
        case Position(lastUci, fen) => client ! ClientIn.Fen(gameId, lastUci, fen)
      }
    }

  // when a client disconnects
  def unwatch(gameIds: Iterable[Game.Id], client: Client): Unit =
    gameIds foreach { gameId =>
      games.computeIfPresent(gameId, (_, watched) => {
        val newClients = watched.clients - client
        if (newClients.isEmpty) {
          lilaIn(LilaIn.Unwatch(gameId))
          null
        }
        else watched.copy(clients = newClients)
      })
    }

  // move comes from the server
  def move(gameId: Game.Id, lastUci: Uci, fen: FEN): Unit = {
    val watched = games.computeIfPresent(gameId, (_, watched) =>
      watched.copy(position = Some(Position(lastUci, fen))))
    if (watched != null) {
      val msg = ClientIn.Fen(gameId, lastUci, fen)
      watched.clients foreach { _ ! msg }
    }
  }
}

object Fens {
  case class Position(lastUci: Uci, fen: FEN)
  case class Watched(position: Option[Position], clients: Set[ActorRef[ClientMsg]])
}
