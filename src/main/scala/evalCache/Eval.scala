package lila.ws
package evalCache

case class Eval(
    cp: Option[Eval.Cp],
    mate: Option[Eval.Mate],
):

  def isEmpty = cp.isEmpty && mate.isEmpty

  // def invert = copy(cp = cp.map(_.invert), mate = mate.map(_.invert))

  // def score: Option[Eval.Score] = cp.map(Eval.Score.cp(_)) orElse mate.map(Eval.Score.mate(_))

  // def forceAsCp: Option[Eval.Cp] = cp orElse mate.map {
  //   case m if m.negative => Eval.Cp(Int.MinValue - m.value)
  //   case m               => Eval.Cp(Int.MaxValue - m.value)
  // }

object Eval:

  opaque type Score = Either[Cp, Mate]
  object Score extends TotalWrapper[Score, Either[Cp, Mate]]:

    inline def cp(x: Cp): Score     = Score(Left(x))
    inline def mate(y: Mate): Score = Score(Right(y))
    // val checkmate: Either[Cp, Mate] = Right(Mate(0))

    extension (score: Score)

      inline def cp: Option[Cp]     = score.value.left.toOption
      inline def mate: Option[Mate] = score.value.toOption

    //   inline def isCheckmate = score.value == Score.checkmate
      inline def mateFound   = score.value.isRight

    //   inline def invert = Score(score.value.left.map(Cp.invert(_)).map(Mate.invert(_)))
    //   inline def invertIf(cond: Boolean): Score = if (cond) invert else score

    //   def eval: Eval = Eval(cp, mate)

  end Score

  opaque type Cp = Int
  object Cp extends OpaqueInt[Cp]:
    val CEILING                               = Cp(1000)
    val initial                               = Cp(15)
    inline def ceilingWithSignum(signum: Int) = CEILING.invertIf(signum < 0)

    extension (cp: Cp)
      inline def centipawns = cp.value

      inline def pawns: Float      = cp.value / 100f
      inline def showPawns: String = "%.2f" format pawns

      inline def ceiled: Cp =
        if (cp.value > Cp.CEILING) Cp.CEILING
        else if (cp.value < -Cp.CEILING) -Cp.CEILING
        else cp

      inline def invert: Cp                  = Cp(-cp.value)
      inline def invertIf(cond: Boolean): Cp = if (cond) invert else cp

      def signum: Int = Math.signum(cp.value.toFloat).toInt
  end Cp

  opaque type Mate = Int
  object Mate extends OpaqueInt[Mate]:
    extension (mate: Mate)
      inline def moves: Int = mate.value

      inline def invert: Mate                  = Mate(-moves)
      inline def invertIf(cond: Boolean): Mate = if (cond) invert else mate

      inline def signum: Int = if (positive) 1 else -1

      inline def positive = mate.value > 0
      inline def negative = mate.value < 0

  val initial = Eval(Some(Cp.initial), None)

  val empty = Eval(None, None)
