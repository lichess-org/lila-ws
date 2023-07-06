package lila.ws
package ipc

import chess.Color

sealed trait LilaOut

sealed trait SiteOut  extends LilaOut
sealed trait LobbyOut extends LilaOut
sealed trait RoomOut  extends LilaOut
sealed trait SimulOut extends RoomOut
sealed trait TourOut  extends RoomOut
sealed trait StudyOut extends RoomOut
sealed trait RoundOut extends RoomOut
sealed trait RacerOut extends RoomOut

sealed trait AnyRoomOut extends RoundOut with StudyOut with TourOut with SimulOut with RacerOut

object LilaOut:

  // site

  case class Mlat(millis: Double)                                  extends SiteOut
  case class TellFlag(flag: Flag, json: JsonString)                extends SiteOut
  case class TellUsers(users: Iterable[User.Id], json: JsonString) extends SiteOut
  case class TellAll(json: JsonString)                             extends SiteOut
  case class DisconnectUser(user: User.Id)                         extends SiteOut
  case class TellSri(sri: Sri, json: JsonString)                   extends SiteOut with LobbyOut with StudyOut
  case class SetTroll(user: User.Id, v: IsTroll)                   extends SiteOut
  case class Impersonate(user: User.Id, by: Option[User.Id])       extends SiteOut
  case class Follow(left: User.Id, right: User.Id)                 extends SiteOut
  case class UnFollow(left: User.Id, right: User.Id)               extends SiteOut
  case class Pong(pingAt: UptimeMillis)                            extends SiteOut with RoundOut
  case class LilaResponse(reqId: Int, body: String)                extends SiteOut with RoundOut

  // lobby

  case class LobbyPairings(pairings: List[(Sri, Game.FullId)])          extends LobbyOut
  case class TellLobby(json: JsonString)                                extends LobbyOut
  case class TellLobbyActive(json: JsonString)                          extends LobbyOut
  case class TellLobbyUsers(users: Iterable[User.Id], json: JsonString) extends LobbyOut

  case class TellSris(sri: Seq[Sri], json: JsonString) extends LobbyOut

  // room

  case class TellRoom(roomId: RoomId, json: JsonString) extends AnyRoomOut
  case class TellRoomVersion(
      roomId: RoomId,
      version: SocketVersion,
      troll: IsTroll,
      json: JsonString
  ) extends AnyRoomOut
  case class TellRoomUser(roomId: RoomId, user: User.Id, json: JsonString) extends AnyRoomOut with SiteOut
  case class TellRoomUsers(roomId: RoomId, users: Iterable[User.Id], json: JsonString)
      extends AnyRoomOut
      with SiteOut

  case class TellRoomChat(
      roomId: RoomId,
      version: SocketVersion,
      troll: IsTroll,
      json: JsonString
  ) extends AnyRoomOut

  case class RoomStop(roomId: RoomId) extends AnyRoomOut

  // study

  case class RoomIsPresent(reqId: Int, roomId: RoomId, userId: User.Id) extends StudyOut

  // simul

  case class RoomFilterPresent(reqId: Int, roomId: RoomId, userIds: Set[User.Id]) extends SimulOut

  // tour

  case class GetWaitingUsers(roomId: RoomId, name: String) extends TourOut

  // round

  case class RoundVersion(
      gameId: Game.Id,
      version: SocketVersion,
      flags: RoundEventFlags,
      tpe: String,
      data: JsonString
  ) extends RoundOut
  case class RoundTourStanding(tourId: Tour.Id, data: JsonString)                     extends RoundOut
  case class RoundResyncPlayer(fullId: Game.FullId)                                   extends RoundOut
  case class RoundGone(fullId: Game.FullId, v: Boolean)                               extends RoundOut
  case class RoundGoneIn(fullId: Game.FullId, seconds: Int)                           extends RoundOut
  case class RoundBotOnline(gameId: Game.Id, color: Color, v: Boolean)                extends RoundOut
  case class GameStart(users: List[User.Id])                                          extends RoundOut
  case class GameFinish(gameId: Game.Id, winner: Option[Color], users: List[User.Id]) extends RoundOut
  case class TvSelect(gameId: Game.Id, speed: chess.Speed, json: JsonString)          extends RoundOut

  // racer

  case class RacerState(raceId: Racer.Id, state: JsonString) extends TourOut

  case class ApiUserOnline(userId: User.Id, online: Boolean) extends AnyRoomOut
  case object LilaBoot                                       extends AnyRoomOut
  case class LilaStop(reqId: Int)                            extends AnyRoomOut
  case object VersioningReady extends RoundOut // lila is ready to receive versioned round events

  // impl

  private def get(args: String, nb: Int)(
      f: PartialFunction[Array[String], Option[LilaOut]]
  ): Option[LilaOut] =
    f.applyOrElse(args.split(" ", nb), (_: Array[String]) => None)

  def read(str: String): Option[LilaOut] =
    val parts = str.split(" ", 2)
    val args  = parts.lift(1) getOrElse ""
    parts(0) match

      case "mlat" => args.toDoubleOption map Mlat.apply

      case "r/ver" =>
        get(args, 5) { case Array(roomId, version, f, tpe, data) =>
          version.toIntOption map { sv =>
            val flags = RoundEventFlags(
              watcher = f contains 's',
              owner = f contains 'p',
              player =
                if f contains 'w' then Some(chess.White)
                else if f contains 'b' then Some(chess.Black)
                else None,
              moveBy =
                if f contains 'B' then Some(chess.Black)
                else if f contains 'W' then Some(chess.White)
                else None,
              troll = f contains 't'
            )
            RoundVersion(Game.Id(roomId), SocketVersion(sv), flags, tpe, JsonString(data))
          }
        }

      case "tell/flag" =>
        get(args, 2) { case Array(flag, payload) =>
          Flag make flag map:
            TellFlag(_, JsonString(payload))
        }

      case "tell/users" =>
        get(args, 2) { case Array(users, payload) =>
          Some(TellUsers(User.Id from commas(users), JsonString(payload)))
        }

      case "tell/sri" =>
        get(args, 2) { case Array(sri, payload) =>
          Some(TellSri(Sri(sri), JsonString(payload)))
        }

      case "tell/all" => Some(TellAll(JsonString(args)))

      case "disconnect/user" => Some(DisconnectUser(User.Id(args)))

      case "lobby/pairings" =>
        Some(LobbyPairings {
          commas(args)
            .map(_ split ':')
            .collect { case Array(sri, fullId) =>
              (Sri(sri), Game.FullId(fullId))
            }
            .toList
        })

      case "tell/lobby"        => Some(TellLobby(JsonString(args)))
      case "tell/lobby/active" => Some(TellLobbyActive(JsonString(args)))

      case "tell/lobby/users" =>
        get(args, 2) { case Array(users, payload) =>
          Some(TellLobbyUsers(User.Id from commas(users), JsonString(payload)))
        }

      case "mod/troll/set" =>
        get(args, 2) { case Array(user, v) =>
          Some(SetTroll(User.Id(user), IsTroll(boolean(v))))
        }
      case "mod/impersonate" =>
        get(args, 2) { case Array(user, by) =>
          Some(Impersonate(User.Id(user), User.Id from optional(by)))
        }

      case "rel/follow" =>
        get(args, 2) { case Array(left, right) =>
          Some(Follow(User.Id(left), User.Id(right)))
        }

      case "rel/unfollow" =>
        get(args, 2) { case Array(left, right) =>
          Some(UnFollow(User.Id(left), User.Id(right)))
        }

      case "tell/sris" =>
        get(args, 2) { case Array(sris, payload) =>
          Some(
            TellSris(
              Sri from commas(sris).toSeq,
              JsonString(payload)
            )
          )
        }

      case "tell/room" =>
        get(args, 2) { case Array(roomId, payload) =>
          Some(TellRoom(RoomId(roomId), JsonString(payload)))
        }

      case "tell/room/version" =>
        get(args, 4) { case Array(roomId, version, troll, payload) =>
          version.toIntOption map { sv =>
            TellRoomVersion(
              RoomId(roomId),
              SocketVersion(sv),
              IsTroll(boolean(troll)),
              JsonString(payload)
            )
          }
        }

      case "tell/room/user" =>
        get(args, 3) { case Array(roomId, userId, payload) =>
          Some(TellRoomUser(RoomId(roomId), User.Id(userId), JsonString(payload)))
        }
      case "tell/room/users" =>
        get(args, 3) { case Array(roomId, userIds, payload) =>
          Some(TellRoomUsers(RoomId(roomId), User.Id from commas(userIds), JsonString(payload)))
        }

      case "room/stop" => Some(RoomStop(RoomId(args)))

      case "room/present" =>
        get(args, 3) { case Array(reqIdS, roomId, userId) =>
          reqIdS.toIntOption map { reqId =>
            RoomIsPresent(reqId, RoomId(roomId), User.Id(userId))
          }
        }

      case "room/filter-present" =>
        get(args, 3) { case Array(reqIdS, roomId, userIds) =>
          reqIdS.toIntOption map { reqId =>
            RoomFilterPresent(reqId, RoomId(roomId), User.Id from commas(userIds).toSet)
          }
        }

      case "tell/room/chat" =>
        get(args, 4) { case Array(roomId, version, troll, payload) =>
          version.toIntOption map { sv =>
            TellRoomChat(
              RoomId(roomId),
              SocketVersion(sv),
              IsTroll(boolean(troll)),
              JsonString(payload)
            )
          }
        }

      case "req/response" =>
        get(args, 2) { case Array(reqId, body) =>
          reqId.toIntOption.map:
            LilaResponse(_, body)
        }

      case "tour/get/waiting" =>
        get(args, 2) { case Array(roomId, name) =>
          Some(GetWaitingUsers(RoomId(roomId), name))
        }

      case "r/tour/standing" =>
        get(args, 2) { case Array(tourId, data) =>
          Some(RoundTourStanding(Tour.Id(tourId), JsonString(data)))
        }

      case "r/resync/player" => Some(RoundResyncPlayer(Game.FullId(args)))

      case "r/gone" =>
        get(args, 2) { case Array(fullId, gone) =>
          Some(RoundGone(Game.FullId(fullId), boolean(gone)))
        }

      case "r/goneIn" =>
        get(args, 2) { case Array(fullId, secS) =>
          secS.toIntOption map:
            RoundGoneIn(Game.FullId(fullId), _)
        }

      case "r/bot/online" =>
        get(args, 3) { case Array(gameId, color, v) =>
          Some(RoundBotOnline(Game.Id(gameId), readColor(color), boolean(v)))
        }

      case "r/start" => Some(GameStart(User.Id from commas(args).toList))
      case "r/finish" =>
        get(args, 3) { case Array(gameId, winner, users) =>
          Some(
            GameFinish(Game.Id(gameId), readOptionalColor(winner), User.Id from commas(users).toList)
          )
        }

      // tv

      case "tv/select" =>
        get(args, 3) { case Array(gameId, speedS, data) =>
          chess.SpeedId.from(speedS.toIntOption) flatMap chess.Speed.apply map { speed =>
            TvSelect(Game.Id(gameId), speed, JsonString(data))
          }
        }

      // racer

      case "racer/state" =>
        get(args, 2) { case Array(raceId, data) =>
          Some(RacerState(Racer.Id(raceId), JsonString(data)))
        }

      // misc

      case "api/online" =>
        get(args, 2) { case Array(userId, online) =>
          Some(ApiUserOnline(User.Id(userId), boolean(online)))
        }

      case "pong" => args.toLongOption map UptimeMillis.apply map Pong.apply

      case "boot" => Some(LilaBoot)

      case "lila/stop" => args.toIntOption map LilaStop.apply

      case "r/versioning-ready" => Some(VersioningReady)

      case _ => None

  def commas(str: String): Array[String]            = if str == "-" then Array.empty else str split ','
  def boolean(str: String): Boolean                 = str == "+"
  def optional(str: String): Option[String]         = if str == "-" then None else Some(str)
  def readColor(str: String): Color                 = Color.fromWhite(str == "w")
  def readOptionalColor(str: String): Option[Color] = optional(str) map readColor
