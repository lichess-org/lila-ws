package lila.ws

import scala.concurrent.duration._
import lila.ws.ipc.LilaIn

final class Lag(
    lilaRedis: Lila,
    groupedWithin: util.GroupedWithin
) {

  private val clientReports = groupedWithin[(User.ID, Int)](128, 947.millis) { lags =>
    lilaRedis.emit.site(LilaIn.Lags(lags.toMap))
  }

  def siteClientReport = clientReports.apply _

  def trustedLag(millis: Int) = Monitor.lag.roundFrameLag(millis)
}
