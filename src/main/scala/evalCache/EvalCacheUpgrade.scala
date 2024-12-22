package lila.ws
package evalCache

import chess.format.UciPath
import play.api.libs.json.JsString
import scalalib.DebouncerFunction

import java.util.concurrent.ConcurrentHashMap

import lila.ws.ipc.ClientIn.EvalHit
import lila.ws.ipc.ClientOut.EvalGet
import lila.ws.util.ExpireCallbackMemo

/* Upgrades the user's eval when a better one becomes available,
 * by remembering the last evalGet of each socket member,
 * and listening to new evals stored.
 */
final private class EvalCacheUpgrade(using
    ec: Executor,
    scheduler: org.apache.pekko.actor.typed.Scheduler
):
  import EvalCacheUpgrade.*

  private val members       = ConcurrentHashMap[SriString, WatchingMember](4096)
  private val evals         = ConcurrentHashMap[SetupId, EvalState](1024)
  private val expirableSris = ExpireCallbackMemo[Sri](scheduler, 3 minutes, expire)

  private val debouncer = DebouncerFunction[SetupId](scheduler.scheduleOnce(5.seconds, _), 64)

  private val upgradeMon = Monitor.evalCache.single.upgrade

  def register(sri: Sri, e: EvalGet): Unit =
    Id
      .from(e.variant, e.fen)
      .foreach: entryId =>
        members.compute(
          sri.value,
          (_, prev) =>
            Option(prev).foreach: member =>
              unregisterEval(member.setupId, sri)
            val setupId = SetupId(entryId, e.multiPv)
            evals
              .compute(setupId, (_, eval) => Option(eval).fold(EvalState(Set(sri), Depth(0)))(_.addSri(sri)))
            WatchingMember(sri, setupId, e.path)
        )
        expirableSris.put(sri)

  def onEval(input: EvalCacheEntry.Input): Unit =
    (1 to input.eval.multiPv.value).foreach: multiPv =>
      val setupId = SetupId(input.id, MultiPv(multiPv))
      debouncer.push(setupId)(() => publishEval(setupId, input))

  private def publishEval(setupId: SetupId, input: EvalCacheEntry.Input) =
    val newEvalState = Option:
      evals.computeIfPresent(
        setupId,
        (_, stored) =>
          if stored.depth >= input.eval.depth then stored
          else stored.copy(depth = input.eval.depth)
      )
    newEvalState
      .filter(_.depth == input.eval.depth) // ensure the new one from input
      .foreach: eval =>
        val wms = eval.sris.withFilter(_ != input.sri).flatMap(sri => Option(members.get(sri.value)))
        if wms.nonEmpty then
          val evalJson = EvalCacheJsonHandlers.writeEval(input.eval, input.fen)
          wms
            .groupBy(_.path)
            .map: (path, members) =>
              val hit = EvalHit(evalJson + ("path" -> JsString(path.value)))
              members.foreach(m => Bus.publish(_.sri(m.sri), hit))
              upgradeMon.count.increment(wms.size)

  private def expire(sri: Sri): Unit =
    Option(members.remove(sri.value)).foreach: m =>
      unregisterEval(m.setupId, sri)

  private def unregisterEval(setupId: SetupId, sri: Sri): Unit =
    evals.computeIfPresent(
      setupId,
      (_, eval) =>
        val newSris = eval.sris - sri
        if newSris.isEmpty then null
        else eval.copy(sris = newSris)
    )

  scheduler.scheduleWithFixedDelay(1 minute, 1 minute): () =>
    upgradeMon.members.update(members.size)
    upgradeMon.evals.update(evals.size)
    upgradeMon.expirable.update(expirableSris.count)

private object EvalCacheUpgrade:

  type SriString = String

  case class SetupId(entryId: Id, multiPv: MultiPv)

  case class EvalState(sris: Set[Sri], depth: Depth):
    def addSri(sri: Sri) = copy(sris = sris + sri)

  case class WatchingMember(sri: Sri, setupId: SetupId, path: UciPath)
