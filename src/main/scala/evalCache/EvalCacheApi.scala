package lila.ws
package evalCache

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import scala.concurrent.duration.*

import scala.concurrent.{ ExecutionContext, Future }
import lila.ws.ipc.ClientOut.EvalGet
import chess.variant.Variant
import chess.format.Fen
import play.api.libs.json.JsObject
import org.joda.time.DateTime

final class EvalCacheApi(mongo: Mongo)(using ExecutionContext):

  import EvalCacheEntry.*
  import EvalCacheBsonHandlers.given

  def get(e: EvalGet) =
    println(e)

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
