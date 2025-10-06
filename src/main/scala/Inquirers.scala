package lila.ws

final class Inquirers(mongo: Mongo)(using ec: Executor, cacheApi: util.CacheApi, scheduler: Scheduler):

  private val cache = cacheApi.notLoadingSync[User.Id, Boolean](32, "inquirers"):
    _.expireAfterWrite(5.minutes).build[User.Id, Boolean]()

  def contains(user: User.Id): Boolean =
    cache.underlying.getIfPresent(user) == true

  scheduler.scheduleAtFixedRate(10.seconds, 2.seconds) { () =>
    mongo.inquirers.foreach { users =>
      cache.putAll(users.view.map(_ -> true).toMap)
    }
  }
