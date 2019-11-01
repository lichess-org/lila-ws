package lila.ws
package ipc

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import javax.inject._
import play.api.libs.json._
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

import LightUser._

@Singleton
final class CrowdJson @Inject() (
    mongo: Mongo,
    lightUserApi: LightUserApi
)(implicit ec: ExecutionContext) {

  def room(crowd: RoomCrowd.Output): Future[Bus.Msg] = {
    if (crowd.users.size > 20) keepOnlyStudyMembers(crowd) map { users =>
      crowd.copy(users = users, anons = 0)
    }
    else Future successful crowd
  } flatMap spectatorsOf map ClientIn.Crowd.apply map { Bus.msg(_, _ room crowd.roomId) }

  def round(crowd: RoundCrowd.Output): Future[Bus.Msg] =
    spectatorsOf(crowd.room.copy(
      users = if (crowd.room.users.size > 20) Nil else crowd.room.users
    )) map { spectators =>
      Bus.msg(ClientIn.Crowd(Json.obj(
        "white" -> (crowd.players.white > 0),
        "black" -> (crowd.players.black > 0),
        "watchers" -> spectators
      )), _ room crowd.room.roomId)
    }

  private def spectatorsOf(crowd: RoomCrowd.Output): Future[JsObject] =
    if (crowd.users.isEmpty) Future successful Json.obj("nb" -> crowd.members)
    else Future sequence { crowd.users map lightUserApi.get } map { lights =>
      Json.obj(
        "nb" -> crowd.members,
        "users" -> lights.flatMap(_.map(_.titleName)),
        "anons" -> crowd.anons
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
