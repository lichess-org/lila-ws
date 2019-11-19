package lila.ws

import javax.inject._
import scala.concurrent.duration._

import ipc.LilaIn

@Singleton
final class Services @Inject() (
    lilaRedis: Lila,
    groupedWithin: GroupedWithin,
    val users: Users,
    val fens: Fens,
    val roomCrowd: RoomCrowd,
    val roundCrowd: RoundCrowd,
    val keepAlive: KeepAlive,
    val lobby: Lobby
) {

  def lila = lilaRedis.emit

  val lag = groupedWithin[(User.ID, Int)](128, 947.millis) { lags =>
    lila.site(LilaIn.Lags(lags.toMap))
  }
  val notified = groupedWithin[User.ID](40, 1001.millis) { userIds =>
    lila.site(LilaIn.NotifiedBatch(userIds))
  }
  val friends = groupedWithin[User.ID](10, 521.millis) { userIds =>
    lila.site(LilaIn.FriendsBatch(userIds))
  }
}
