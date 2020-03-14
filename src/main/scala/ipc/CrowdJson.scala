package lila.ws
package ipc

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import play.api.libs.json._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import lila.ws.util.LilaJsObject.augment

final class CrowdJson(
    mongo: Mongo,
    lightUserApi: LightUserApi
)(implicit ec: ExecutionContext) {

  def room(crowd: RoomCrowd.Output): Future[ClientIn.Crowd] = {
    if (crowd.users.size > 20) keepOnlyStudyMembers(crowd) map { users =>
      crowd.copy(users = users, anons = 0)
    }
    else Future successful crowd
  } flatMap spectatorsOf map ClientIn.Crowd.apply

  def round(crowd: RoundCrowd.Output): Future[ClientIn.Crowd] =
    spectatorsOf(
      crowd.room.copy(
        users = if (crowd.room.users.size > 20) Nil else crowd.room.users
      )
    ) map { spectators =>
      ClientIn.Crowd(
        Json
          .obj(
            "white" -> (crowd.players.white > 0),
            "black" -> (crowd.players.black > 0)
          )
          .add("watchers" -> (if (crowd.room.users.nonEmpty) Some(spectators) else None))
      )
    }

  private def spectatorsOf(crowd: RoomCrowd.Output): Future[JsObject] =
    if (crowd.users.isEmpty) Future successful Json.obj("nb" -> crowd.members)
    else
      Future.traverse(crowd.users)(lightUserApi.get) map { names =>
        Json.obj(
          "nb"    -> crowd.members,
          "users" -> names.filterNot(isBotName),
          "anons" -> crowd.anons
        )
      }

  private def isBotName(str: String) = str startsWith "BOT "

  private val isStudyCache: AsyncLoadingCache[String, Boolean] =
    Scaffeine()
      .expireAfterWrite(20.minutes)
      .buildAsyncFuture(mongo.studyExists)

  private def keepOnlyStudyMembers(crowd: RoomCrowd.Output): Future[Iterable[User.ID]] =
    isStudyCache.get(crowd.roomId.value) flatMap {
      case false => Future successful Nil
      case true  => mongo.studyMembers(crowd.roomId.value) map crowd.users.toSet.intersect
    }
}
