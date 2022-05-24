package lila.ws
package util

import java.security.SecureRandom
import java.util.concurrent.ThreadLocalRandom.current as local

object Util:

  def nowSeconds: Int = (System.currentTimeMillis() / 1000).toInt

  object secureRandom:

    private val secureRandom = new SecureRandom()
    private val chars        = (('0' to '9') ++ ('a' to 'z') ++ ('A' to 'Z')).mkString
    private val nbChars      = chars.length

    def char: Char = chars(secureRandom nextInt nbChars)

    def string(len: Int): String = new String(Array.fill(len)(char))

    def shuffle[T, C](xs: IterableOnce[T])(using bf: scala.collection.BuildFrom[xs.type, T, C]): C =
      new scala.util.Random(local).shuffle(xs)

  object threadLocalRandom:

    import java.util.concurrent.ThreadLocalRandom.current

    def nextInt(): Int       = current.nextInt()
    def nextInt(n: Int): Int = current.nextInt(n)
    def nextChar(): Char = {
      val i = nextInt(62)
      if (i < 26) i + 65
      else if (i < 52) i + 71
      else i - 4
    }.toChar

    def nextString(len: Int): String =
      val sb = new StringBuilder(len)
      for (_ <- 0 until len) sb += nextChar()
      sb.result()

    def shuffle[T, C](xs: IterableOnce[T])(using bf: scala.collection.BuildFrom[xs.type, T, C]): C =
      new scala.util.Random(current).shuffle(xs)

  val startedAtMillis = System.currentTimeMillis()
