package lila.ws

import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.blemale.scaffeine.Scaffeine
import com.typesafe.scalalogging.Logger

import java.util.concurrent.atomic.AtomicInteger

// send a request to lila and await a response
object LilaRequest:

  private val counter = AtomicInteger(0)

  private val inFlight = Scaffeine()
    .expireAfterWrite(10.seconds)
    .removalListener: (id, _, cause) =>
      if cause != RemovalCause.EXPLICIT then
        logger.warn(s"$id removed: $cause")
        Monitor.redis.request.expired(cause.toString)
    .build[Int, Promise[String]]()
  private val asMap = inFlight.asMap()

  def send[R](sendReq: Int => Unit, readRes: String => R)(using Executor): Future[R] =
    val id = counter.getAndIncrement()
    sendReq(id)
    val promise = Promise[String]()
    inFlight.put(id, promise)
    Monitor.redis.request.send()
    promise.future.map(readRes)

  def onResponse(reqId: Int, response: String): Unit =
    asMap.remove(reqId).foreach(_.success(response))

  def monitorInFlight() =
    Monitor.redis.request.inFlight(inFlight.estimatedSize().toInt)

  private val logger = Logger(getClass)
