package lila.ws
package evalCache

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import scala.concurrent.duration.*

import scala.concurrent.{ ExecutionContext, Future }
import lila.ws.ipc.ClientOut.{ EvalGet, EvalPut }
import lila.ws.ipc.ClientIn
import chess.variant.Variant
import chess.format.Fen
import play.api.libs.json.{ JsObject, JsString }
import org.joda.time.DateTime
import com.typesafe.scalalogging.Logger
import cats.syntax.option.*
import reactivemongo.api.bson.BSONDocument

final class EvalCacheApi(mongo: Mongo)(using
    ExecutionContext,
    akka.actor.typed.Scheduler
):

  private val truster = EvalCacheTruster(mongo)
  private val upgrade = EvalCacheUpgrade()

  import EvalCacheEntry.*
  import EvalCacheBsonHandlers.given

  def get(sri: Sri, e: EvalGet, emit: Emit[ClientIn]): Unit =
    getEvalJson(e.variant, e.fen, e.multiPv).foreach {
      _.foreach { json =>
        emit(ClientIn.EvalHit(json + ("path" -> JsString(e.path.value))))
      }
    }
    if e.up then upgrade.register(sri, e)

  def put(sri: Sri, user: User.Id, e: EvalPut): Unit =
    truster.get(user) foreach {
      _ foreach { trust =>
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
      }
    }

  private def getEvalJson(variant: Variant, fen: Fen.Epd, multiPv: MultiPv): Future[Option[JsObject]] =
    getEval(Id(variant, SmallFen.make(variant, fen.simple)), multiPv) map {
      _.map { EvalCacheJsonHandlers.writeEval(_, fen) }
    } map { res =>
      Fen.readPly(fen) foreach { ply =>
        Monitor.evalCache.request(ply.value, res.isDefined).increment()
      }
      res
    }

  private val cache: AsyncLoadingCache[Id, Option[EvalCacheEntry]] = Scaffeine()
    .initialCapacity(65536)
    .expireAfterAccess(5 minutes)
    .buildAsyncFuture(fetchAndSetAccess)

  private def getEval(id: Id, multiPv: MultiPv): Future[Option[Eval]] =
    getEntry(id) map {
      _.flatMap(_ makeBestMultiPvEval multiPv)
    }

  private def getEntry(id: Id): Future[Option[EvalCacheEntry]] = cache get id

  private def fetchAndSetAccess(id: Id): Future[Option[EvalCacheEntry]] =
    mongo.evalCacheEntry(id) map { res =>
      if (res.isDefined) mongo.evalCacheUsedNow(id)
      res
    }

  private def putTrusted(sri: Sri, user: User.Id, input: Input): Future[Unit] =
    def destSize(fen: Fen.Epd): Int =
      chess.Game(chess.variant.Standard.some, fen.some).situation.moves.view.map(_._2.size).sum
    println(s"putting $input")
    mongo.evalCacheColl.flatMap { c =>
      EvalCacheValidator(input).pp("validation") match
        case Some(error) =>
          Logger("EvalCacheApi.put").info(s"Invalid from ${user} $error ${input.fen}")
          Future.successful(())
        case None =>
          getEntry(input.id) flatMap {
            case None =>
              val entry = EvalCacheEntry(
                _id = input.id,
                nbMoves = destSize(input.fen),
                evals = List(input.eval),
                usedAt = DateTime.now,
                updatedAt = DateTime.now
              )
              c.insert
                .one(entry)
                .recover(mongo.ignoreDuplicateKey)
                .map { _ =>
                  cache.put(input.id, Future.successful(entry.some))
                  upgrade.onEval(input, sri)
                }
            case Some(oldEntry) =>
              println(s"old $oldEntry")
              val entry = oldEntry add input.eval
              println(s"new $entry")
              if entry.similarTo(oldEntry) then Future.successful(())
              else
                println("updating!")
                c.update.one(BSONDocument("_id" -> entry.id), entry, upsert = true).map { _ =>
                  cache.put(input.id, Future.successful(entry.some))
                  upgrade.onEval(input, sri)
                }
          }
    }

private object EvalCacheValidator:

  case class Error(message: String) extends AnyVal

  def apply(in: EvalCacheEntry.Input): Option[Error] =
    in.eval.pvs.toList.foldLeft(none[Error]) {
      case (None, pv) =>
        chess.Replay
          .boardsFromUci(pv.moves.value.toList, in.fen.some, in.id.variant)
          .fold(err => Error(err).some, _ => none)
      case (error, _) => error
    }
