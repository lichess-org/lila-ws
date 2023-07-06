package lila.ws
package evalCache

import reactivemongo.api.bson.*
import reactivemongo.api.bson.exceptions.TypeDoesNotMatchException
import scala.util.{ Failure, Success, Try }
import cats.data.NonEmptyList
import cats.syntax.all.*
import chess.format.Uci

object EvalCacheBsonHandlers:

  import Mongo.given
  import Eval.*
  import EvalCacheEntry.*

  given BSONHandler[NonEmptyList[Pv]] = new:
    private def scoreWrite(s: Score): String = s.value.fold(_.value.toString, m => s"#${m.value}")
    private def scoreRead(str: String): Option[Score] =
      if str startsWith "#" then
        str.drop(1).toIntOption map { m =>
          Score mate Mate(m)
        }
      else
        str.toIntOption map { c =>
          Score cp Cp(c)
        }
    private def movesWrite(moves: Moves): String = Uci writeListChars moves.value.toList
    private def movesRead(str: String): Option[Moves] = Moves from:
      Uci.readListChars(str).flatMap(_.toNel)
    private val scoreSeparator = ':'
    private val pvSeparator    = '/'
    private val pvSeparatorStr = pvSeparator.toString

    def readTry(bs: BSONValue) =
      bs match
        case BSONString(value) =>
          Try {
            value.split(pvSeparator).toList.map { pvStr =>
              pvStr.split(scoreSeparator) match
                case Array(score, moves) =>
                  Pv(
                    scoreRead(score) getOrElse sys.error(s"Invalid score $score"),
                    movesRead(moves) getOrElse sys.error(s"Invalid moves $moves")
                  )
                case x => sys error s"Invalid PV $pvStr: ${x.toList} (in $value)"
            }
          }.flatMap:
            _.toNel.toRight(new Exception(s"Empty PVs $value")).toTry
        case b => handlerBadType[NonEmptyList[Pv]](b)

    def writeTry(x: NonEmptyList[Pv]) =
      Success(BSONString {
        x.toList.map { pv =>
          s"${scoreWrite(pv.score)}$scoreSeparator${movesWrite(pv.moves)}"
        } mkString pvSeparatorStr
      })

  private def handlerBadType[T](b: BSONValue): Try[T] =
    Failure(TypeDoesNotMatchException("BSONValue", b.getClass.getSimpleName))
  private def handlerBadValue[T](msg: String): Try[T] = Failure(new IllegalArgumentException(msg))

  private def tryHandler[T](read: PartialFunction[BSONValue, Try[T]], write: T => BSONValue): BSONHandler[T] =
    new:
      def readTry(bson: BSONValue) = read.applyOrElse(bson, (b: BSONValue) => handlerBadType(b))
      def writeTry(t: T)           = Success(write(t))

  given BSONHandler[Id] = tryHandler[Id](
    { case BSONString(value) =>
      value split ':' match
        case Array(fen) => Success(Id(chess.variant.Standard, SmallFen(fen)))
        case Array(variantId, fen) =>
          import chess.variant.Variant
          Success(
            Id(
              Variant.Id.from(variantId.toIntOption) flatMap {
                Variant(_)
              } getOrElse sys.error(s"Invalid evalcache variant $variantId"),
              SmallFen(fen)
            )
          )
        case _ => handlerBadValue(s"Invalid evalcache id $value")
    },
    x =>
      BSONString {
        if x.variant.standard || x.variant.fromPosition then x.smallFen.value
        else s"${x.variant.id}:${x.smallFen.value}"
      }
  )

  given BSONDocumentHandler[Eval]           = Macros.handler
  given BSONDocumentHandler[EvalCacheEntry] = Macros.handler
