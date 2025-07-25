package lila.ws
package evalCache

import cats.data.NonEmptyList
import chess.eval.Score
import chess.format.Uci

import EvalCacheEntry.*

class EvalCacheTest extends munit.FunSuite:

  test("Multiple repeated lines should be invalid"):
    val moves = Moves.from(Uci.Move("e2e4").map(NonEmptyList.one)).get
    val eval = Eval(
      pvs = NonEmptyList
        .one(Pv(Score.cp(40), moves))
        .concat(
          List(Pv(Score.cp(50), moves))
        ),
      knodes = MIN_KNODES,
      depth = Depth(25),
      by = User.Id("user"),
      trust = Trust(1)
    )
    assertEquals(eval.looksValid, false)
