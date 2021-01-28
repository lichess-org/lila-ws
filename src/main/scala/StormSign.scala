package lila.ws

import com.roundeights.hasher.Algo
import com.typesafe.config.Config

final class StormSign(config: Config) {

  private val signer = Algo hmac config.getString("storm.secret")

  def apply(key: String): String = signer sha1 key
}
