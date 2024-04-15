package lila.ws
package evalCache

import cats.data.NonEmptyList
import cats.syntax.all.*
import chess.format.{ BinaryFen, Uci }
import reactivemongo.api.bson.*
import reactivemongo.api.bson.exceptions.TypeDoesNotMatchException

import scala.util.{ Failure, Success, Try }

object EvalCacheBsonHandlers:

  import Mongo.given
  import Eval.*
  import EvalCacheEntry.*

  given BSONHandler[NonEmptyList[Pv]] = new:
    private def scoreWrite(s: Score): String = s.value.fold(_.value.toString, m => s"#${m.value}")
    private def scoreRead(str: String): Option[Score] =
      if str.startsWith("#") then
        str.drop(1).toIntOption.map { m =>
          Score.mate(Mate(m))
        }
      else
        str.toIntOption.map { c =>
          Score.cp(Cp(c))
        }
    private def movesWrite(moves: Moves): String = Uci.writeListChars(moves.value.toList)
    private def movesRead(str: String): Option[Moves] = Moves.from:
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
                    scoreRead(score).getOrElse(sys.error(s"Invalid score $score")),
                    movesRead(moves).getOrElse(sys.error(s"Invalid moves $moves"))
                  )
                case x => sys.error(s"Invalid PV $pvStr: ${x.toList} (in $value)")
            }
          }.flatMap:
            _.toNel.toRight(new Exception(s"Empty PVs $value")).toTry
        case b => handlerBadType[NonEmptyList[Pv]](b)

    def writeTry(x: NonEmptyList[Pv]) =
      Success(BSONString {
        x.toList
          .map { pv =>
            s"${scoreWrite(pv.score)}$scoreSeparator${movesWrite(pv.moves)}"
          }
          .mkString(pvSeparatorStr)
      })

  private def handlerBadType[T](b: BSONValue): Try[T] =
    Failure(TypeDoesNotMatchException("BSONValue", b.getClass.getSimpleName))

  given BSONHandler[Id] = new:
    def readTry(bson: BSONValue) =
      bson match
        case v: BSONBinary => Success(Id(BinaryFen(v.byteArray)))
        case _             => handlerBadType(bson)
    def writeTry(v: Id) = Success(BSONBinary(v.position.value, Subtype.GenericBinarySubtype))

  given BSONDocumentHandler[Eval]           = Macros.handler
  given BSONDocumentHandler[EvalCacheEntry] = Macros.handler
