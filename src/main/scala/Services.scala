package lila.ws

import scala.concurrent.duration._

import ipc.LilaIn

final class Services(
    lilaRedis: Lila,
    groupedWithin: util.GroupedWithin,
    val users: Users,
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
  val studyDoor = groupedWithin[ThroughStudyDoor](64, 1931.millis) { throughs =>
    lila.study(LilaIn.StudyDoor {
      throughs.foldLeft(Map.empty[User.ID, Either[RoomId, RoomId]]) {
        case (doors, ThroughStudyDoor(user, through)) => doors + (user.id -> through)
      }
    })
  }
  val challengePing = groupedWithin[RoomId](20, 2.seconds) { ids =>
    lila.challenge(LilaIn.ChallengePings(ids.distinct))
  }
}
