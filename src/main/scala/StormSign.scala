package lila.ws

import com.roundeights.hasher.Algo
import com.typesafe.config.Config

final class StormSign(config: Config):

  private val signer = Algo hmac config.getString("storm.secret")

  def apply(key: String, pad: String): String = xor(signer sha1 key, pad)

  private def xor(msg: String, pad: String): String =
    msg.zip(pad).map { (x, y) => x ^ y }.map(_.toChar).mkString
