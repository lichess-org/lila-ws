package lila.ws

import kamon.Kamon

object Monitor {

  val redisPublishTime = Kamon.timer("redis.publish.time").withoutTags
}
