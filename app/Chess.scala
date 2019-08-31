package lila.ws

import play.api.libs.json._

import chess.format.{ FEN, Uci, UciCharPair }
import chess.opening.{ FullOpening, FullOpeningDB }
import chess.Pos
import chess.variant.{ Variant, Crazyhouse }

import ipc._

object Chess {

  def apply(req: ClientOut.AnaMove): Option[ClientIn.Node] =
    chess.Game(req.variant.some, Some(req.fen.value))(req.orig, req.dest, req.promotion).toOption flatMap {
      case (game, move) => game.pgnMoves.lastOption map { san =>
        val uci = Uci(move)
        val movable = game.situation playable false
        val fen = chess.format.Forsyth >> game
        ClientIn.Node(
          path = req.path,
          id = UciCharPair(uci),
          ply = game.turns,
          move = Uci.WithSan(uci, san),
          fen = FEN(fen),
          check = game.situation.check,
          dests = if (movable) Some(game.situation.destinations) else None,
          opening =
            if (game.turns <= 30 && Variant.openingSensibleVariants(req.variant)) FullOpeningDB findByFen fen
            else None,
          drops = if (movable) game.situation.drops else Some(Nil),
          crazyData = game.situation.board.crazyData
        )
      }
    }

  def apply(req: ClientOut.Opening): Option[ClientIn.Opening] =
    if (Variant.openingSensibleVariants(req.variant))
      FullOpeningDB findByFen req.fen.value map {
        ClientIn.Opening(req.path, _)
      }
    else None

  object json {
    implicit val fenWrite = Writes[FEN] { fen => JsString(fen.value) }
    implicit val pathWrite = Writes[Path] { path => JsString(path.value) }
    implicit val uciWrite = Writes[Uci] { uci => JsString(uci.uci) }
    implicit val uciCharPairWrite = Writes[UciCharPair] { ucp => JsString(ucp.toString) }
    implicit val posWrite = Writes[Pos] { pos => JsString(pos.key) }
    implicit val openingWrite = Writes[FullOpening] { o =>
      Json.obj(
        "eco" -> o.eco,
        "name" -> o.name
      )
    }
    implicit val destsJsonWriter: Writes[Map[Pos, List[Pos]]] = Writes { dests =>
      JsString(destString(dests))
    }
    private def destString(dests: Map[Pos, List[Pos]]): String = {
      val sb = new java.lang.StringBuilder(80)
      var first = true
      dests foreach {
        case (orig, dests) =>
          if (first) first = false
          else sb append " "
          sb append orig.piotr
          dests foreach { sb append _.piotr }
      }
      sb.toString
    }

    implicit val crazyhousePocketWriter: OWrites[Crazyhouse.Pocket] = OWrites { v =>
      JsObject(
        Crazyhouse.storableRoles.flatMap { role =>
          Some(v.roles.count(role == _)).filter(0 < _).map { count =>
            role.name -> JsNumber(count)
          }
        }
      )
    }
    implicit val crazyhouseDataWriter: OWrites[chess.variant.Crazyhouse.Data] = OWrites { v =>
      Json.obj("pockets" -> List(v.pockets.white, v.pockets.black))
    }
  }
}
