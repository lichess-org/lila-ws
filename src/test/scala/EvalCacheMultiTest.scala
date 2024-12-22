package lila.ws
package evalCache

import chess.format.*
import lila.ws.ipc.ClientOut.EvalGetMulti
import chess.variant.Standard
import cats.data.NonEmptyList
import chess.eval.*

class EvalCacheMultiTest extends munit.FunSuite:

  val inst = EvalCacheMulti.mock()
  val sri  = Sri.random()
  val fen  = Fen.Full("r2q1rk1/pp3pbp/3pb1p1/1P2p3/2P1P3/8/P2BBPPP/2RQ1RK1 b - - 0 17")
  val get  = EvalGetMulti(List(fen), Standard)
  inst.register(sri, get)

  test("onEval"):
    def makeInput(depth: Depth) =
      import EvalCacheEntry.*
      val score = Score.initial
      val moves = Moves.from(Uci.Move("e2e4").map(NonEmptyList.one)).get
      val eval = Eval(
        pvs = NonEmptyList.one(Pv(score, moves)),
        knodes = MIN_KNODES,
        depth = depth,
        by = User.Id("user"),
        trust = Trust(1)
      )
      val situation = Fen.read(Standard, fen).get
      Input(Id(situation), fen, situation, eval.truncatePvs, Sri.random())

    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH)), Set(sri))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH)), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH)), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 1)), Set(sri))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 1)), Set())
