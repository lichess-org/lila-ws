package lila.ws
package evalCache

import chess.format.Fen
import chess.variant.Variant

import java.util.concurrent.ConcurrentHashMap

import lila.ws.ipc.ClientIn.EvalHitMulti
import lila.ws.ipc.ClientOut.EvalGetMulti
import lila.ws.util.ExpireCallbackMemo

/* Compared to EvalCacheUpgrade, accepts multiple positions per member,
 * only sends cp/mate
 */
final private class EvalCacheMulti(using
    ec: Executor,
    scheduler: org.apache.pekko.actor.typed.Scheduler
):
  import EvalCacheMulti.*
  import EvalCacheUpgrade.{ EvalState, SriString }

  private val members       = ConcurrentHashMap[SriString, WatchingMember](4096)
  private val evals         = ConcurrentHashMap[Id, EvalState](1024)
  private val expirableSris = ExpireCallbackMemo[Sri](scheduler, 1 minute, expire)

  private val upgradeMon = Monitor.evalCache.multi.upgrade

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

  def onEval(input: EvalCacheEntry.Input, fromSri: Sri): Unit =
    Option(
      evals.computeIfPresent(
        input.id,
        (_, ev) =>
          if ev.depth >= input.eval.depth then ev
          else ev.copy(depth = input.eval.depth)
      )
    ).filter(_.depth == input.eval.depth)
      .foreach: eval =>
        val sris = eval.sris.filter(_ != fromSri)
        if sris.nonEmpty then
          val hit = EvalHitMulti:
            EvalCacheJsonHandlers.writeMultiHit(input.fen, input.eval)
          sris.foreach: sri =>
            Bus.publish(_.sri(sri), hit)
          upgradeMon.count.increment(sris.size)

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

  scheduler.scheduleWithFixedDelay(1 minute, 1 minute): () =>
    upgradeMon.members.update(members.size)
    upgradeMon.evals.update(evals.size)
    upgradeMon.expirable.update(expirableSris.count)

private object EvalCacheMulti:

  import EvalCacheUpgrade.*

  case class WatchingMember(sri: Sri, variant: Variant, fens: List[Fen.Full]):
    def setups: List[Id] = fens.flatMap(Id.from(variant, _))
