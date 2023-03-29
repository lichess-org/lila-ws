package lila.ws

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import reactivemongo.api.bson.*
import Mongo.given

final class LightUserApi(mongo: Mongo)(using Executor):

  export cache.get

  private val cache: AsyncLoadingCache[User.Id, User.TitleName] =
    Scaffeine()
      .initialCapacity(32768)
      .expireAfterWrite(15.minutes)
      .buildAsyncFuture(fetch)

  private def fetch(id: User.Id): Future[User.TitleName] =
    mongo.user:
      _.find(
        BSONDocument("_id" -> id),
        Some(BSONDocument("username" -> true, "title" -> true))
      ).one[BSONDocument] map { docOpt =>
        val name = for
          doc  <- docOpt
          name <- doc.getAsOpt[User.Name]("username")
        yield User.TitleName(name, doc.getAsOpt[User.Title]("title"))
        name getOrElse id.into(User.TitleName)
      }
