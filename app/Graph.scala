package lila.ws

import akka.stream._
import akka.stream.scaladsl._
import GraphDSL.Implicits._
import scala.concurrent.duration._

import ipc._

object Graph {

  type GraphType = RunnableGraph[(SourceQueueWithComplete[SiteOut], SourceQueueWithComplete[LobbyOut], Stream.Queues)]

  def apply(
    lilaInSite: Sink[LilaIn.Site, _],
    lilaInLobby: Sink[LilaIn.Lobby, _]
  )(implicit system: akka.actor.ActorSystem): GraphType = RunnableGraph.fromGraph(GraphDSL.create(
    Source.queue[SiteOut](8192, overflow), // from site chan
    Source.queue[LobbyOut](8192, overflow), // from lobby chan
    Source.queue[LilaIn.Notified](128, overflow), // clients -> lila:site notified
    Source.queue[LilaIn.Friends](128, overflow), // clients -> lila:site friends
    Source.queue[LilaIn.Site](8192, overflow), // clients -> lila:site (forward)
    Source.queue[LilaIn.Lobby](8192, overflow), // clients -> lila:lobby
    Source.queue[sm.LagSM.Input](256, overflow), // clients -> lag machine
    Source.queue[sm.FenSM.Input](256, overflow), // clients -> fen machine
    Source.queue[sm.CountSM.Input](256, overflow), // clients -> count machine
    Source.queue[sm.UserSM.Input](256, overflow) // clients -> user machine
  ) {
      case (siteOut, lobbyOut, lilaInNotified, lilaInFriends, lilaInSite, lilaInLobby, lag, fen, count, user) => (
        siteOut, lobbyOut,
        Stream.Queues(lilaInNotified, lilaInFriends, lilaInSite, lilaInLobby, lag, fen, count, user)
      )
    } { implicit b => (SiteOutlet, LobbyOutlet, ClientToNotified, ClientToFriends, ClientToSite, ClientToLobby, ClientToLag, ClientToFen, ClientToCount, ClientToUser) =>

      def merge[A](ports: Int): UniformFanInShape[A, A] = b.add(Merge[A](ports))

      def machine[State, Input, Emit](m: sm.StateMachine[State, Input, Emit]): FlowShape[Input, Emit] = b.add {
        Flow[Input].scan(m.zero)(m.apply).mapConcat(m.emit)
      }

      // site

      val SiteOut = merge[SiteOut](2)

      val SOBroad: UniformFanOutShape[SiteOut, SiteOut] = b.add(Broadcast[SiteOut](5))

      val SOBus: FlowShape[SiteOut, Bus.Msg] = b.add {
        Flow[SiteOut].collect {
          case LilaOut.Mlat(millis) => Bus.msg(ClientIn.Mlat(millis), _.mlat)
          case LilaOut.TellFlag(flag, payload) => Bus.msg(ClientIn.Payload(payload), _ flag flag)
          case LilaOut.TellSri(sri, payload) => Bus.msg(ClientIn.Payload(payload), _ sri sri)
          case LilaOut.TellAll(payload) => Bus.msg(ClientIn.Payload(payload), _.all)
        }
      }

      val ClientBus = merge[Bus.Msg](2)

      val BusPublish: SinkShape[Bus.Msg] = b.add {
        val bus = Bus(system)
        Sink.foreach[Bus.Msg](bus.publish)
      }

      val SOUser: FlowShape[LilaOut, sm.UserSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.TellUsers(users, json) => sm.UserSM.TellMany(users, ClientIn.Payload(json))
          case LilaOut.DisconnectUser(user) => sm.UserSM.Kick(user)
        }
      }

      val User = merge[sm.UserSM.Input](3)

      val UserSM: FlowShape[sm.UserSM.Input, LilaIn.Site] = machine(sm.UserSM.machine)

      val SOFen: FlowShape[LilaOut, sm.FenSM.Input] = b.add {
        Flow[LilaOut].collect {
          case move: LilaOut.Move => sm.FenSM.Move(move)
        }
      }

      val Fen = merge[sm.FenSM.Input](2)

      val FenSM: FlowShape[sm.FenSM.Input, LilaIn.Site] = machine(sm.FenSM.machine)

      val SOLag: FlowShape[LilaOut, sm.LagSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => sm.LagSM.Publish
        }
      }

      val Lag = merge[sm.LagSM.Input](2)

      val LagSM: FlowShape[sm.LagSM.Input, LilaIn.Site] = machine(sm.LagSM.machine)

      val SOCount: FlowShape[LilaOut, sm.CountSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => sm.CountSM.Publish
        }
      }

      val Count = merge[sm.CountSM.Input](2)

      val CountSM: FlowShape[sm.CountSM.Input, LilaIn.Site] = machine(sm.CountSM.machine)

      val Notified: FlowShape[LilaIn.Notified, LilaIn.NotifiedBatch] = b.add {
        Flow[LilaIn.Notified].groupedWithin(40, 1001.millis) map { notifs =>
          LilaIn.NotifiedBatch(notifs.map(_.userId))
        }
      }

      val Friends: FlowShape[LilaIn.Friends, LilaIn.FriendsBatch] = b.add {
        Flow[LilaIn.Friends].groupedWithin(10, 503.millis) map { friends =>
          LilaIn.FriendsBatch(friends.map(_.userId))
        }
      }

      val SiteIn = merge[LilaIn.Site](7)

      val SiteInlet: Inlet[LilaIn.Site] = b.add(lilaInSite).in

      // lobby

      val LOBroad: UniformFanOutShape[LobbyOut, LobbyOut] = b.add(Broadcast[LobbyOut](3))

      // forward site messages coming from lobby chan
      val LOSite: FlowShape[LobbyOut, SiteOut] = b.add {
        Flow[LobbyOut].collect {
          case siteOut: SiteOut => siteOut
        }
      }

      val LOBus: FlowShape[LobbyOut, Bus.Msg] = b.add {
        Flow[LobbyOut].mapConcat {
          case LilaOut.TellLobby(payload) => List(Bus.msg(ClientIn.Payload(payload), _.lobby))
          case LilaOut.DisconnectSri(sri) => List(Bus.msg(ClientCtrl.Disconnect, _ sri sri))
          case LilaOut.TellSris(sris, payload) => sris map { sri =>
            Bus.msg(ClientIn.Payload(payload), _ sri sri)
          }
          case _ => Nil
        }
      }

      val LobbyPong: SinkShape[LobbyOut] = b.add {
        Sink.foreach[LobbyOut] {
          case LilaOut.NbMembers(nb) => LobbyPongStore.update(_.copy(members = nb))
          case LilaOut.NbRounds(nb) => LobbyPongStore.update(_.copy(rounds = nb))
          case _ =>
        }
      }

      val LobbyInlet: Inlet[LilaIn.Lobby] = b.add(lilaInLobby).in

      val UserTicker: SourceShape[sm.UserSM.Input] = b.add {
        Source.tick(7.seconds, 5.seconds, sm.UserSM.PublishDisconnects)
      }

      // format: OFF

      // source      broadcast  collect    merge    machine     merge       sink
      SiteOut     ~> SOBroad ~> SOBus                        ~> ClientBus ~> BusPublish
                     SOBroad ~> SOFen   ~> Fen
                     SOBroad ~> SOLag   ~> Lag
                     SOBroad ~> SOUser  ~> User
                     SOBroad ~> SOCount ~> Count
      ClientToFen                       ~> Fen   ~> FenSM    ~> SiteIn
      ClientToLag                       ~> Lag   ~> LagSM    ~> SiteIn
      ClientToUser                      ~> User  ~> UserSM   ~> SiteIn
      ClientToCount                     ~> Count ~> CountSM  ~> SiteIn
      ClientToFriends                            ~> Friends  ~> SiteIn
      ClientToNotified                           ~> Notified ~> SiteIn
      ClientToSite                                           ~> SiteIn    ~> SiteInlet

      SiteOutlet  ~> SiteOut

      LobbyOutlet ~> LOBroad ~> LOSite  ~> SiteOut // merge site messages coming from lobby input into site input
                     LOBroad ~> LOBus                        ~> ClientBus
                     LOBroad                                              ~> LobbyPong
      ClientToLobby                                                       ~> LobbyInlet

      UserTicker                        ~> User

      // format: ON

      ClosedShape
    })

  // If the buffer is full when a new element arrives,
  // drops the oldest element from the buffer to make space for the new element.
  private val overflow = OverflowStrategy.dropHead
}
