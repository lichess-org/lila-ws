package lila.ws

import scala.util.control.NoStackTrace
import scala.util.Success
import com.typesafe.scalalogging.Logger
import com.github.blemale.scaffeine.Scaffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import scala.concurrent.duration.*
import play.api.libs.json.*
import chess.format.Fen
import scala.concurrent.{ Future, Promise }

import lila.ws.ipc.ClientOut.EvalGet
import scala.concurrent.ExecutionContext

// Caches EvalGet client requests, sends only one to lila, then notifies all clients
final class EvalCache(using ExecutionContext):

  import EvalCache.{ *, given }

  private val cache = Scaffeine()
    .expireAfterWrite(2.seconds)
    .removalListener((key: EvalKey, promise: Promise[JsObject], cause: RemovalCause) =>
      if !promise.isCompleted then promise.failure(EvalGetTimeout)
    )
    .build[EvalKey, Promise[JsObject]]()

  def get(e: EvalGet, lilaIn: Emit[ipc.LilaIn.Site])(clientOut: Emit[JsObject]): Unit =
    cache
      .get(
        keyOf(e),
        key => {
          lilaIn(ipc.LilaIn.TellSri(sri, None, Json.obj("t" -> "evalGet", "d" -> e)))
          Promise[JsObject]
        }
      )
      .future
      .foreach(clientOut)

  def hit(json: JsonString): Unit =
    Json.parse(json.value) match
      case o: JsObject =>
        o.get[EvalHit]("d") foreach { hit =>
          cache.getIfPresent(keyOf(hit)) foreach { promise =>
            promise.complete(Success(o))
          }
        }
      case _ =>

  private val logger = Logger(getClass)

object EvalCache:

  given Format[EvalGet] = Json.format
  given Format[EvalHit] = Json.format

  opaque type EvalKey = String

  case class EvalHit(fen: Fen.Epd, path: String, pvs: JsArray)

  val sri = Sri("lila_ws_sri")

  private def keyOf(e: EvalGet): EvalKey = s"${e.fen} ${e.path} ${e.mpv}"
  private def keyOf(e: EvalHit): EvalKey = s"${e.fen} ${e.path} ${e.pvs.value.size}"

  private val EvalGetTimeout = new Exception with NoStackTrace:
    override def getMessage = "evalGet eviction eviction of non-completed promise"
