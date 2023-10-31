package lila.ws

import org.apache.pekko.actor.typed.ActorRef
import com.typesafe.scalalogging.Logger
import ipc.*
import ornicar.scalalib.ThreadLocalRandom

final class LilaHandler(
    lila: Lila,
    users: Users,
    friendList: FriendList,
    roomCrowd: RoomCrowd,
    roundCrowd: RoundCrowd,
    mongo: Mongo,
    clients: ActorRef[Clients.Control],
    services: Services
)(using Executor):

  import LilaOut.*
  import Bus.publish

  private val logger = Logger(getClass)

  private val siteHandler: Emit[LilaOut] =

    case Mlat(millis)            => publish(_.mlat, ClientIn.Mlat(millis))
    case TellFlag(flag, payload) => publish(_ flag flag, ClientIn.Payload(payload))
    case TellSri(sri, payload)   => publish(_ sri sri, ClientIn.Payload(payload))
    case TellSris(sris, payload) => sris foreach { sri => publish(_ sri sri, ClientIn.Payload(payload)) }
    case TellAll(payload)        => publish(_.all, ClientIn.Payload(payload))

    case TellUsers(us, json)  => users.tellMany(us, ClientIn.Payload(json))
    case DisconnectUser(user) => users.kick(user)
    case TellRoomUser(roomId, user, json) =>
      users.tellOne(user, ClientIn.onlyFor(_ Room roomId, ClientIn.Payload(json)))
    case TellRoomUsers(roomId, us, json) =>
      users.tellMany(us, ClientIn.onlyFor(_ Room roomId, ClientIn.Payload(json)))
    case SetTroll(user, v) =>
      users.setTroll(user, v)
      mongo.troll.set(user, v)

    case Follow(left, right)   => friendList.follow(left, right)
    case UnFollow(left, right) => friendList.unFollow(left, right)

    case ApiUserOnline(user, true) =>
      clients ! Clients.Control.Start(
        ApiActor start ApiActor.Deps(user, services),
        Promise[_root_.lila.ws.Client]()
      )
    case ApiUserOnline(user, false) => users.tellOne(user, ClientCtrl.ApiDisconnect)

    case Impersonate(user, by) => Impersonations(user, by)

    case Pong(pingAt) => Monitor.ping.record("site", pingAt)

    case LilaStop(reqId) =>
      logger.info("******************** LILA STOP ********************")
      lila.emit.site(LilaIn.ReqResponse(reqId, "See you on the other side"))
      lila.currentStatus.setOffline()

    case msg => logger.warn(s"Unhandled site: $msg")

  private val lobbyHandler: Emit[LilaOut] =

    case TellLobbyUsers(us, json) =>
      users.tellMany(us, ClientIn.onlyFor(_.Lobby, ClientIn.Payload(json)))

    case TellLobby(payload)       => publish(_.lobby, ClientIn.Payload(payload))
    case TellLobbyActive(payload) => publish(_.lobby, ClientIn.LobbyNonIdle(ClientIn.Payload(payload)))
    case TellSris(sris, payload)  => sris foreach { sri => publish(_ sri sri, ClientIn.Payload(payload)) }
    case LobbyPairings(pairings) =>
      pairings.foreach { (sri, fullId) => publish(_ sri sri, ClientIn.LobbyPairing(fullId)) }

    case site: SiteOut => siteHandler(site)
    case msg           => logger.warn(s"Unhandled lobby: $msg")

  private val simulHandler: Emit[LilaOut] =
    case LilaOut.RoomFilterPresent(reqId, roomId, userIds) =>
      lila.emit.simul(LilaIn.ReqResponse(reqId, roomCrowd.filterPresent(roomId, userIds).mkString(",")))
    case LilaBoot => roomBoot(_.idFilter.simul, lila.emit.simul)
    case msg      => roomHandler(msg)

  private val teamHandler: Emit[LilaOut] =
    case LilaBoot => roomBoot(_.idFilter.team, lila.emit.team)
    case msg      => roomHandler(msg)

  private val swissHandler: Emit[LilaOut] =
    case LilaBoot => roomBoot(_.idFilter.swiss, lila.emit.swiss)
    case msg      => roomHandler(msg)

  private val tourHandler: Emit[LilaOut] =
    case GetWaitingUsers(roomId, name) =>
      mongo.tournamentActiveUsers(roomId into Tour.Id) zip
        mongo.tournamentPlayingUsers(roomId into Tour.Id) foreach { (active, playing) =>
          val present   = roomCrowd getUsers roomId
          val standby   = active diff playing
          val allAbsent = standby diff present
          lila.emit.tour(LilaIn.WaitingUsers(roomId, present intersect standby))
          val absent =
            if allAbsent.sizeIs > 100
            then ThreadLocalRandom.shuffle(allAbsent) take 80
            else allAbsent
          if absent.nonEmpty then users.tellMany(absent, ClientIn.TourReminder(roomId into Tour.Id, name))
        }
    case LilaBoot => roomBoot(_.idFilter.tour, lila.emit.tour)
    case msg      => roomHandler(msg)

  private val studyHandler: Emit[LilaOut] =
    case LilaOut.RoomIsPresent(reqId, roomId, userId) =>
      lila.emit.study(LilaIn.ReqResponse(reqId, roomCrowd.isPresent(roomId, userId).toString))
    case LilaBoot => roomBoot(_.idFilter.study, lila.emit.study)
    case msg      => roomHandler(msg)

  import scala.language.implicitConversions
  private given Conversion[Game.Id, RoomId] with
    def apply(id: Game.Id): RoomId = id.into(RoomId)

  private val roundHandler: Emit[LilaOut] =
    case RoundVersion(gameId, version, flags, tpe, data) =>
      val versioned = ClientIn.RoundVersioned(version, flags, tpe, data)
      History.round.add(gameId, versioned)
      publish(_ room gameId, versioned)
      if tpe == "move" || tpe == "drop" then Fens.move(gameId, data, flags.moveBy)
    case TellRoom(roomId, payload) => publish(_ room roomId, ClientIn.Payload(payload))
    case RoundResyncPlayer(fullId) =>
      publish(_ room fullId.gameId, ClientIn.RoundResyncPlayer(fullId.playerId))
    case RoundGone(fullId, gone) =>
      publish(_ room fullId.gameId, ClientIn.RoundGone(fullId.playerId, gone))
    case RoundGoneIn(fullId, seconds) =>
      publish(_ room fullId.gameId, ClientIn.RoundGoneIn(fullId.playerId, seconds))
    case RoundTourStanding(tourId, data) =>
      publish(_ tourStanding tourId, ClientIn.roundTourStanding(data))
    case o: TvSelect => Tv select o
    case RoomStop(roomId) =>
      History.round.stop(Game.Id(roomId.value))
      publish(_ room roomId, ClientCtrl.Disconnect)
    case RoundBotOnline(gameId, color, v) => roundCrowd.botOnline(gameId, color, v)
    case GameStart(users) =>
      users.foreach: u =>
        friendList.startPlaying(u)
        publish(_ userTv u.into(UserTv), ClientIn.Resync)
    case GameFinish(gameId, winner, users) =>
      users foreach friendList.stopPlaying
      Fens.finish(gameId, winner)
    case LilaResponse(reqId, body) => LilaRequest.onResponse(reqId, body)
    case Pong(pingAt) =>
      val millis = Monitor.ping.record("round", pingAt)
      lila.emit.round(LilaIn.RoundLatency(millis))
    case LilaBoot =>
      logger.info("#################### LILA BOOT ####################")
      lila.emit.round(LilaIn.RoomSetVersions(History.round.allVersions))
    case VersioningReady =>
      logger.info("#################### LILA VERSIONING READY ####################")
      lila.currentStatus.setOnline()
      Impersonations.reset()
    case msg => roomHandler(msg)

  private val racerHandler: Emit[LilaOut] =
    case RacerState(raceId, data) => publish(_ room raceId.into(RoomId), ClientIn.racerState(data))
    case msg                      => roomHandler(msg)

  private def tellRoomVersion(roomId: RoomId, version: SocketVersion, troll: IsTroll, payload: JsonString) =
    val versioned = ClientIn.Versioned(payload, version, troll)
    History.room.add(roomId, versioned)
    publish(_ room roomId, versioned)

  private val roomHandler: Emit[LilaOut] =
    case TellRoomVersion(roomId, version, troll, payload) =>
      tellRoomVersion(roomId, version, troll, payload)
    case TellRoomChat(roomId, version, troll, payload) =>
      tellRoomVersion(roomId, version, troll, payload)
      publish(_ externalChat roomId, ClientIn.Payload(payload))
    case TellRoom(roomId, payload) => publish(_ room roomId, ClientIn.Payload(payload))
    case RoomStop(roomId)          => History.room.stop(roomId)

    case site: SiteOut => siteHandler(site)
    case msg           => logger.warn(s"Unhandled room: $msg")

  private def roomBoot(
      filter: Mongo => Mongo.IdFilter,
      lilaIn: Emit[LilaIn.RoomSetVersions]
  ): Unit =
    val versions = History.room.allVersions
    filter(mongo)(versions.map(_._1)).foreach: ids =>
      lilaIn(LilaIn.RoomSetVersions(versions.filter(v => ids(v._1))))

  lila.setHandlers:
    case Lila.chans.round.out     => roundHandler
    case Lila.chans.site.out      => siteHandler
    case Lila.chans.lobby.out     => lobbyHandler
    case Lila.chans.tour.out      => tourHandler
    case Lila.chans.swiss.out     => swissHandler
    case Lila.chans.simul.out     => simulHandler
    case Lila.chans.study.out     => studyHandler
    case Lila.chans.team.out      => teamHandler
    case Lila.chans.challenge.out => roomHandler
    case Lila.chans.racer.out     => racerHandler
    case chan                     => in => logger.warn(s"Unknown channel $chan sent $in")
