package lila.ws

import com.github.blemale.scaffeine.Scaffeine
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext
import akka.actor.typed.Scheduler

final class Inquirers(mongo: Mongo, lightUserApi: LightUserApi)(using
    ec: ExecutionContext,
    scheduler: Scheduler
):

  private val cache = Scaffeine()
    .expireAfterWrite(5 minutes)
    .build[UserId, Boolean]()

  def contains(user: UserId): Boolean =
    cache.underlying.getIfPresent(user) == true

  scheduler.scheduleAtFixedRate(10 seconds, 2 seconds) { () =>
    mongo.inquirers foreach { users =>
      cache putAll users.view.map(_ -> true).toMap
    }
  }
