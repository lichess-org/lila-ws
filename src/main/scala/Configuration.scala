package lila.ws

import com.typesafe.config.{ Config, ConfigFactory }

object Configuration {

  private val conf: Config = ConfigFactory.load()

  val bindHost: String = conf.getString("bind.host")
  val bindPort: Int = conf.getInt("bind.port")
  val mongoUri: String = conf.getString("mongo.uri")
  val redisUri: String = conf.getString("redis.uri")
}
