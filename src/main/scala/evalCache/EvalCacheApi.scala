package lila.ws
package evalCache

import cats.syntax.all.*
import chess.ErrorStr
import chess.format.Fen
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import com.typesafe.scalalogging.Logger
import play.api.libs.json.JsString
import reactivemongo.api.bson.BSONDocument

import java.time.LocalDateTime

import lila.ws.ipc.ClientIn
import lila.ws.ipc.ClientOut.{ EvalGet, EvalGetMulti, EvalPut }

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
    Id.from(e.variant, e.fen)
      .foreach: id =>
        getEntry(id)
          .map:
            _.flatMap(_.makeBestMultiPvEval(e.multiPv))
          .map(monitorRequest(e.fen, Monitor.evalCache.single))
          .foreach:
            _.foreach: eval =>
              emit:
                ClientIn.EvalHit:
                  EvalCacheJsonHandlers.writeEval(eval, e.fen) + ("path" -> JsString(e.path.value))
    if e.up then upgrade.register(sri, e)

  def getMulti(sri: Sri, e: EvalGetMulti, emit: Emit[ClientIn]): Unit =
    e.fens
      .flatMap(fen => Id.from(e.variant, fen).map(fen -> _))
      .traverse: (fen, id) =>
        getEntry(id)
          .map:
            _.flatMap(_.makeBestSinglePvEval).map(fen -> _)
          .map(monitorRequest(fen, Monitor.evalCache.multi))
      .map(_.flatten)
      .foreach: evals =>
        if evals.nonEmpty then
          emit:
            ClientIn.EvalHitMulti:
              EvalCacheJsonHandlers.writeMultiHit(evals)
    multi.register(sri, e.copy(fens = e.fens))

  def put(sri: Sri, user: User.Id, e: EvalPut): Unit =
    if isStorableDepth(e.depth)
    then
      truster
        .get(user)
        .foreach:
          _.filter(_.isEnough).foreach: trust =>
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
            ).foreach(putTrusted(sri, user, _))

  // reduce the number of evals stored and,
  // perhaps more importantly, distributed to subscribers
  private def isStorableDepth(depth: Depth) =
    if depth < 20 then false
    else if depth < 30 then true
    else if depth < 50 then depth.value % 2 == 0
    else depth.value                    % 5 == 0

  private def monitorRequest[A](fen: Fen.Full, mon: Monitor.evalCache.Style)(res: Option[A]): Option[A] =
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
    mongo
      .evalCacheEntry(id)
      .map: res =>
        if res.isDefined then mongo.evalCacheUsedNow(id)
        res

  private def putTrusted(sri: Sri, user: User.Id, input: Input): Future[Unit] =
    mongo.evalCacheColl.flatMap: c =>
      validate(input).match
        case Left(error) =>
          Logger("EvalCacheApi.put").info(s"Invalid from ${user} $error ${input.fen}")
          Future.successful(())
        case _ =>
          getEntry(input.id).flatMap:
            case None =>
              val entry = EvalCacheEntry(
                _id = input.id,
                nbMoves = input.situation.moves.view.map(_._2.size).sum,
                evals = List(input.eval),
                usedAt = LocalDateTime.now,
                updatedAt = LocalDateTime.now
              )
              c.insert
                .one(entry)
                .recover(mongo.ignoreDuplicateKey)
                .map: _ =>
                  afterPut(input, sri, entry)
            case Some(oldEntry) =>
              val entry = oldEntry.add(input.eval)
              if entry.similarTo(oldEntry) then Future.successful(())
              else
                c.update
                  .one(BSONDocument("_id" -> entry.id), entry, upsert = true)
                  .map: _ =>
                    afterPut(input, sri, entry)

  private def afterPut(input: Input, sri: Sri, entry: EvalCacheEntry): Unit =
    cache.put(input.id, Future.successful(entry.some))
    // todo: debounce upgrades in hot rooms
    upgrade.onEval(input, sri)
    multi.onEval(input, sri)

  private def validate(in: EvalCacheEntry.Input): Either[ErrorStr, Unit] =
    in.eval.pvs.traverse_ { pv =>
      chess.Replay.boardsFromUci(pv.moves.value.toList, in.fen.some, in.situation.variant)
    }
