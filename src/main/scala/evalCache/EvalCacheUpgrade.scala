package lila.ws
package evalCache

import play.api.libs.json.JsString

import chess.format.{ Fen, UciPath }
import chess.variant.Variant
import lila.ws.util.ExpireCallbackMemo
import lila.ws.ipc.ClientOut.EvalGet
import lila.ws.ipc.ClientIn.EvalHit

import scala.collection.mutable

/* Upgrades the user's eval when a better one becomes available,
 * by remembering the last evalGet of each socket member,
 * and listening to new evals stored.
 */
final private class EvalCacheUpgrade(using
    ec: Executor,
    scheduler: org.apache.pekko.actor.typed.Scheduler
):
  import EvalCacheUpgrade.*

  private val members       = mutable.AnyRefMap.empty[SriString, WatchingMember]
  private val evals         = mutable.AnyRefMap.empty[SetupId, EvalState]
  private val expirableSris = ExpireCallbackMemo[Sri](scheduler, 10 minutes, expire)

  private val upgradeMon = Monitor.evalCache.single.upgrade

  def register(sri: Sri, e: EvalGet): Unit =
    members get sri.value foreach: wm =>
      unregisterEval(wm.setupId, sri)
    val setupId = makeSetupId(e.variant, e.fen, e.multiPv)
    members += (sri.value -> WatchingMember(sri, setupId, e.path))
    evals += (setupId     -> evals.get(setupId).fold(EvalState(Set(sri), Depth(0)))(_ addSri sri))
    expirableSris put sri

  def onEval(input: EvalCacheEntry.Input, fromSri: Sri): Unit =
    (1 to input.eval.multiPv.value) flatMap { multiPv =>
      val setupId = makeSetupId(input.id.variant, input.fen, MultiPv(multiPv))
      evals get setupId map (setupId -> _)
    } filter {
      _._2.depth < input.eval.depth
    } foreach { (setupId, eval) =>
      evals += (setupId -> eval.copy(depth = input.eval.depth))
      val wms = eval.sris.withFilter(_ != fromSri) flatMap { sri => members.get(sri.value) }
      if wms.nonEmpty then
        val evalJson = EvalCacheJsonHandlers.writeEval(input.eval, input.fen)
        wms.groupBy(_.path) map: (path, wms) =>
          val hit = EvalHit(evalJson + ("path" -> JsString(path.value)))
          wms foreach { wm => Bus.publish(_ sri wm.sri, hit) }
        upgradeMon.count.increment(wms.size)
    }

  private def expire(sri: Sri): Unit =
    members get sri.value foreach { wm =>
      unregisterEval(wm.setupId, sri)
      members -= sri.value
    }

  private def unregisterEval(setupId: SetupId, sri: Sri): Unit =
    evals get setupId foreach: eval =>
      val newSris = eval.sris - sri
      if newSris.isEmpty then evals -= setupId
      else evals += (setupId -> eval.copy(sris = newSris))

  scheduler.scheduleWithFixedDelay(1 minute, 1 minute): () =>
    upgradeMon.members.update(members.size)
    upgradeMon.evals.update(evals.size)
    upgradeMon.expirable.update(expirableSris.count)

private object EvalCacheUpgrade:

  type SriString = String
  type SetupId   = String

  case class EvalState(sris: Set[Sri], depth: Depth):
    def addSri(sri: Sri) = copy(sris = sris + sri)

  def makeSetupId(variant: Variant, fen: Fen.Epd, multiPv: MultiPv): SetupId =
    s"${variant.id}${SmallFen.make(variant, fen.simple)}^$multiPv"

  case class WatchingMember(sri: Sri, setupId: SetupId, path: UciPath)
