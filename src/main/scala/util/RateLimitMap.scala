package lila.ws

import scala.concurrent.duration.FiniteDuration
import com.github.blemale.scaffeine.Scaffeine

/** Throttler that allows X operations per Y unit of time Not thread safe
  */
final class RateLimitMap(
    name: String,
    credits: Int,
    duration: FiniteDuration,
    enforce: Boolean = true,
    log: Boolean = true
) {
  import RateLimit._

  private type ClearAt = Long

  private val storage = Scaffeine()
    .expireAfterWrite(duration)
    .build[String, (Cost, ClearAt)]()

  private def makeClearAt = nowMillis + duration.toMillis

  def apply(k: String, cost: Cost = 1, msg: => String = ""): Boolean = cost < 1 || {
    storage getIfPresent k match {
      case None =>
        storage.put(k, cost -> makeClearAt)
        true
      case Some((a, clearAt)) if a < credits =>
        storage.put(k, (a + cost) -> clearAt)
        true
      case Some((_, clearAt)) if nowMillis > clearAt =>
        storage.put(k, cost -> makeClearAt)
        true
      case _ if enforce =>
        if (log) logger.info(s"$name $credits/$duration $k cost: $cost $msg")
        false
      case _ => true
    }
  }
}
