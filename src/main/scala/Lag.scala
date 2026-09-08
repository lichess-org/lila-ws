package lila.ws

import com.github.blemale.scaffeine.Cache

import lila.ws.ipc.LilaIn

final class Lag(lilaRedis: Lila, groupedWithin: util.GroupedWithin)(using cacheApi: util.CacheApi):

  import Lag.*

  private val trustedStats: Cache[User.Id, TrustedMillis] =
    cacheApi.notLoadingSync[User.Id, TrustedMillis](65_536, "lag.trustedStats"):
      _.expireAfterWrite(1.hour).build[User.Id, TrustedMillis]()

  export trustedStats.getIfPresent as sessionLag

  private val clientReports = groupedWithin[(User.Id, Int)](256, 947.millis): lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))

  export clientReports.apply as recordClientLag

  def recordTrustedLag(millis: Int, userId: Option[User.Id]) =
    Monitor.lag.roundFrameLag(millis)
    userId.foreach: uid =>
      trustedStats.put(uid, nextTrusted(sessionLag(uid), millis))

object Lag:

  type TrustedMillis = Int

  private val trustedRiseFactor = 0.05f
  private val trustedFallFactor = 0.1f
  private val maxTrustedMillis = 5000

  /* Weighted running average of trusted pongs, feeding lag compensation.
   *
   * Reports are clamped, and the average falls twice as fast as it rises, so a
   * single spike can no longer inflate compensation for the rest of the hour.
   *
   * The step is rounded away from the average rather than truncated, so the
   * average keeps converging instead of stalling on sub-millisecond steps. It
   * still never moves past the reported value. */
  def nextTrusted(prev: Option[TrustedMillis], millis: Int): TrustedMillis =
    val capped = millis.atLeast(0).atMost(maxTrustedMillis)
    prev.fold(capped): avg =>
      val diff = capped - avg
      val step = diff * (if diff < 0 then trustedFallFactor else trustedRiseFactor)
      avg + (if diff < 0 then Math.floor(step) else Math.ceil(step)).toInt
