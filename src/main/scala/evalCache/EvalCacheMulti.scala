package lila.ws
package evalCache

import java.util.concurrent.ConcurrentHashMap
import org.apache.pekko.actor.typed.Scheduler
import chess.format.Fen
import chess.variant.Variant
import scalalib.zeros.given

import lila.ws.ipc.ClientIn.EvalHitMulti
import lila.ws.ipc.ClientOut.EvalGetMulti
import lila.ws.util.ExpireCallbackMemo
import lila.ws.util.ExpireMemo

/* Compared to EvalCacheUpgrade, accepts multiple positions per member,
 * only sends cp/mate
 */
final private class EvalCacheMulti private (makeExpirableSris: (Sri => Unit) => ExpireMemo[Sri]):
  import EvalCacheMulti.*
  import EvalCacheUpgrade.{ EvalState, SriString }

  private val members                        = ConcurrentHashMap[SriString, WatchingMember](4096)
  private val evals                          = ConcurrentHashMap[Id, EvalState](1024)
  private val expirableSris: ExpireMemo[Sri] = makeExpirableSris(expire)

  def register(sri: Sri, e: EvalGetMulti): Unit =
    members
      .compute(
        sri.value,
        (_, prev) =>
          Option(prev).foreach:
            _.setups.foreach(unregisterEval(_, sri))
          WatchingMember(sri, e.variant, e.fens)
      )
      .setups
      .foreach: id =>
        evals.compute(id, (_, prev) => Option(prev).fold(EvalState(Set(sri), Depth(0)))(_.addSri(sri)))
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
    Option(evals.get(input.id))
      .filter(_.depth < input.eval.depth)
      .so: prev =>
        evals.put(input.id, prev.copy(depth = input.eval.depth))
        prev.sris.filter(_ != input.sri)

  private def expire(sri: Sri): Unit =
    Option(members.remove(sri.value)).foreach:
      _.setups.foreach(unregisterEval(_, sri))

  private def unregisterEval(id: Id, sri: Sri): Unit =
    evals.computeIfPresent(
      id,
      (_, eval) =>
        val newSris = eval.sris - sri
        if newSris.isEmpty then null
        else eval.copy(sris = newSris)
    )

private object EvalCacheMulti:

  import EvalCacheUpgrade.*

  case class WatchingMember(sri: Sri, variant: Variant, fens: List[Fen.Full]):
    def setups: List[Id] = fens.flatMap(Id.from(variant, _))

  private val upgradeMon = Monitor.evalCache.multi.upgrade

  def withMonitoring()(using ec: Executor, scheduler: Scheduler): EvalCacheMulti =
    val instance = EvalCacheMulti(expire => ExpireCallbackMemo[Sri](1 minute, expire))
    scheduler.scheduleWithFixedDelay(1 minute, 1 minute): () =>
      upgradeMon.members.update(instance.members.size)
      upgradeMon.evals.update(instance.evals.size)
      upgradeMon.expirable.update(instance.expirableSris.count)
    instance

  def mock() = EvalCacheMulti: _ =>
    new ExpireMemo[Sri]:
      def put(key: Sri): Unit = ()
      def count: Int          = 0
