package lila.ws
package evalCache

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }

import lila.ws.ipc.ClientOut.{ EvalGet, EvalGetMulti, EvalPut }
import lila.ws.ipc.ClientIn
import chess.format.Fen
import chess.ErrorStr
import play.api.libs.json.JsString
import com.typesafe.scalalogging.Logger
import cats.syntax.all.*
import reactivemongo.api.bson.BSONDocument
import java.time.LocalDateTime

final class EvalCacheApi(mongo: Mongo)(using
    Executor,
    org.apache.pekko.actor.typed.Scheduler
):

  private val truster = EvalCacheTruster(mongo)
  private val upgrade = EvalCacheUpgrade()
  private val multi   = EvalCacheMulti()

  import EvalCacheEntry.*
  import EvalCacheBsonHandlers.given

  def get(sri: Sri, e: EvalGet, emit: Emit[ClientIn]): Unit =
    getEntry(Id.make(e.variant, e.fen))
      .map:
        _.flatMap(_ makeBestMultiPvEval e.multiPv)
      .map(monitorRequest(e.fen, Monitor.evalCache.single))
      .foreach:
        _.foreach: eval =>
          emit:
            ClientIn.EvalHit:
              EvalCacheJsonHandlers.writeEval(eval, e.fen) + ("path" -> JsString(e.path.value))
    if e.up then upgrade.register(sri, e)

  def getMulti(sri: Sri, e: EvalGetMulti, emit: Emit[ClientIn]): Unit =
    e.fens
      .traverse: fen =>
        getEntry(Id.make(e.variant, fen))
          .map:
            _.flatMap(_.makeBestSinglePvEval).map(fen -> _)
          .map(monitorRequest(fen, Monitor.evalCache.multi))
      .map(_.flatten)
      .foreach: evals =>
        if evals.nonEmpty then
          emit:
            ClientIn.EvalHitMulti:
              EvalCacheJsonHandlers.writeMultiHit(evals)
    multi.register(sri, e)

  def put(sri: Sri, user: User.Id, e: EvalPut): Unit =
    truster
      .get(user) foreach:
      _.filter(_.isEnough) foreach: trust =>
        makeInput(
          e.variant,
          e.fen,
          Eval(
            pvs = e.pvs,
            knodes = e.knodes,
            depth = e.depth,
            by = user,
            trust = trust
          )
        ) foreach { putTrusted(sri, user, _) }

  private def monitorRequest[A](fen: Fen.Epd, mon: Monitor.evalCache.Style)(res: Option[A]): Option[A] =
    Fen
      .readPly(fen)
      .foreach: ply =>
        mon.request(ply.value, res.isDefined).increment()
    res

  private val cache: AsyncLoadingCache[Id, Option[EvalCacheEntry]] = Scaffeine()
    .initialCapacity(65_536)
    .expireAfterWrite(5 minutes)
    .buildAsyncFuture(fetchAndSetAccess)

  export cache.get as getEntry

  private def fetchAndSetAccess(id: Id): Future[Option[EvalCacheEntry]] =
    mongo.evalCacheEntry(id) map: res =>
      if res.isDefined then mongo.evalCacheUsedNow(id)
      res

  private def putTrusted(sri: Sri, user: User.Id, input: Input): Future[Unit] =
    def destSize(fen: Fen.Epd): Int =
      chess.Game(chess.variant.Standard.some, fen.some).situation.moves.view.map(_._2.size).sum
    mongo.evalCacheColl.flatMap: c =>
      EvalCacheValidator(input) match
        case Left(error) =>
          Logger("EvalCacheApi.put").info(s"Invalid from ${user} $error ${input.fen}")
          Future.successful(())
        case _ =>
          getEntry(input.id) flatMap:
            case None =>
              val entry = EvalCacheEntry(
                _id = input.id,
                nbMoves = destSize(input.fen),
                evals = List(input.eval),
                usedAt = LocalDateTime.now,
                updatedAt = LocalDateTime.now
              )
              c.insert
                .one(entry)
                .recover(mongo.ignoreDuplicateKey)
                .map: _ =>
                  cache.put(input.id, Future.successful(entry.some))
                  upgrade.onEval(input, sri)
                  multi.onEval(input, sri)
            case Some(oldEntry) =>
              val entry = oldEntry add input.eval
              if entry.similarTo(oldEntry) then Future.successful(())
              else
                c.update
                  .one(BSONDocument("_id" -> entry.id), entry, upsert = true)
                  .map: _ =>
                    cache.put(input.id, Future.successful(entry.some))
                    upgrade.onEval(input, sri)
                    multi.onEval(input, sri)

private object EvalCacheValidator:

  def apply(in: EvalCacheEntry.Input): Either[ErrorStr, Unit] =
    in.eval.pvs.traverse_(pv => chess.Replay.boardsFromUci(pv.moves.value.toList, in.fen.some, in.id.variant))
