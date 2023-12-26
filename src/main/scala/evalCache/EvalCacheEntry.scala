package lila.ws
package evalCache

import chess.format.{ Fen, Uci }
import chess.variant.Variant
import cats.data.NonEmptyList

import Eval.Score
import java.time.LocalDateTime

case class EvalCacheEntry(
    _id: EvalCacheEntry.Id,
    nbMoves: Int,                     // multipv cannot be greater than number of legal moves
    evals: List[EvalCacheEntry.Eval], // best ones first, by depth and nodes
    usedAt: LocalDateTime,
    updatedAt: LocalDateTime
):

  import EvalCacheEntry.*

  inline def id = _id

  def add(eval: Eval) =
    copy(
      evals = EvalCacheSelector(eval :: evals),
      usedAt = LocalDateTime.now,
      updatedAt = LocalDateTime.now
    )

  // finds the best eval with at least multiPv pvs,
  // and truncates its pvs to multiPv
  def makeBestMultiPvEval(multiPv: MultiPv): Option[Eval] =
    evals
      .find(_.multiPv >= multiPv.atMost(nbMoves))
      .map(_ takePvs multiPv)

  def makeBestSinglePvEval: Option[Eval] =
    evals.headOption.map(_ takePvs MultiPv(1))

  def similarTo(other: EvalCacheEntry) =
    id == other.id && evals == other.evals

object EvalCacheEntry:

  case class Eval(pvs: NonEmptyList[Pv], knodes: Knodes, depth: Depth, by: User.Id, trust: Trust):

    def multiPv = MultiPv(pvs.size)

    def bestPv: Pv = pvs.head

    def bestMove: Uci = bestPv.moves.value.head

    def looksValid =
      pvs.toList.forall(_.looksValid) && (
        pvs.toList.forall(_.score.mateFound) || (knodes >= MIN_KNODES || depth >= MIN_DEPTH)
      )

    def truncatePvs = copy(pvs = pvs.map(_.truncate))

    def takePvs(multiPv: MultiPv) =
      copy(pvs = NonEmptyList(pvs.head, pvs.tail.take(multiPv.value - 1)))

    def depthAboveMin = (depth - MIN_DEPTH) atLeast 0

  case class Pv(score: Score, moves: Moves):

    def looksValid =
      score.mate match
        case None       => moves.value.toList.sizeIs > MIN_PV_SIZE
        case Some(mate) => mate.value != 0 // sometimes we get #0. Dunno why.

    def truncate = copy(moves = Moves truncate moves)

  case class Id(variant: Variant, smallFen: SmallFen)
  object Id:
    def make(variant: Variant, fen: Fen.Epd): Id =
      Id(variant, SmallFen.make(variant, fen.simple))

  case class Input(id: Id, fen: Fen.Epd, eval: Eval)

  def makeInput(variant: Variant, fen: Fen.Epd, eval: Eval) =
    SmallFen.validate(variant, fen) ifTrue eval.looksValid map: smallFen =>
      Input(Id(variant, smallFen), fen, eval.truncatePvs)
