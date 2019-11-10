package lila.ws

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import javax.inject._
import play.api.libs.json.{ Json, OWrites }
import reactivemongo.api.bson._
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
            name <- doc.getAsOpt[String]("username")
            title = doc.getAsOpt[String]("title")
          } yield LightUser(name, title)
        }
    }
}
