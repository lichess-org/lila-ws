package lila.ws

import com.typesafe.config.{ Config, ConfigFactory }

object Configuration {

  private val conf: Config = ConfigFactory.load()

  val port: Int = conf.getInt("port")
  val mongoUri: String = conf.getString("mongo.uri")
  val redisUri: String = conf.getString("redis.uri")
}
