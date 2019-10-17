package lila.ws
package ipc

import javax.inject._
import play.api.libs.json.Json
import scala.concurrent.{ Future, ExecutionContext }

import LightUser._

@Singleton
final class CrowdJson @Inject() (lightUserApi: LightUserApi)(implicit ec: ExecutionContext) {

  def apply(crowd: sm.CrowdSM.RoomCrowd): Future[Bus.Msg] = {
    if (crowd.users.isEmpty) Future successful Json.obj("nb" -> crowd.members)
    else Future sequence {
      crowd.users map lightUserApi.get
    } map { users =>
      Json.obj(
        "nb" -> crowd.members,
        "users" -> users.flatMap(_.map(_.titleName)),
        "anons" -> crowd.anons
      )
    }
  } map ClientIn.Crowd.apply map { Bus.msg(_, _ room crowd.roomId) }
}
