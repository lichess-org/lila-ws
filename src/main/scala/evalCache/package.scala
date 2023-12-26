package lila.ws
package evalCache

import cats.data.NonEmptyList
import chess.format.{ Fen, Uci }
import chess.variant.Variant

val MIN_KNODES   = Knodes(3000)
val MIN_DEPTH    = Depth(20)
val MIN_PV_SIZE  = 6
val MAX_PV_SIZE  = 10
val MAX_MULTI_PV = MultiPv(5)

opaque type Knodes = Int
object Knodes extends OpaqueInt[Knodes]:
  extension (a: Knodes)
    def intNodes: Int =
      val nodes = a.value * 1000d
      if nodes.toInt == nodes then nodes.toInt
      else Integer.MAX_VALUE

opaque type Moves = NonEmptyList[Uci]
object Moves extends TotalWrapper[Moves, NonEmptyList[Uci]]:
  extension (a: Moves) def truncate: Moves = NonEmptyList(a.value.head, a.value.tail.take(MAX_PV_SIZE - 1))

opaque type Trust = Double
object Trust extends OpaqueDouble[Trust]:
  extension (a: Trust) def isEnough = a > -1

opaque type SmallFen = String
object SmallFen extends OpaqueString[SmallFen]:
  def make(variant: Variant, fen: Fen.Simple): SmallFen =
    val base = fen.value.split(' ').take(4).mkString("").filter { c =>
      c != '/' && c != '-' && c != 'w'
    }
    if variant == chess.variant.ThreeCheck
    then fen.value.split(' ').lift(6).foldLeft(base)(_ + _)
    else base
  def validate(variant: Variant, fen: Fen.Epd): Option[SmallFen] =
    Fen.read(variant, fen).exists(_ playable false) option make(variant, fen.simple)
