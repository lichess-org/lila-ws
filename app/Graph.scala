package lila.ws

import akka.stream._
import akka.stream.scaladsl._
import GraphDSL.Implicits._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import ipc._

object Graph {

  type SQ[A] = SourceQueueWithComplete[A]
  type GraphType = RunnableGraph[(SQ[SiteOut], SQ[LobbyOut], SQ[SimulOut], SQ[TourOut], SQ[StudyOut], SQ[RoundOut], Stream.Queues)]

  def apply(
    lilaInSite: Sink[LilaIn.Site, _],
    lilaInLobby: Sink[LilaIn.Lobby, _],
    lilaInSimul: Sink[LilaIn.Simul, _],
    lilaInTour: Sink[LilaIn.Tour, _],
    lilaInStudy: Sink[LilaIn.Study, _],
    lilaInRound: Sink[LilaIn.Round, _],
    mongo: Mongo,
    crowdJson: CrowdJson
  )(implicit system: akka.actor.ActorSystem, ec: ExecutionContext): GraphType = RunnableGraph.fromGraph(GraphDSL.create(
    Source.queue[SiteOut](8192, overflow), // from site chan
    Source.queue[LobbyOut](8192, overflow), // from lobby chan
    Source.queue[SimulOut](8192, overflow), // from simul chan
    Source.queue[TourOut](8192, overflow), // from tour chan
    Source.queue[StudyOut](8192, overflow), // from study chan
    Source.queue[RoundOut](8192, overflow), // from round chan
    Source.queue[LilaIn.Notified](128, overflow), // clients -> lila:site notified
    Source.queue[LilaIn.Friends](128, overflow), // clients -> lila:site friends
    Source.queue[LilaIn.Site](8192, overflow), // clients -> lila:site (forward)
    Source.queue[LilaIn.Lobby](8192, overflow), // clients -> lila:lobby
    Source.queue[LilaIn.Simul](1024, overflow), // clients -> lila:simul
    Source.queue[LilaIn.Tour](8192, overflow), // clients -> lila:tour
    Source.queue[LilaIn.Study](8192, overflow), // clients -> lila:study
    Source.queue[LilaIn.Round](8192, overflow), // clients -> lila:round
    Source.queue[UserLag](256, overflow), // clients -> lag machine
    Source.queue[sm.FenSM.Input](256, overflow), // clients -> fen machine
    Source.queue[sm.UserSM.Input](256, overflow), // clients -> user machine,
    Source.queue[RoomCrowd.Input](256, overflow), // clients -> crowd machine
    Source.queue[RoundCrowd.Input](512, overflow), // clients -> crowd machine
    Source.queue[ThroughStudyDoor](256, overflow) // clients -> study door machine
  ) {
      case (siteOut, lobbyOut, simulOut, tourOut, studyOut, roundOut, lilaInNotified, lilaInFriends, lilaInSite, lilaInLobby, lilaInSimul,
        lilaInTour, lilaInStudy, lilaInRound, lag, fen, user, crowd, roundCrowd, studyDoor) => (
        siteOut, lobbyOut, simulOut, tourOut, studyOut, roundOut,
        Stream.Queues(lilaInNotified, lilaInFriends, lilaInSite, lilaInLobby, lilaInSimul, lilaInTour, lilaInStudy, lilaInRound, lag, fen, user, crowd, roundCrowd, studyDoor)
      )
    } { implicit b => (SiteOutlet, LobbyOutlet, SimulOutlet, TourOutlet, StudyOutlet, RoundOutlet, ClientToNotified, ClientToFriends, ClientToSite, ClientToLobby,
      ClientToSimul, ClientToTour, ClientToStudy, ClientToRound, ClientToLag, ClientToFen, ClientToUser,
      ClientToCrowd, ClientToRoundCrowd, ClientToStudyDoor) =>

      val bus = Bus(system)
      val tv = new Tv(bus)

      def ToSiteOut: FlowShape[LilaOut, SiteOut] = b.add {
        Flow[LilaOut].collect {
          case siteOut: SiteOut => siteOut
        }
      }

      def broadcast[A](ports: Int): UniformFanOutShape[A, A] = b.add(Broadcast[A](ports))

      def merge[A](ports: Int): UniformFanInShape[A, A] = b.add(Merge[A](ports))

      def machine[State, Input, Emit](m: sm.StateMachine[State, Input, Emit]): FlowShape[Input, Emit] = b.add {
        Flow[Input].scan(m.zero)(m.apply).mapConcat(m.emit)
      }

      // site

      val SiteOut = merge[SiteOut](6)

      val SOBroad = broadcast[SiteOut](3)

      val SOBus: FlowShape[SiteOut, Bus.Msg] = b.add {
        Flow[SiteOut].collect {
          case LilaOut.Mlat(millis) => Bus.msg(ClientIn.Mlat(millis), _.mlat)
          case LilaOut.TellFlag(flag, payload) => Bus.msg(ClientIn.Payload(payload), _ flag flag)
          case LilaOut.TellSri(sri, payload) => Bus.msg(ClientIn.Payload(payload), _ sri sri)
          case LilaOut.TellAll(payload) => Bus.msg(ClientIn.Payload(payload), _.all)
        }
      }

      val ClientBus = merge[Bus.Msg](7)

      val BusPublish: SinkShape[Bus.Msg] = b.add {
        Sink.foreach[Bus.Msg](bus.publish)
      }

      val SOUser: FlowShape[LilaOut, sm.UserSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.TellUsers(users, json) => sm.UserSM.TellMany(users, ClientIn.Payload(json))
          case LilaOut.DisconnectUser(user) => sm.UserSM.Kick(user)
          case LilaOut.TellRoomUser(roomId, user, json) =>
            sm.UserSM.TellOne(user, ClientIn.onlyFor(_ Room roomId, ClientIn.Payload(json)))
          case LilaOut.TellRoomUsers(roomId, users, json) =>
            sm.UserSM.TellMany(users, ClientIn.onlyFor(_ Room roomId, ClientIn.Payload(json)))
        }
      }

      val User = merge[sm.UserSM.Input](5)

      val UserSM: FlowShape[sm.UserSM.Input, LilaIn.Site] = machine(sm.UserSM.machine)

      val SOFen: FlowShape[LilaOut, sm.FenSM.Input] = b.add {
        Flow[LilaOut].collect {
          case move: LilaOut.Move => sm.FenSM.Move(move)
        }
      }

      val Fen = merge[sm.FenSM.Input](2)

      val FenSM: FlowShape[sm.FenSM.Input, LilaIn.Site] = machine(sm.FenSM.machine)

      val Lag: FlowShape[UserLag, LilaIn.Lags] = b.add {
        Flow[UserLag].groupedWithin(128, 947.millis) map { lags =>
          LilaIn.Lags(lags.map(l => l.userId -> l.lag).toMap)
        }
      }

      val Notified: FlowShape[LilaIn.Notified, LilaIn.NotifiedBatch] = b.add {
        Flow[LilaIn.Notified].groupedWithin(40, 1001.millis) map { notifs =>
          LilaIn.NotifiedBatch(notifs.map(_.userId).distinct)
        }
      }

      val Friends: FlowShape[LilaIn.Friends, LilaIn.FriendsBatch] = b.add {
        Flow[LilaIn.Friends].groupedWithin(10, 521.millis) map { friends =>
          LilaIn.FriendsBatch(friends.map(_.userId).distinct)
        }
      }

      val SiteIn = merge[LilaIn.Site](6)

      val SiteInlet: Inlet[LilaIn.Site] = b.add(lilaInSite).in

      // lobby

      val LOBroad = broadcast[LobbyOut](4)

      val LOBus: FlowShape[LobbyOut, Bus.Msg] = b.add {
        Flow[LobbyOut].mapConcat {
          case LilaOut.TellLobby(payload) => List(Bus.msg(ClientIn.Payload(payload), _.lobby))
          case LilaOut.TellLobbyActive(payload) => List(Bus.msg(ClientIn.LobbyNonIdle(ClientIn.Payload(payload)), _.lobby))
          case LilaOut.TellSris(sris, payload) => sris map { sri =>
            Bus.msg(ClientIn.Payload(payload), _ sri sri)
          }
          case LilaOut.LobbyPairings(pairings) => pairings.map {
            case (sri, fullId) => Bus.msg(ClientIn.LobbyPairing(fullId), _ sri sri)
          }
          case _ => Nil
        }
      }

      val LOUser: FlowShape[LilaOut, sm.UserSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.TellLobbyUsers(users, json) => sm.UserSM.TellMany(users, ClientIn.onlyFor(_.Lobby, ClientIn.Payload(json)))
        }
      }

      val LobbyPong: SinkShape[LobbyOut] = b.add {
        Sink.foreach[LobbyOut] {
          case LilaOut.NbMembers(nb) => LobbyPongStore.update(_.copy(members = nb))
          case LilaOut.NbRounds(nb) => LobbyPongStore.update(_.copy(rounds = nb))
          case _ =>
        }
      }

      val Lobby: FlowShape[LilaIn.Lobby, LilaIn.Lobby] = b add LobbyFlow

      val LobbyInlet: Inlet[LilaIn.Lobby] = b.add(lilaInLobby).in

      // crowd

      val Crowd: FlowShape[RoomCrowd.Input, RoomCrowd.Output] = b.add {
        Flow[RoomCrowd.Input].mapConcat(in => RoomCrowd(in).toList)
      }

      val CrowdJson: FlowShape[RoomCrowd.Output, Bus.Msg] = b.add {
        Flow[RoomCrowd.Output]
          .groupedWithin(1024, 1.second)
          .mapConcat { all =>
            all.foldLeft(Map.empty[RoomId, RoomCrowd.Output]) {
              case (crowds, crowd) => crowds.updated(crowd.roomId, crowd)
            }.values.toList
          }
          .mapAsyncUnordered(8)(crowdJson.room)
      }

      // room

      def EventStore: SinkShape[RoomOut] = b.add {
        Sink.foreach[RoomOut] {
          case LilaOut.TellRoomVersion(roomId, version, troll, json) =>
            RoomEvents.add(roomId, ClientIn.Versioned(json, version, troll))
          case LilaOut.RoomStop(roomId) => RoomEvents.stop(roomId)
          case LilaOut.RoomStart(roomId) => RoomEvents.reset(roomId)
          case _ =>
        }
      }

      val roomBusCollect: PartialFunction[RoomOut, Bus.Msg] = {
        case LilaOut.TellRoomVersion(roomId, version, troll, payload) =>
          Bus.msg(ClientIn.Versioned(payload, version, troll), _ room roomId)
        case LilaOut.TellRoom(roomId, payload) =>
          Bus.msg(ClientIn.Payload(payload), _ room roomId)
      }

      def RoomBus: FlowShape[RoomOut, Bus.Msg] = b.add {
        Flow[RoomOut].collect(roomBusCollect)
      }

      // extract KeepAlive events from the stream,
      // and send the room ids to another sink every 15 seconds
      def andKeepAlive[In <: LilaIn.Room](sink: Sink[LilaIn.KeepAlives, _]) = Flow[In].divertTo(
        that = Flow[In]
          .conflateWithSeed[Set[RoomId]]({
            case LilaIn.KeepAlive(roomId) => Set(roomId)
            case _ => Set.empty
          }) {
            case (rooms, LilaIn.KeepAlive(roomId)) => rooms + roomId
            case (rooms, _) => rooms
          }
          .throttle(1, per = 15.second)
          .map(LilaIn.KeepAlives.apply)
          .to(sink),
        when = {
          case _: LilaIn.KeepAlive => true
          case m => false
        }
      )

      // simul

      val SimBroad = broadcast[SimulOut](3)

      val SimKeepAlive = andKeepAlive[LilaIn.Simul](lilaInSimul)

      val SimulInlet: Inlet[LilaIn.Simul] = b.add(lilaInSimul).in

      // tournament

      val TouBroad = broadcast[TourOut](4)

      val TouCrowd: FlowShape[TourOut, LilaIn.WaitingUsers] = b.add {
        Flow[TourOut].collect {
          case LilaOut.GetWaitingUsers(roomId, name) => (roomId, name, RoomCrowd.getUsers(roomId))
        }.mapAsyncUnordered(4) {
          case (roomId, name, present) => mongo.tournamentActiveUsers(roomId.value) zip
            mongo.tournamentPlayingUsers(roomId.value) map {
              case (active, playing) =>
                LilaIn.WaitingUsers(roomId, name, present, active diff playing)
            }
        }
      }

      val TouCrowdB = broadcast[LilaIn.WaitingUsers](2)

      val TouRemind: FlowShape[LilaIn.WaitingUsers, sm.UserSM.TellMany] = b.add {
        Flow[LilaIn.WaitingUsers].mapConcat {
          case LilaIn.WaitingUsers(roomId, name, present, standby) =>
            val allAbsent = standby diff present
            val absent = {
              if (allAbsent.size > 100) scala.util.Random.shuffle(allAbsent) take 80
              else allAbsent
            }.toSet
            if (absent.nonEmpty)
              List(sm.UserSM.TellMany(absent, ClientIn.TourReminder(roomId.value, name)))
            else Nil
        }
      }

      val TourKeepAlive = andKeepAlive[LilaIn.Tour](lilaInTour)

      val TourIn = merge[LilaIn.Tour](2)

      val TourInlet: Inlet[LilaIn.Tour] = b.add(lilaInTour).in

      // study

      val StuBroad = broadcast[StudyOut](4)

      val StuCrowd: FlowShape[StudyOut, LilaIn.ReqResponse] = b.add {
        Flow[StudyOut].collect {
          case LilaOut.RoomIsPresent(reqId, roomId, userId) =>
            LilaIn.ReqResponse(reqId, RoomCrowd.isPresent(roomId, userId).toString)
        }
      }

      val StudyDoor: FlowShape[ThroughStudyDoor, LilaIn.StudyDoor] = b.add {
        Flow[ThroughStudyDoor].groupedWithin(64, 1931.millis) map { throughs =>
          LilaIn.StudyDoor {
            throughs.foldLeft(Map.empty[lila.ws.User.ID, Either[RoomId, RoomId]]) {
              case (doors, ThroughStudyDoor(user, through)) => doors + (user.id -> through)
            }
          }
        }
      }

      val StuKeepAlive = andKeepAlive[LilaIn.Study](lilaInStudy)

      val StudyIn = merge[LilaIn.Study](3)

      val StudyInlet: Inlet[LilaIn.Study] = b.add(lilaInStudy).in

      // round

      val RouBroad = broadcast[RoundOut](2)

      val RoundBus: SinkShape[RoundOut] = b.add {
        implicit def gameRoomId(gameId: Game.Id): RoomId = RoomId(gameId)
        implicit def roomGameId(roomId: RoomId): Game.Id = Game.Id(roomId.value)
        Sink.foreach[RoundOut] {
          case LilaOut.RoundResyncPlayer(fullId) =>
            bus(ClientIn.RoundResyncPlayer(fullId.playerId), _ room RoomId(fullId.gameId))
          case LilaOut.RoundGone(fullId, gone) =>
            bus(ClientIn.RoundGone(fullId.playerId, gone), _ room RoomId(fullId.gameId))
          case LilaOut.RoundVersion(gameId, version, flags, tpe, data) =>
            val versioned = ClientIn.RoundVersioned(version, flags, tpe, data)
            RoundEvents.add(gameId, versioned)
            bus(versioned, _ room gameId)
          case LilaOut.RoundTourStanding(tourId, data) =>
            bus(ClientIn.roundTourStanding(data), _ tourStanding tourId)
          case LilaOut.UserTvNewGame(gameId, userId) =>
            bus(UserTvNewGame(userId), _ room gameId)
          case o: LilaOut.TvSelect => tv select o
          case LilaOut.RoomStop(roomId) =>
            RoundEvents.stop(roomId)
            bus(ClientCtrl.Disconnect, _ room roomId)
          case LilaOut.RoomStart(roomId) =>
            val hadEvents = RoundEvents hasEvents roomId
            if (hadEvents) {
              println(RoundEvents.getFrom(roomId, None))
              println(s"start round $roomId that had events! kicking members")
            }
            RoundEvents.reset(roomId)
            if (hadEvents) bus(ClientCtrl.Disconnect, _ room roomId)
          case LilaOut.RoundBotOnline(gameId, color, v) => bus(RoundBotOnline(gameId, color, v), _.roundBot)
          case LilaOut.TellRoom(roomId, payload) =>
            bus(ClientIn.Payload(payload), _ room roomId)
          case _ =>
        }
      }

      val RouCrowd: FlowShape[RoundCrowd.Input, List[RoundCrowd.Output]] = b.add {
        Flow[RoundCrowd.Input]
          .mapConcat(in => RoundCrowd(in).toList)
          .groupedWithin(256, 500.millis)
          .map { all =>
            all.foldLeft(Map.empty[RoomId, RoundCrowd.Output]) {
              case (crowds, crowd) => crowds.updated(crowd.room.roomId, crowd)
            }.values.toList
          }
      }

      val RouCrowdBroad = broadcast[List[RoundCrowd.Output]](2)

      val RouCrowdJson: FlowShape[List[RoundCrowd.Output], Bus.Msg] = b.add {
        Flow[List[RoundCrowd.Output]]
          .mapConcat(identity)
          .mapAsyncUnordered(8)(crowdJson.round)
      }

      val RouOns: FlowShape[List[RoundCrowd.Output], LilaIn.RoundOnlines] = b.add {
        Flow[List[RoundCrowd.Output]].map(LilaIn.RoundOnlines.apply)
      }

      val RoundIn = merge[LilaIn.Round](2)

      val RoundInlet: Inlet[LilaIn.Round] = b.add(lilaInRound).in

      // tickers

      val UserTicker: SourceShape[sm.UserSM.Input] = b.add {
        Source.tick(7.seconds, 5.seconds, sm.UserSM.PublishDisconnects)
      }

      // format: OFF

      // source      broadcast   collect      merge    machine     merge           sink
      SiteOut     ~> SOBroad  ~> SOBus                          ~> ClientBus    ~> BusPublish
                     SOBroad  ~> SOFen     ~> Fen
                     SOBroad  ~> SOUser    ~> User
      ClientToFen                          ~> Fen   ~> FenSM    ~> SiteIn
      ClientToUser                         ~> User  ~> UserSM   ~> SiteIn
      ClientToFriends                               ~> Friends  ~> SiteIn
      ClientToNotified                              ~> Notified ~> SiteIn
      ClientToLag                                   ~> Lag      ~> SiteIn
      ClientToSite                                              ~> SiteIn       ~> SiteInlet

      SiteOutlet  ~> SiteOut

      LobbyOutlet ~> LOBroad  ~> ToSiteOut ~> SiteOut
                     LOBroad  ~> LOUser    ~> User
                     LOBroad  ~> LOBus                          ~> ClientBus
                     LOBroad                                                    ~> LobbyPong
                     ClientToLobby                  ~> Lobby                    ~> LobbyInlet

      SimulOutlet ~> SimBroad ~> ToSiteOut ~> SiteOut
                     SimBroad ~> RoomBus                        ~> ClientBus
                     SimBroad                                                   ~> EventStore
      ClientToSimul           ~> SimKeepAlive                                   ~> SimulInlet

      TourOutlet  ~> TouBroad ~> ToSiteOut ~> SiteOut
                     TouBroad ~> RoomBus                        ~> ClientBus
                     TouBroad                                                   ~> EventStore
                     TouBroad ~> TouCrowd  ~> TouCrowdB         ~> TouRemind    ~> User
                                              TouCrowdB         ~> TourIn       ~> TourInlet
      ClientToTour            ~> TourKeepAlive                  ~> TourIn

      StudyOutlet ~> StuBroad ~> ToSiteOut ~> SiteOut
                     StuBroad ~> RoomBus                        ~> ClientBus
                     StuBroad                                                   ~> EventStore
                     StuBroad ~> StuCrowd                       ~> StudyIn      ~> StudyInlet
      ClientToStudy           ~> StuKeepAlive                   ~> StudyIn
      ClientToStudyDoor       ~> StudyDoor                      ~> StudyIn

      RoundOutlet ~> RouBroad ~> ToSiteOut ~> SiteOut
                     RouBroad ~> RoundBus
      ClientToRound                                             ~> RoundIn
      ClientToRoundCrowd                            ~> RouCrowd ~> RouCrowdBroad
                     RouCrowdBroad                              ~> RouCrowdJson ~> ClientBus
                     RouCrowdBroad                  ~> RouOns   ~> RoundIn      ~> RoundInlet

      ClientToCrowd                                 ~> Crowd    ~> CrowdJson    ~> ClientBus
      UserTicker                           ~> User

      // format: ON

      ClosedShape
    })

  def LobbyFlow = Flow.fromGraph(GraphDSL.create() { implicit b =>
    val broad = b.add(Broadcast[LilaIn.Lobby](3))
    val connect = b.add {
      Flow[LilaIn.Lobby].collect {
        case con: LilaIn.ConnectSri => con
      }.groupedWithin(6, 479.millis) map { con =>
        LilaIn.ConnectSris(con.map { c => (c.sri, c.userId) })
      }
    }
    val disconnect = b.add {
      Flow[LilaIn.Lobby].collect {
        case con: LilaIn.DisconnectSri => con
      }.groupedWithin(50, 487.millis) map { dis =>
        LilaIn.DisconnectSris(dis.map(_.sri))
      }
    }
    val tell = b.add {
      Flow[LilaIn.Lobby].collect {
        case con: LilaIn.TellSri => con
      }
    }
    val merge = b.add(Merge[LilaIn.Lobby](3))

    broad.out(0) ~> connect ~> merge.in(0)
    broad.out(1) ~> disconnect ~> merge.in(1)
    broad.out(2) ~> tell ~> merge.in(2)

    FlowShape(broad.in, merge.out)
  })

  // If the buffer is full when a new element arrives,
  // drops the oldest element from the buffer to make space for the new element.
  private val overflow = OverflowStrategy.dropHead
}
