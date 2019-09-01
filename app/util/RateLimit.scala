package lila.ws

import com.github.blemale.scaffeine.{ Cache, Scaffeine }
import play.api.Logger
import scala.concurrent.duration.Duration

/**
 * side effect throttler that allows X ops per Y unit of time
 */
final class RateLimit[K](
    credits: Int,
    duration: Duration,
    name: String,
    enforce: Boolean = true,
    log: Boolean = true
) {
  import RateLimit._

  private val storage = Scaffeine()
    .expireAfterWrite(duration)
    .build[K, (Cost, ClearAt)]()

  private def makeClearAt = nowMillis + duration.toMillis

  private val logger = Logger(name)

  logger.info(s"[start] $name ($credits/$duration)")

  def apply[A](k: K, default: A, cost: Cost = 1, msg: => String = "")(op: => A): A =
    storage getIfPresent k match {
      case None =>
        storage.put(k, cost -> makeClearAt)
        op
      case Some((a, clearAt)) if a <= credits =>
        storage.put(k, (a + cost) -> clearAt)
        op
      case Some((_, clearAt)) if nowMillis > clearAt =>
        storage.put(k, cost -> makeClearAt)
        op
      case _ if enforce =>
        if (log) logger.info(s"$name ($credits/$duration) $k cost: $cost $msg")
        default
      case _ =>
        op
    }
}

object RateLimit {

  type Charge = Cost => Unit
  type Cost = Int

  private type ClearAt = Long

  private def nowMillis = System.currentTimeMillis()

  import akka.stream._
  import akka.stream.scaladsl._

  def flow[K, A](limiter: RateLimit[K], key: K): Flow[A, A, _] =
    Flow[A].collect {
      case a if limiter(key, false, msg = a.toString)(true) => a
    }
}
