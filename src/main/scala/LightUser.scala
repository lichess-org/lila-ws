package lila.ws

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import reactivemongo.api.bson.*
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import Mongo.given

final class LightUserApi(mongo: Mongo)(using executionContext: ExecutionContext):

  type TitleName = String

  def get(id: UserId): Future[TitleName] = cache get id

  private val cache: AsyncLoadingCache[UserId, TitleName] =
    Scaffeine()
      .initialCapacity(32768)
      .expireAfterWrite(15.minutes)
      .buildAsyncFuture(fetch)

  private def fetch(id: UserId): Future[TitleName] =
    mongo.user {
      _.find(
        BSONDocument("_id" -> id),
        Some(BSONDocument("username" -> true, "title" -> true))
      ).one[BSONDocument] map { docOpt =>
        val name =
          for {
            doc  <- docOpt
            name <- doc.getAsOpt[String]("username")
          } yield doc.getAsOpt[String]("title").fold(name)(_ + " " + name)
        name getOrElse id.userId
      }
    }
