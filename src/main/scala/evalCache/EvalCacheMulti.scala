package lila.ws
package evalCache

import chess.format.Fen
import chess.variant.Variant
import lila.ws.util.ExpireCallbackMemo
import lila.ws.ipc.ClientOut.EvalGetMulti
import lila.ws.ipc.ClientIn.EvalHitMulti

import scala.collection.mutable

/* Compared to EvalCacheUpgrade, accepts multiple positions per member,
 * only sends cp/mate
 */
final private class EvalCacheMulti(using
    ec: Executor,
    scheduler: org.apache.pekko.actor.typed.Scheduler
):
  import EvalCacheMulti.*
  import EvalCacheUpgrade.{ EvalState, SetupId, SriString }

  private val members       = mutable.AnyRefMap.empty[SriString, WatchingMember]
  private val evals         = mutable.AnyRefMap.empty[SetupId, EvalState]
  private val expirableSris = ExpireCallbackMemo[Sri](scheduler, 5 minutes, expire)

  private val upgradeMon = Monitor.evalCache.multi.upgrade

  def register(sri: Sri, e: EvalGetMulti): Unit =
    members get sri.value foreach: prevMember =>
      prevMember.setups.foreach(unregisterEval(_, sri))
    val wm = WatchingMember(sri, e.variant, e.fens)
    members += (sri.value -> wm)
    wm.setups.foreach: setupId =>
      evals += (setupId -> evals.get(setupId).fold(EvalState(Set(sri), Depth(0)))(_ addSri sri))
    expirableSris put sri

  def onEval(input: EvalCacheEntry.Input, fromSri: Sri): Unit =
    val setupId = makeSetupId(input.id.variant, input.fen)
    evals
      .get(setupId)
      .filter(_.depth < input.eval.depth)
      .map(setupId -> _)
      .foreach: (setupId, oldEval) =>
        evals += (setupId -> oldEval.copy(depth = input.eval.depth))
        val sris = oldEval.sris.filter(_ != fromSri)
        if sris.nonEmpty then
          val hit = EvalHitMulti:
            EvalCacheJsonHandlers.writeMultiHit(input.fen, input.eval)
          sris.foreach: sri =>
            Bus.publish(_ sri sri, hit)
          upgradeMon.count.increment(sris.size)

  private def expire(sri: Sri): Unit =
    members get sri.value foreach: wm =>
      wm.setups.foreach(unregisterEval(_, sri))
      members -= sri.value

  private def unregisterEval(setupId: SetupId, sri: Sri): Unit =
    evals get setupId foreach: eval =>
      val newSris = eval.sris - sri
      if newSris.isEmpty then evals -= setupId
      else evals += (setupId -> eval.copy(sris = newSris))

  scheduler.scheduleWithFixedDelay(1 minute, 1 minute): () =>
    upgradeMon.members.update(members.size)
    upgradeMon.evals.update(evals.size)
    upgradeMon.expirable.update(expirableSris.count)

private object EvalCacheMulti:

  import EvalCacheUpgrade.*

  def makeSetupId(variant: Variant, fen: Fen.Epd): SetupId =
    s"${variant.id}${SmallFen.make(variant, fen.simple)}"

  case class WatchingMember(sri: Sri, variant: Variant, fens: List[Fen.Epd]):
    def setups: List[SetupId] = fens.map(makeSetupId(variant, _))
