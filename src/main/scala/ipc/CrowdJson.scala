package lila.ws
package ipc

import com.github.blemale.scaffeine.AsyncLoadingCache
import play.api.libs.json.*

final class CrowdJson(inquirers: Inquirers, mongo: Mongo, lightUserApi: LightUserApi)(using
    ec: Executor,
    cacheApi: lila.ws.util.CacheApi
):

  def room(crowd: RoomCrowd.Output): Future[ClientIn.Crowd] = {
    if crowd.users.sizeIs > 20 then
      keepOnlyStudyMembers(crowd).map: users =>
        crowd.copy(users = users, anons = 0)
    else Future.successful(crowd)
  }.flatMap: withFewUsers =>
    roomSpectatorsOf(withFewUsers, crowd.users).map: json =>
      ClientIn.Crowd.make(json, withFewUsers.members, withFewUsers.users)

  def round(crowd: RoundCrowd.Output): Future[ClientIn.Crowd] =
    roundSpectatorsOf(crowd).map: spectators =>
      ClientIn.Crowd.make(
        Json
          .obj(
            "white" -> (crowd.players.white > 0),
            "black" -> (crowd.players.black > 0),
            "watchers" -> spectators
          ),
        crowd.size,
        Nil
      )

  private def roomSpectatorsOf(crowd: RoomCrowd.Output, allUsers: Iterable[User.Id]): Future[JsObject] =
    if crowd.users.isEmpty then Future.successful(Json.obj("nb" -> crowd.members))
    else
      Future.traverse(crowd.users.filterNot(inquirers.contains))(lightUserApi.get).map { names =>
        val base = Json.obj(
          "nb" -> crowd.members,
          "users" -> names.filterNot(isBotName)
        ) ++ (if crowd.anons > 0 then Json.obj("anons" -> crowd.anons) else Json.obj())
        val streamers = Streamer.intersect(allUsers)
        if streamers.isEmpty then base else base ++ Json.obj("streams" -> Json.toJson(streamers))
      }

  private def roundSpectatorsOf(crowd: RoundCrowd.Output): Future[JsObject] =
    if crowd.users.isEmpty then Future.successful(JsObject.empty)
    else if crowd.size > 10 then Future.successful(Json.obj("nb" -> crowd.size))
    else
      Future.traverse(crowd.users.filterNot(inquirers.contains))(lightUserApi.get).map { names =>
        val base = Json.obj("users" -> names.filterNot(isBotName))
        val streamers = Streamer.intersect(crowd.users)
        if streamers.isEmpty then base else base ++ Json.obj("streams" -> Json.toJson(streamers))
      }

  private def isBotName(name: User.TitleName) = name.value.startsWith("BOT ")

  private val isStudyCache: AsyncLoadingCache[Study.Id, Boolean] = cacheApi(32, "crowdJson.isStudy"):
    _.expireAfterWrite(20.minutes).buildAsyncFuture(mongo.studyExists)

  private def keepOnlyStudyMembers(crowd: RoomCrowd.Output): Future[Iterable[User.Id]] =
    isStudyCache
      .get(crowd.roomId.into(Study.Id))
      .flatMap:
        case false => Future.successful(Nil)
        case true => mongo.studyMembers(crowd.roomId.into(Study.Id)).map(crowd.users.toSet.intersect)
