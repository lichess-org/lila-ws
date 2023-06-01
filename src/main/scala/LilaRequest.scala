package lila.ws

import java.util.concurrent.atomic.AtomicInteger
import com.github.blemale.scaffeine.Scaffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.typesafe.scalalogging.Logger

// send a request to lila and await a response
object LilaRequest:

  private val counter = AtomicInteger(0)

  private val inFlight = Scaffeine()
    .expireAfterWrite(30.seconds)
    .removalListener: (id, _, cause) =>
      if cause != RemovalCause.EXPLICIT then logger.warn(s"$id removed: $cause")
    .build[Int, Promise[String]]()
  private val asMap = inFlight.asMap()

  def apply[R](sendReq: Int => Unit, readRes: String => R)(using Executor): Future[R] =
    val id = counter.getAndIncrement()
    sendReq(id)
    val promise = Promise[String]()
    inFlight.put(id, promise)
    promise.future map readRes

  def onResponse(reqId: Int, response: String): Unit =
    asMap.remove(reqId).foreach(_ success response)

  private val logger = Logger(getClass)
