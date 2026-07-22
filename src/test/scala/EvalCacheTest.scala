package lila.ws
package evalCache

import cats.data.NonEmptyList
import chess.eval.Score
import chess.format.{ Fen, Uci }

import EvalCacheEntry.*

class EvalCacheTest extends munit.FunSuite:

  private def makeEval(score: Score, moves: String) =
    Eval(
      pvs = NonEmptyList.one:
        Pv(
          score,
          Moves:
            NonEmptyList.fromListUnsafe:
              moves
                .split(" ")
                .toList
                .map(Uci.Move.apply)
                .flatten
        )
      ,
      knodes = MIN_KNODES,
      depth = Depth(25),
      by = User.Id("user"),
      trust = Trust(1)
    )

  private def makeInput(
      eval: Eval,
      fen: String = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      variant: chess.variant.Variant = chess.variant.Standard
  ) =
    EvalCacheEntry.makeInput(
      variant = variant,
      fen = Fen.Full(fen),
      eval = eval,
      sri = Sri("sri")
    )

  test("Multiple repeated lines should be invalid"):
    val pv1 = makeEval(Score.cp(50), "e2e4")
    val eval = pv1.copy(pvs = pv1.pvs.append(pv1.pvs.head.copy(score = Score.cp(40))))
    assertEquals(eval.looksValid, false)

  test("Mate eval should be invalid if we reach the last position and it's not mate"):
    val moves = "e2e4"
    val eval = makeEval(Score.mate(1), moves)
    val input = makeInput(eval)
    assertEquals(
      input.map(_.validate),
      Some(Left(chess.ErrorStr("Invalid mate Pv(Mate(1),NonEmptyList(Move(e2e4)))")))
    )

  test("PV cannot be longer than mate eval"):
    val line = "e2e4 e7e5 d2d4"
    val eval = makeEval(Score.mate(1), line)
    val input = makeInput(eval)
    assertEquals(
      input.map(_.validate),
      Some(
        Left(chess.ErrorStr("Invalid mate Pv(Mate(1),NonEmptyList(Move(e2e4), Move(e7e5), Move(d2d4)))"))
      )
    )

  test("Really long mate evals with pvs truncated should be valid"):
    val antichess_forced = "b7b5 f1b5 d7d5 b5e8 d5e4 e8f7 d8d2 c1d2 h7h5 f7h5"
    val eval = makeEval(Score.mate(-16), antichess_forced)
    val input =
      makeInput(eval, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b - - 0 1", chess.variant.Antichess)
    assertEquals(input.map(_.validate), Some(Right(())))

  test("Valid mate in 2"):
    val moves = "c4f7 e8e7 c3d5"
    val eval = makeEval(Score.mate(2), moves)
    val input = makeInput(eval, "r2qkbnr/ppp2ppp/2np4/4N3/2B1P3/2N5/PPPP1PPP/R1BbK2R w KQkq - 0 6")
    assertEquals(input.map(_.validate), Some(Right(())))
