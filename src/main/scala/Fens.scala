package lila.ws

import org.apache.pekko.actor.typed.ActorRef
import chess.Color
import chess.format.{ Fen, Uci }
import java.util.concurrent.ConcurrentHashMap
import lila.ws.ipc.*

/* Manages subscriptions to Fen updates */
object Fens:

  case class Watched(position: Option[Position], clients: Set[ActorRef[ClientMsg]])

  private val games = ConcurrentHashMap[Game.Id, Watched](1024)

  // client starts watching
  def watch(gameIds: Iterable[Game.Id], client: Client): Unit =
    gameIds.foreach: gameId =>
      games
        .compute(
          gameId,
          {
            case (_, null)                     => Watched(None, Set(client))
            case (_, Watched(square, clients)) => Watched(square, clients + client)
          }
        )
        .position
        .foreach: p =>
          client ! ClientIn.Fen(gameId, p)

  // when a client disconnects
  def unwatch(gameIds: Iterable[Game.Id], client: Client): Unit =
    gameIds.foreach: gameId =>
      games.computeIfPresent(
        gameId,
        (_, watched) =>
          val newClients = watched.clients - client
          if newClients.isEmpty then null
          else watched.copy(clients = newClients)
      )

  // a game finishes
  def finish(gameId: Game.Id, winner: Option[Color]) =
    games.computeIfPresent(
      gameId,
      (_, watched) =>
        watched.clients foreach { _ ! ClientIn.Finish(gameId, winner) }
        null
    )

  // move coming from the server
  def move(gameId: Game.Id, json: JsonString, moveBy: Option[Color]): Unit =
    games.computeIfPresent(
      gameId,
      (_, watched) =>
        val turnColor = moveBy.fold(Color.white)(c => !c)
        json.value
          .match
            case MoveClockRegex(uciS, fenS, wcS, bcS) =>
              for
                uci <- Uci(uciS)
                wc  <- wcS.toIntOption
                bc  <- bcS.toIntOption
              yield Position(uci, Fen.Board(fenS), Some(Clock(wc, bc)), turnColor)
            case MoveRegex(uciS, fenS) => Uci(uciS) map { Position(_, Fen.Board(fenS), None, turnColor) }
            case _                     => None
          .fold(watched): position =>
            val msg = ClientIn.Fen(gameId, position)
            watched.clients foreach { _ ! msg }
            watched.copy(position = Some(position))
    )

  // ...,"uci":"h2g2","san":"Rg2","fen":"r2qb1k1/p2nbrpn/6Np/3pPp1P/1ppP1P2/2P1B3/PP2B1R1/R2Q1NK1",...,"clock":{"white":121.88,"black":120.94}
  private val MoveRegex      = """uci":"([^"]+)".+fen":"([^"]+)""".r.unanchored
  private val MoveClockRegex = """uci":"([^"]+)".+fen":"([^"]+).+white":(\d+).+black":(\d+)""".r.unanchored

  def size = games.size
