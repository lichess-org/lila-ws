package lila.ws

import cats.syntax.option.*
import chess.format.{ Uci, UciPath }

import ipc.*

object Chess:

  def apply(req: ClientOut.AnaMove): ClientIn =
    Monitor.time(_.chessMoveTime):
      chess
        .Game(req.variant.some, req.fen.some)(req.orig, req.dest, req.promotion)
        .map((game, move) => makeNode(game, Uci.WithSan(Uci(move), move.toSanStr), req.path, req.chapterId))
        .getOrElse(ClientIn.StepFailure)

  def apply(req: ClientOut.AnaDrop): ClientIn =
    Monitor.time(_.chessMoveTime):
      chess
        .Game(req.variant.some, req.fen.some)
        .drop(req.role, req.square)
        .map((game, drop) => makeNode(game, Uci.WithSan(Uci(drop), drop.toSanStr), req.path, req.chapterId))
        .getOrElse(ClientIn.StepFailure)

  private def makeNode(
      game: chess.Game,
      move: Uci.WithSan,
      path: UciPath,
      chapterId: Option[ChapterId]
  ): ClientIn.Node =
    ClientIn.Node(
      path = path,
      ply = game.ply,
      move = move,
      fen = chess.format.Fen.write(game),
      crazyData = game.position.crazyData,
      chapterId = chapterId
    )
