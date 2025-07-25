package lila.ws
package evalCache

import cats.syntax.option.*
import chess.eval.WinPercent
import chess.format.Fen
import chess.variant.Variant
import org.apache.pekko.actor.typed.Scheduler
import scalalib.zeros.given

import lila.ws.ipc.ClientIn.EvalHitMulti
import lila.ws.ipc.ClientOut.EvalGetMulti
import lila.ws.util.{ ExpireCallbackMemo, ExpireMemo }

/* Compared to EvalCacheUpgrade, accepts multiple positions per member,
 * only sends cp/mate
 */
final private class EvalCacheMulti private (makeExpirableSris: (Sri => Unit) => ExpireMemo[Sri]):
  import EvalCacheMulti.*
  import EvalCacheUpgrade.{ EvalState, SriString }

  private val members = scalalib.ConcurrentMap[SriString, WatchingMember](4096)
  private val evals = scalalib.ConcurrentMap[Id, EvalState](1024)
  private val expirableSris: ExpireMemo[Sri] = makeExpirableSris(expire)

  def register(sri: Sri, e: EvalGetMulti): Unit =
    members
      .compute(sri.value): prev =>
        prev.foreach:
          _.setups.foreach(unregisterEval(_, sri))
        WatchingMember(sri, e.variant, e.fens).some
      .so(_.setups)
      .foreach: id =>
        evals.compute(id)(_.getOrElse(EvalState.initial).addSri(sri).some)
    expirableSris.put(sri)

  def onEval(input: EvalCacheEntry.Input): Unit =
    val sris = onEvalSrisToUpgrade(input)
    if sris.nonEmpty then
      val hit = EvalHitMulti:
        EvalCacheJsonHandlers.writeMultiHit(input.fen, input.eval)
      sris.foreach: sri =>
        Bus.publish(_.sri(sri), hit)
      upgradeMon.count.increment(sris.size)

  private[evalCache] def onEvalSrisToUpgrade(input: EvalCacheEntry.Input): Set[Sri] =
    evals
      .get(input.id)
      .filter(_.depth < input.eval.depth)
      .so: prev =>
        val win = WinPercent.fromScore(input.eval.bestPv.score)
        val winHasChanged = Math.abs(win.value - prev.win.value) > 2
        val newWin = if winHasChanged then win else prev.win
        evals.put(input.id, prev.copy(depth = input.eval.depth, win = newWin))
        winHasChanged.so:
          prev.sris.filter(_ != input.sri)

  private def expire(sri: Sri): Unit =
    members.remove(sri.value).foreach(_.setups.foreach(unregisterEval(_, sri)))

  private def unregisterEval(id: Id, sri: Sri): Unit =
    evals.computeIfPresent(id): eval =>
      val newSris = eval.sris - sri
      Option.unless(newSris.isEmpty)(eval.copy(sris = newSris))

private object EvalCacheMulti:

  case class WatchingMember(sri: Sri, variant: Variant, fens: List[Fen.Full]):
    def setups: List[Id] = fens.flatMap(Id.from(variant, _))

  private val upgradeMon = Monitor.evalCache.multi.upgrade

  def withMonitoring()(using ec: Executor, scheduler: Scheduler): EvalCacheMulti =
    val instance = EvalCacheMulti(expire => ExpireCallbackMemo[Sri](1.minute, expire))
    scheduler.scheduleWithFixedDelay(1.minute, 1.minute): () =>
      upgradeMon.members.update(instance.members.size())
      upgradeMon.evals.update(instance.evals.size())
      upgradeMon.expirable.update(instance.expirableSris.count)
    instance

  def mock() = EvalCacheMulti: _ =>
    new ExpireMemo[Sri]:
      def put(key: Sri): Unit = ()
      def count: Int = 0
