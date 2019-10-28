package lila.ws
package ipc

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import javax.inject._
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

import LightUser._

@Singleton
final class CrowdJson @Inject() (
    mongo: Mongo,
    lightUserApi: LightUserApi
)(implicit ec: ExecutionContext) {

  def apply(in: RoomCrowd.Output): Future[Bus.Msg] = {
    if (in.users.size > 20) keepOnlyStudyMembers(in) map { users =>
      in.copy(users = users, anons = 0)
    }
    else Future successful in
  } flatMap { crowd =>
    if (crowd.users.isEmpty) Future successful Json.obj("nb" -> crowd.members)
    else {
      Future sequence { crowd.users map lightUserApi.get } map { lights =>
        Json.obj(
          "nb" -> crowd.members,
          "users" -> lights.flatMap(_.map(_.titleName)),
          "anons" -> crowd.anons
        )
      }
    }
  } map ClientIn.Crowd.apply map { Bus.msg(_, _ room in.roomId) }

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
