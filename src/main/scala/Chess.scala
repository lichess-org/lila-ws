package lila.ws

import cats.syntax.option.*
import chess.format.{ Uci, UciCharPair, UciPath }
import chess.json.Json
import chess.opening.OpeningDb
import chess.variant.Variant

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

  def apply(req: ClientOut.AnaDests): ClientIn.Dests =
    Monitor.time(_.chessDestTime):
      ClientIn.Dests(
        path = req.path,
        dests =
          if req.variant.standard && req.fen == chess.format.Fen.initial && req.path.value.isEmpty
          then initialDests
          else
            val sit = chess.Game(req.variant.some, Some(req.fen)).position
            if sit.playable(false) then Json.destString(sit.destinations) else ""
        ,
        opening =
          if Variant.list.openingSensibleVariants(req.variant)
          then OpeningDb.findByFullFen(req.fen)
          else None,
        chapterId = req.chapterId
      )

  def apply(req: ClientOut.Opening): Option[ClientIn.OpeningMsg] =
    Option
      .when(Variant.list.openingSensibleVariants(req.variant))(req.fen)
      .flatMap(OpeningDb.findByFullFen)
      .map(ClientIn.OpeningMsg(req.path, _))

  private def makeNode(
      game: chess.Game,
      move: Uci.WithSan,
      path: UciPath,
      chapterId: Option[ChapterId]
  ): ClientIn.Node =
    val movable = game.position.playable(false)
    val fen = chess.format.Fen.write(game)
    ClientIn.Node(
      path = path,
      id = UciCharPair(move.uci),
      ply = game.ply,
      move = move,
      fen = fen,
      check = game.position.check,
      dests = if movable then game.position.destinations else Map.empty,
      opening =
        if game.ply <= 30 && Variant.list.openingSensibleVariants(game.position.variant)
        then OpeningDb.findByFullFen(fen)
        else None,
      drops = if movable then game.position.drops else Some(Nil),
      crazyData = game.position.crazyData,
      chapterId = chapterId
    )

  private val initialDests = "iqy muC gvx ltB bqs pxF jrz nvD ksA owE"
