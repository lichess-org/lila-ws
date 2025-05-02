package lila.ws
package evalCache

import cats.data.NonEmptyList
import chess.eval.*
import chess.format.*
import chess.variant.Standard

import lila.ws.ipc.ClientOut.EvalGetMulti

class EvalCacheMultiTest extends munit.FunSuite:

  val fen = Fen.Full("r2q1rk1/pp3pbp/3pb1p1/1P2p3/2P1P3/8/P2BBPPP/2RQ1RK1 b - - 0 17")
  val sri = Sri.random()

  def makeInput(depth: Depth, score: Score) =
    import EvalCacheEntry.*
    val moves = Moves.from(Uci.Move("e2e4").map(NonEmptyList.one)).get
    val eval = Eval(
      pvs = NonEmptyList.one(Pv(score, moves)),
      knodes = MIN_KNODES,
      depth = depth,
      by = User.Id("user"),
      trust = Trust(1)
    )
    val position = Fen.read(Standard, fen).get
    Input(Id(position), fen, position, eval.truncatePvs, Sri.random())

  test("onEval, only send when depth has changed"):
    val inst = EvalCacheMulti.mock()
    inst.register(sri, EvalGetMulti(List(fen), Standard))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH, Score.cp(-200))), Set(sri))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH, Score.cp(200))), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH, Score.cp(-200))), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 1, Score.cp(200))), Set(sri))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 1, Score.cp(-200))), Set())

  test("onEval, only send when winning chances have changed"):
    val inst = EvalCacheMulti.mock()
    inst.register(sri, EvalGetMulti(List(fen), Standard))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH, Score.cp(200))), Set(sri))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 1, Score.cp(200))), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 2, Score.cp(-200))), Set(sri))
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 3, Score.cp(-200))), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 4, Score.cp(-190))), Set())
    assertEquals(inst.onEvalSrisToUpgrade(makeInput(MIN_DEPTH + 5, Score.cp(-160))), Set(sri))
