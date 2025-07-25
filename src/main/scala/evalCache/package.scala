package lila.ws
package evalCache

import cats.data.NonEmptyList
import chess.format.Uci

val MIN_KNODES = Knodes(3000)
val MIN_DEPTH = Depth(20)
val MIN_PV_SIZE = 6
val MAX_PV_SIZE = 10
val MAX_MULTI_PV = MultiPv(5)

opaque type Knodes = Int
object Knodes extends RelaxedOpaqueInt[Knodes]:
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
