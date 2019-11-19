package lila.ws

import ipc.LilaOut._
import javax.inject._
import play.api.Logger
import scala.concurrent.ExecutionContext

import ipc._

@Singleton
final class LilaHandler @Inject() (
    lila: Lila,
    fens: Fens,
    users: Users,
    roomCrowd: RoomCrowd,
    roundCrowd: RoundCrowd,
    lobby: Lobby,
    mongo: Mongo
)(implicit ec: ExecutionContext) {

  import LilaOut._
  import Bus.publish

  private val logger = Logger("LilaHandler")

  private val siteHandler: Emit[LilaOut] = {

    case Mlat(millis) => publish(_.mlat, ClientIn.Mlat(millis))
    case TellFlag(flag, payload) => publish(_ flag flag, ClientIn.Payload(payload))
    case TellSri(sri, payload) => publish(_ sri sri, ClientIn.Payload(payload))
    case TellAll(payload) => publish(_.all, ClientIn.Payload(payload))

    case Move(gameId, lastUci, fen) => fens.move(gameId, lastUci, fen)

    case TellUsers(us, json) => users.tellMany(us, ClientIn.Payload(json))
    case DisconnectUser(user) => users.kick(user)
    case TellRoomUser(roomId, user, json) => users.tellOne(user, ClientIn.onlyFor(_ Room roomId, ClientIn.Payload(json)))
    case TellRoomUsers(roomId, us, json) => users.tellMany(us, ClientIn.onlyFor(_ Room roomId, ClientIn.Payload(json)))
    case SetTroll(user, v) => users.setTroll(user, v)

    case Impersonate(user, by) => Impersonations(user, by)

    case msg => logger.warn(s"Unhandled site: $msg")
  }

  private val lobbyHandler: Emit[LilaOut] = {

    case TellLobbyUsers(us, json) => users.tellMany(us, ClientIn.onlyFor(_.Lobby, ClientIn.Payload(json)))

    case TellLobby(payload) => publish(_.lobby, ClientIn.Payload(payload))
    case TellLobbyActive(payload) => publish(_.lobby, ClientIn.LobbyNonIdle(ClientIn.Payload(payload)))
    case TellSris(sris, payload) => sris foreach { sri => publish(_ sri sri, ClientIn.Payload(payload)) }
    case LobbyPairings(pairings) => pairings.foreach { case (sri, fullId) => publish(_ sri sri, ClientIn.LobbyPairing(fullId)) }
    case NbMembers(nb) => lobby.pong.update(_.copy(members = nb))
    case NbRounds(nb) => lobby.pong.update(_.copy(rounds = nb))

    case site: SiteOut => siteHandler(site)
    case msg => logger.warn(s"Unhandled lobby: $msg")
  }

  private val simulHandler: Emit[LilaOut] = {
    case LilaBoot => roomBoot(_.idFilter.simul, lila.emit.simul)
    case msg => roomHandler("simul")
  }

  private val tourHandler: Emit[LilaOut] = {
    case GetWaitingUsers(roomId, name) =>
      mongo.tournamentActiveUsers(roomId.value) zip mongo.tournamentPlayingUsers(roomId.value) foreach {
        case (active, playing) =>
          val present = roomCrowd getUsers roomId
          val standby = active diff playing
          val allAbsent = standby diff present
          lila.emit.tour(LilaIn.WaitingUsers(roomId, name, present, standby))
          val absent = {
            if (allAbsent.size > 100) scala.util.Random.shuffle(allAbsent) take 80
            else allAbsent
          }.toSet
          if (absent.nonEmpty) users.tellMany(absent, ClientIn.TourReminder(roomId.value, name))
      }
    case LilaBoot => roomBoot(_.idFilter.tour, lila.emit.tour)
    case msg => roomHandler("tour")
  }

  private val studyHandler: Emit[LilaOut] = {
    case LilaBoot => roomBoot(_.idFilter.study, lila.emit.study)
    case msg => roomHandler("study")
  }

  private val roundHandler: Emit[LilaOut] = {
    implicit def gameRoomId(gameId: Game.Id): RoomId = RoomId(gameId)
    implicit def roomGameId(roomId: RoomId): Game.Id = Game.Id(roomId.value)
    ({
      case RoundVersion(gameId, version, flags, tpe, data) =>
        val versioned = ClientIn.RoundVersioned(version, flags, tpe, data)
        History.round.add(gameId, versioned)
        publish(_ room gameId, versioned)
      case TellRoom(roomId, payload) => publish(_ room roomId, ClientIn.Payload(payload))
      case RoundResyncPlayer(fullId) => publish(_ room RoomId(fullId.gameId), ClientIn.RoundResyncPlayer(fullId.playerId))
      case RoundGone(fullId, gone) => publish(_ room RoomId(fullId.gameId), ClientIn.RoundGone(fullId.playerId, gone))
      case RoundTourStanding(tourId, data) => publish(_ tourStanding tourId, ClientIn.roundTourStanding(data))
      case UserTvNewGame(gameId, userId) => publish(_ room gameId, RoundUserTvNewGame(userId))
      case o: TvSelect => Tv select o
      case RoomStop(roomId) =>
        History.round.stop(roomId)
        publish(_ room roomId, ClientCtrl.Disconnect)
      case RoundBotOnline(gameId, color, v) => roundCrowd.botOnline(gameId, color, v)
      case LilaBoot =>
        println("#################### LILA BOOT ####################")
        lila.emit.round(LilaIn.RoomSetVersions(History.round.allVersions))
      case msg => roomHandler("round")
    })
  }

  private val challengeHandler: Emit[LilaOut] = roomHandler("challenge")

  private def roomHandler(name: String): Emit[LilaOut] = {
    case TellRoomVersion(roomId, version, troll, payload) =>
      History.room.add(roomId, ClientIn.Versioned(payload, version, troll))
      publish(_ room roomId, ClientIn.Versioned(payload, version, troll))
    case TellRoom(roomId, payload) => publish(_ room roomId, ClientIn.Payload(payload))
    case RoomStop(roomId) => History.room.stop(roomId)

    case site: SiteOut => siteHandler(site)
    case msg => logger.warn(s"Unhandled $name: $msg")
  }

  private def roomBoot(filter: Mongo => Mongo.IdFilter, lilaIn: Emit[LilaIn.RoomSetVersions]): Unit = {
    val versions = History.room.allVersions
    filter(mongo)(versions.map(_._1)) foreach { ids =>
      lilaIn(LilaIn.RoomSetVersions(versions.filter(v => ids(v._1))))
    }
  }

  def handlers: Map[Lila.Chan, Emit[LilaOut]] = Map(
    Lila.chans.site -> siteHandler,
    Lila.chans.lobby -> lobbyHandler,
    Lila.chans.simul -> simulHandler,
    Lila.chans.tour -> tourHandler,
    Lila.chans.study -> studyHandler,
    Lila.chans.round -> roundHandler,
    Lila.chans.challenge -> challengeHandler
  )
}
