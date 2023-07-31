package lila.ws

import play.api.libs.json.*
import chess.format.{ Fen, Uci, UciCharPair, UciPath }
import chess.opening.{ Opening, OpeningDb }
import chess.Square
import chess.bitboard.Bitboard
import chess.variant.{ Crazyhouse, Variant }
import com.typesafe.scalalogging.Logger
import cats.syntax.option.*

import ipc.*

object Chess:

  private val logger = Logger(getClass)

  def apply(req: ClientOut.AnaMove): ClientIn =
    Monitor.time(_.chessMoveTime):
      try
        chess
          .Game(req.variant.some, Some(req.fen))(req.orig, req.dest, req.promotion)
          .map((game, move) => makeNode(game, Uci.WithSan(Uci(move), move.san), req.path, req.chapterId))
          .getOrElse(ClientIn.StepFailure)
      catch
        case e: java.lang.ArrayIndexOutOfBoundsException =>
          logger.warn(s"${req.fen} ${req.variant} ${req.orig}${req.dest}", e)
          ClientIn.StepFailure

  def apply(req: ClientOut.AnaDrop): ClientIn =
    Monitor.time(_.chessMoveTime):
      try
        chess
          .Game(req.variant.some, Some(req.fen))
          .drop(req.role, req.square)
          .map((game, drop) => makeNode(game, Uci.WithSan(Uci(drop), drop.san), req.path, req.chapterId))
          .getOrElse(ClientIn.StepFailure)
      catch
        case e: java.lang.ArrayIndexOutOfBoundsException =>
          logger.warn(s"${req.fen} ${req.variant} ${req.role}@${req.square}", e)
          ClientIn.StepFailure

  def apply(req: ClientOut.AnaDests): ClientIn.Dests =
    Monitor.time(_.chessDestTime):
      ClientIn.Dests(
        path = req.path,
        dests =
          if req.variant.standard && req.fen == chess.format.Fen.initial && req.path.value.isEmpty
          then initialDests
          else
            val sit = chess.Game(req.variant.some, Some(req.fen)).situation
            if sit.playable(false) then json.destString(sit.destinations) else ""
        ,
        opening =
          if Variant.list.openingSensibleVariants(req.variant)
          then OpeningDb findByEpdFen req.fen
          else None,
        chapterId = req.chapterId
      )

  def apply(req: ClientOut.Opening): Option[ClientIn.OpeningMsg] =
    if Variant.list.openingSensibleVariants(req.variant)
    then
      OpeningDb findByEpdFen req.fen map:
        ClientIn.OpeningMsg(req.path, _)
    else None

  private def makeNode(
      game: chess.Game,
      move: Uci.WithSan,
      path: UciPath,
      chapterId: Option[ChapterId]
  ): ClientIn.Node =
    val movable = game.situation playable false
    val fen     = chess.format.Fen write game
    ClientIn.Node(
      path = path,
      id = UciCharPair(move.uci),
      ply = game.ply,
      move = move,
      fen = fen,
      check = game.situation.check,
      dests = if movable then game.situation.destinations else Map.empty,
      opening =
        if game.ply <= 30 && Variant.list.openingSensibleVariants(game.board.variant)
        then OpeningDb findByEpdFen fen
        else None,
      drops = if movable then game.situation.drops else Some(Nil),
      crazyData = game.situation.board.crazyData,
      chapterId = chapterId
    )

  private val initialDests = "iqy muC gvx ltB bqs pxF jrz nvD ksA owE"

  object json:
    given Writes[Uci] with
      def writes(uci: Uci) = JsString(uci.uci)
    given Writes[UciCharPair] with
      def writes(ucp: UciCharPair) = JsString(ucp.toString)
    given Writes[Square] with
      def writes(square: Square) = JsString(square.key)
    given Writes[Opening] with
      def writes(o: Opening) = Json.obj("eco" -> o.eco, "name" -> o.name)
    given Writes[Map[Square, Bitboard]] with
      def writes(dests: Map[Square, Bitboard]) = JsString(destString(dests))

    def destString(dests: Map[Square, Bitboard]): String =
      val sb    = new java.lang.StringBuilder(80)
      var first = true
      dests.foreach: (orig, dests) =>
        if first then first = false
        else sb append " "
        sb append orig.asChar
        dests foreach { sb append _.asChar }
      sb.toString

    given OWrites[Crazyhouse.Pocket] = OWrites: v =>
      JsObject:
        v.map((role, count) => role.name -> JsNumber(count))

    given OWrites[chess.variant.Crazyhouse.Data] = OWrites: v =>
      Json.obj("pockets" -> v.pockets.all)
