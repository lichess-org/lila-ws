package lila.ws

/** Throttler that allows X operations per Y unit of time Not thread safe
  */
final class RateLimitMap(
    name: String,
    credits: Int,
    duration: FiniteDuration,
    enforce: Boolean = true,
    log: Boolean = true
)(using cacheApi: util.CacheApi):

  import RateLimit.*

  private type ClearAt = Long

  private val storage = cacheApi.notLoadingSync[String, (Cost, ClearAt)](4096, s"rateLimit.$name"):
    _.expireAfterWrite(duration).build()

  private inline def makeClearAt = nowMillis + duration.toMillis

  def apply(k: String, cost: Cost = 1, msg: => String = ""): Boolean = cost < 1 || (
    storage.getIfPresent(k) match
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
        if log then logDedup(s"$name $credits/$duration $k cost: $cost $msg")
        Monitor.rateLimit(name)
        false
      case _ => true
  )

  private var lastLog = ""
  private def logDedup(msg: String) =
    if msg != lastLog then
      lastLog = msg
      logger.info(msg)
