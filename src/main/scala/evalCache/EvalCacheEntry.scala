package lila.ws
package evalCache

import cats.data.NonEmptyList
import chess.Position
import chess.eval.Score
import chess.format.{ BinaryFen, Fen, Uci }
import chess.variant.Variant
import com.github.blemale.scaffeine.{ LoadingCache, Scaffeine }

import java.time.LocalDateTime

opaque type Id = BinaryFen
object Id:
  def apply(fen: BinaryFen): Id = fen
  def apply(position: Position): Id = BinaryFen.writeNormalized(position)
  object from:
    private val standardCache: LoadingCache[Fen.Full, Option[Id]] =
      Scaffeine().expireAfterWrite(1.minute).build(compute(chess.variant.Standard, _))
    private def compute(variant: Variant, fen: Fen.Full): Option[Id] =
      Fen.read(variant, fen).map(BinaryFen.writeNormalized)
    def apply(variant: Variant, fen: Fen.Full): Option[Id] =
      if variant.standard then standardCache.get(fen) else compute(variant, fen)
  extension (id: Id) def value: BinaryFen = id

case class EvalCacheEntry(
    _id: Id,
    nbMoves: Int, // multipv cannot be greater than number of legal moves
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
      .map(_.takePvs(multiPv))

  def makeBestSinglePvEval: Option[Eval] =
    evals.headOption.map(_.takePvs(MultiPv(1)))

  def similarTo(other: EvalCacheEntry) =
    id == other.id && evals == other.evals

object EvalCacheEntry:

  case class Input(id: Id, fen: Fen.Full, position: Position, eval: Eval, sri: Sri)

  case class Eval(pvs: NonEmptyList[Pv], knodes: Knodes, depth: Depth, by: User.Id, trust: Trust):

    def multiPv = MultiPv(pvs.size)

    def bestPv: Pv = pvs.head

    def bestMove: Uci = bestPv.moves.value.head

    def looksValid = pvs.forall(_.looksValid) && uniqueFirstMoves && isSufficientlyAnalysed

    private def uniqueFirstMoves = pvs.map(_.moves.value.head.uci).distinct.size == pvs.size

    private def isSufficientlyAnalysed =
      pvs.forall(_.score.mateFound) || (knodes >= MIN_KNODES || depth >= MIN_DEPTH)

    def truncatePvs = copy(pvs = pvs.map(_.truncate))

    def takePvs(multiPv: MultiPv) =
      copy(pvs = NonEmptyList(pvs.head, pvs.tail.take(multiPv.value - 1)))

    def depthAboveMin = (depth - MIN_DEPTH).atLeast(0)

  case class Pv(score: Score, moves: Moves):

    def looksValid = score.mate match
      case None => moves.value.toList.sizeIs > MIN_PV_SIZE
      case Some(mate) => mate.value != 0 // sometimes we get #0. Dunno why.

    def truncate = copy(moves = Moves.truncate(moves))

  def makeInput(variant: Variant, fen: Fen.Full, eval: Eval, sri: Sri) =
    Fen
      .read(variant, fen)
      .filter(_.playable(false))
      .ifTrue(eval.looksValid)
      .map: position =>
        Input(Id(position), fen, position, eval.truncatePvs, sri)
