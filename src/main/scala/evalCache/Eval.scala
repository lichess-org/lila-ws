package lila.ws
package evalCache

case class Eval(cp: Option[Eval.Cp], mate: Option[Eval.Mate]):
  def isEmpty = cp.isEmpty && mate.isEmpty

object Eval:

  val initial = Eval(Some(Cp.initial), None)
  val empty   = Eval(None, None)

  opaque type Score = Either[Cp, Mate]
  object Score extends TotalWrapper[Score, Either[Cp, Mate]]:

    inline def cp(x: Cp): Score     = Score(Left(x))
    inline def mate(y: Mate): Score = Score(Right(y))

    extension (score: Score)

      inline def cp: Option[Cp]     = score.value.left.toOption
      inline def mate: Option[Mate] = score.value.toOption

      inline def mateFound = score.value.isRight

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
        if cp.value > Cp.CEILING then Cp.CEILING
        else if cp.value < -Cp.CEILING then -Cp.CEILING
        else cp

      inline def invert: Cp                  = Cp(-cp.value)
      inline def invertIf(cond: Boolean): Cp = if cond then invert else cp

      def signum: Int = Math.signum(cp.value.toFloat).toInt
  end Cp

  opaque type Mate = Int
  object Mate extends OpaqueInt[Mate]:
    extension (mate: Mate)
      inline def moves: Int = mate.value

      inline def invert: Mate                  = Mate(-moves)
      inline def invertIf(cond: Boolean): Mate = if cond then invert else mate

      inline def signum: Int = if positive then 1 else -1

      inline def positive = mate.value > 0
      inline def negative = mate.value < 0
