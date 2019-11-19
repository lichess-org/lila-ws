package lila.ws
package ipc

import chess.Color
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import javax.inject._
import play.api.libs.json._
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

import LightUser._
import lila.ws.util.LilaJsObject.augment

@Singleton
final class CrowdJson @Inject() (
    mongo: Mongo,
    lightUserApi: LightUserApi
)(implicit ec: ExecutionContext) {

  def room(roomId: RoomId, members: Int, users: Iterable[User.ID], anons: Int): Future[ClientIn.Crowd] = {
    if (crowd.users.size > 20) keepOnlyStudyMembers(crowd) map { users =>
      crowd.copy(users = users, anons = 0)
    }
    else Future successful crowd
  } flatMap spectatorsOf map ClientIn.Crowd.apply

  def round(roomId: RoomId, members: Int, users: Iterable[User.ID], anons: Int, players: Color.Map[Int]): Future[ClientIn.Crowd] =
    spectatorsOf(
      members,
      users = if (crowd.room.users.size > 20) Nil else crowd.room.users,
      anons
    ) map { spectators =>
      ClientIn.Crowd(
        Json.obj(
          "white" -> (players.white > 0),
          "black" -> (players.black > 0)
        ).add("watchers" -> (if (users.nonEmpty) Some(spectators) else None))
      )
    }

  private def spectatorsOf(members: Int, users: Iterable[User.ID], anons: Int): Future[JsObject] =
    if (users.isEmpty) Future successful Json.obj("nb" -> members)
    else Future sequence { users map lightUserApi.get } map { lights =>
      Json.obj(
        "nb" -> members,
        "users" -> lights.flatMap(_.map(_.titleName)),
        "anons" -> anons
      )
    }

  private val isStudyCache: AsyncLoadingCache[String, Boolean] =
    Scaffeine()
      .expireAfterWrite(20.minutes)
      .buildAsyncFuture(mongo.studyExists)

  private def keepOnlyStudyMembers(crowd: RoomCrowd.Output): Future[Iterable[User.ID]] =
    isStudyCache.get(crowd.roomId.value) flatMap {
      case false => Future successful Nil
      case true => mongo.studyMembers(crowd.roomId.value) map crowd.users.toSet.intersect
    }
}
