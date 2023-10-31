package lila.ws

import com.github.blemale.scaffeine.Scaffeine
import org.apache.pekko.actor.typed.Scheduler

final class Inquirers(mongo: Mongo)(using ec: Executor, scheduler: Scheduler):

  private val cache = Scaffeine()
    .expireAfterWrite(5 minutes)
    .build[User.Id, Boolean]()

  def contains(user: User.Id): Boolean =
    cache.underlying.getIfPresent(user) == true

  scheduler.scheduleAtFixedRate(10 seconds, 2 seconds) { () =>
    mongo.inquirers foreach { users =>
      cache putAll users.view.map(_ -> true).toMap
    }
  }
