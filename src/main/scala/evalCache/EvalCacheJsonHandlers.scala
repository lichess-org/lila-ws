package lila.ws
package evalCache

import cats.syntax.all.*
import chess.eval.*
import chess.format.{ Fen, Uci, UciPath }
import chess.variant.Variant
import play.api.libs.json.*

import evalCache.EvalCacheEntry.*

object EvalCacheJsonHandlers:

  def readGet(d: JsObject) = for
    fen <- d.get[Fen.Full]("fen")
    variant = Variant.orDefault(d.get[Variant.LilaKey]("variant"))
    multiPv = d.get[MultiPv]("mpv") | MultiPv(1)
    path <- d.get[UciPath]("path")
    up = d.get[Boolean]("up") | false
  yield ipc.ClientOut.EvalGet(fen, variant, multiPv, path, up)

  def readPut(d: JsObject) = for
    fen <- d.get[Fen.Full]("fen")
    variant = Variant.orDefault(d.get[Variant.LilaKey]("variant"))
    knodes <- d.get[Knodes]("knodes")
    depth <- d.get[Depth]("depth")
    pvObjs <- d.objs("pvs")
    pvs <- pvObjs.map(parsePv).sequence.flatMap(_.toNel)
  yield ipc.ClientOut.EvalPut(fen, variant, pvs, knodes, depth)

  def readGetMulti(d: JsObject) = for
    fens <- d.get[List[Fen.Full]]("fens")
    variant = Variant.orDefault(d.get[Variant.LilaKey]("variant"))
  yield ipc.ClientOut.EvalGetMulti(fens.take(32), variant)

  def writeEval(e: Eval, fen: Fen.Full) = Json.obj(
    "fen" -> fen,
    "knodes" -> e.knodes,
    "depth" -> e.depth,
    "pvs" -> JsArray(e.pvs.toList.map(writePv))
  )

  def writeMultiHit(fen: Fen.Full, e: Eval): JsObject = Json
    .obj("fen" -> fen)
    .add("cp" -> e.bestPv.score.cp)
    .add("mate" -> e.bestPv.score.mate)

  def writeMultiHit(evals: List[(Fen.Full, Eval)]): JsObject = evals match
    case List(single) => writeMultiHit.tupled(single)
    case many => Json.obj("multi" -> many.map(writeMultiHit.tupled))

  private def writePv(pv: Pv) = Json
    .obj("moves" -> pv.moves.value.toList.map(_.uci).mkString(" "))
    .add("cp" -> pv.score.cp)
    .add("mate" -> pv.score.mate)

  private def parsePv(d: JsObject): Option[Pv] =
    for
      movesStr <- d.str("moves")
      moves <- Moves.from(
        movesStr
          .split(' ')
          .take(MAX_PV_SIZE)
          .foldLeft(List.empty[Uci].some):
            case (Some(ucis), str) => Uci(str).map(_ :: ucis)
            case _ => None
          .flatMap(_.reverse.toNel)
      )
      score <- d.int("cp").map(Score.cp).orElse(d.int("mate").map(Score.mate))
    yield Pv(score, moves)
