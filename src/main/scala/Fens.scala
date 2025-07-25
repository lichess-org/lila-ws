package lila.ws

import cats.syntax.option.*
import chess.Color
import chess.format.{ Fen, Uci }
import org.apache.pekko.actor.typed.ActorRef

import lila.ws.ipc.*

/* Manages subscriptions to Fen updates */
object Fens:

  case class Watched(position: Option[Position], clients: Set[ActorRef[ClientMsg]])

  private val games = scalalib.ConcurrentMap[Game.Id, Watched](1024)
  export games.size

  // client starts watching
  def watch(gameIds: Iterable[Game.Id], client: Client): Unit =
    gameIds.foreach: gameId =>
      games
        .compute(gameId):
          case None => Watched(None, Set(client)).some
          case Some(Watched(square, clients)) => Watched(square, clients + client).some
        .flatMap(_.position)
        .foreach: p =>
          client ! ClientIn.Fen(gameId, p)

  // when a client disconnects
  def unwatch(gameIds: Iterable[Game.Id], client: Client): Unit =
    gameIds.foreach: gameId =>
      games.computeIfPresent(gameId): watched =>
        val newClients = watched.clients - client
        Option.when(newClients.nonEmpty)(watched.copy(clients = newClients))

  // a game finishes
  def finish(gameId: Game.Id, winner: Option[Color]) =
    games.computeIfPresent(gameId): watched =>
      watched.clients.foreach { _ ! ClientIn.Finish(gameId, winner) }
      none

  // move coming from the server
  def move(gameId: Game.Id, json: JsonString, moveBy: Option[Color]): Unit =
    games.computeIfPresent(gameId): watched =>
      val turnColor = moveBy.fold(Color.white)(c => !c)
      json.value
        .match
          case MoveClockRegex(uciS, fenS, wcS, bcS) =>
            for
              uci <- Uci(uciS)
              wc <- wcS.toIntOption
              bc <- bcS.toIntOption
            yield Position(uci, Fen.Board(fenS), Some(Clock(wc, bc)), turnColor)
          case MoveRegex(uciS, fenS) => Uci(uciS).map { Position(_, Fen.Board(fenS), None, turnColor) }
          case _ => None
        .fold(watched): position =>
          val msg = ClientIn.Fen(gameId, position)
          watched.clients.foreach { _ ! msg }
          watched.copy(position = Some(position))
        .some

  // ...,"uci":"h2g2","san":"Rg2","fen":"r2qb1k1/p2nbrpn/6Np/3pPp1P/1ppP1P2/2P1B3/PP2B1R1/R2Q1NK1",...,"clock":{"white":121.88,"black":120.94}
  private val MoveRegex = """uci":"([^"]+)".+fen":"([^"]+)""".r.unanchored
  private val MoveClockRegex = """uci":"([^"]+)".+fen":"([^"]+).+white":(\d+).+black":(\d+)""".r.unanchored
