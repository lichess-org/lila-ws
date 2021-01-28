package lila.ws

import com.roundeights.hasher.Algo
import com.typesafe.config.Config

final class StormSign(config: Config) {

  private val signer = Algo hmac config.getString("storm.secret")

  def apply(key: String, user: User): String = signer sha1 s"$key:${user.id}"
}
