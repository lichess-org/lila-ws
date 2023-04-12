package lila.ws
package evalCache

import cats.syntax.all.*
import chess.format.{ Fen, Uci, UciPath }
import play.api.libs.json.*
import chess.variant.Variant

import evalCache.EvalCacheEntry.*
import evalCache.Eval.*

object EvalCacheJsonHandlers:

  def readGet(d: JsObject) = for
    fen <- d.get[Fen.Epd]("fen")
    variant = Variant.orDefault(d.get[Variant.LilaKey]("variant"))
    multiPv = d.get[MultiPv]("mpv") | MultiPv(1)
    path <- d.get[UciPath]("path")
    up = d.get[Boolean]("up") | false
  yield ipc.ClientOut.EvalGet(fen, variant, multiPv, path, up)

  def readPut(d: JsObject) = for
    fen <- d.get[Fen.Epd]("fen")
    variant = Variant.orDefault(d.get[Variant.LilaKey]("variant"))
    knodes <- d.get[Knodes]("knodes")
    depth  <- d.get[Depth]("depth")
    pvObjs <- d objs "pvs"
    pvs    <- pvObjs.map(parsePv).sequence.flatMap(_.toNel)
  yield ipc.ClientOut.EvalPut(fen, variant, pvs, knodes, depth)

  def writeEval(e: Eval, fen: Fen.Epd) =
    Json.obj(
      "fen"    -> fen,
      "knodes" -> e.knodes,
      "depth"  -> e.depth,
      "pvs"    -> JsArray(e.pvs.toList.map(writePv))
    )

  private def writePv(pv: Pv) = Json
    .obj("moves" -> pv.moves.value.toList.map(_.uci).mkString(" "))
    .add("cp" -> pv.score.cp)
    .add("mate" -> pv.score.mate)

  private def parsePv(d: JsObject): Option[Pv] =
    for {
      movesStr <- d str "moves"
      moves <- Moves from
        movesStr
          .split(' ')
          .take(MAX_PV_SIZE)
          .foldLeft(List.empty[Uci].some):
            case (Some(ucis), str) => Uci(str) map (_ :: ucis)
            case _                 => None
          .flatMap(_.reverse.toNel)
      score <- d.get[Cp]("cp").map(Score.cp(_)) orElse
        d.get[Mate]("mate").map(Score.mate(_))
    } yield Pv(score, moves)
