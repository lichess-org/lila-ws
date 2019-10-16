package lila.ws

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import javax.inject._
import play.api.libs.json.{ Json, OWrites }
import reactivemongo.bson._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import lila.ws.util.LilaJsObject.augment

case class LightUser(
    name: String,
    title: Option[String]
) {
  def id = name.toLowerCase
  def titleName = title.fold(name)(_ + " " + name)
}

object LightUser {

  // implicit val lightUserWrites = OWrites[LightUser] { u =>
  //   Json.obj(
  //     "id" -> u.id,
  //     "name" -> u.name
  //   ).add("title" -> u.title)
  // }

  def fallback(userId: String) = LightUser(
    name = userId,
    title = None
  )
}

@Singleton
final class LightUserApi @Inject() (mongo: Mongo)(implicit executionContext: ExecutionContext) {

  def get(id: User.ID): Future[Option[LightUser]] = cache get id

  private val cache: AsyncLoadingCache[User.ID, Option[LightUser]] =
    Scaffeine()
      .expireAfterWrite(15.minutes)
      .buildAsyncFuture(fetch)

  private def fetch(id: User.ID): Future[Option[LightUser]] =
    mongo.user {
      _.find(
        BSONDocument("_id" -> id),
        Some(BSONDocument("username" -> true, "title" -> true))
      ).one[BSONDocument] map { docOpt =>
          for {
            doc <- docOpt
            name <- doc.getAs[String]("username")
            title = doc.getAs[String]("title")
          } yield LightUser(name, title)
        }
    }
}
