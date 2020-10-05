package lila.ws
package util

import java.security.SecureRandom
import java.util.concurrent.ThreadLocalRandom.{ current => local }

object Util {

  def nowSeconds: Int = (System.currentTimeMillis() / 1000).toInt

  object random {

    private val secureRandom = new SecureRandom()
    private val chars        = (('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z')).mkString
    private val nbChars      = chars.length

    def char: Char = chars(secureRandom nextInt nbChars)

    def string(len: Int): String = new String(Array.fill(len)(char))

    def shuffle[T, C](xs: IterableOnce[T])(implicit bf: scala.collection.BuildFrom[xs.type, T, C]): C =
      new scala.util.Random(local).shuffle(xs)
  }
}
