package lila.ws

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import GraphDSL.Implicits._
import javax.inject._

import ipc._

@Singleton
final class Graph @Inject() (system: akka.actor.ActorSystem) {

  private val bus = Bus(system)

  // If the buffer is full when a new element arrives,
  // drops the oldest element from the buffer to make space for the new element.
  private val overflow = OverflowStrategy.dropHead

  def main(lilaInSite: Sink[LilaIn, _], lilaInLobby: Sink[LilaIn, _]) = RunnableGraph.fromGraph(GraphDSL.create(
    Source.queue[SiteOut](8192, overflow), // from site chan
    Source.queue[LobbyOut](8192, overflow), // from lobby chan
    Source.queue[LilaIn](8192, overflow), // clients -> lila:site (forward, notified)
    Source.queue[LilaIn](8192, overflow), // clients -> lila:lobby
    Source.queue[LagSM.Input](256, overflow), // clients -> lag machine
    Source.queue[FenSM.Input](256, overflow), // clients -> fen machine
    Source.queue[CountSM.Input](256, overflow), // clients -> count machine
    Source.queue[UserSM.Input](256, overflow) // clients -> user machine
  ) {
      case (siteOut, lobbyOut, lilaInSite, lilaInLobby, lag, fen, count, user) =>
        (siteOut, lobbyOut, Stream.Queues(lilaInSite, lilaInLobby, lag, fen, count, user))
    } { implicit b => (SiteOutlet, LobbyOutlet, ClientToLilaSite, ClientToLilaLobby, ClientToLag, ClientToFen, ClientToCount, ClientToUser) =>

      def merge[A](ports: Int): UniformFanInShape[A, A] = b.add(Merge[A](ports))

      // site

      val SiteMerge = merge[SiteOut](2)

      val SOBroad: UniformFanOutShape[SiteOut, SiteOut] = b.add(Broadcast[SiteOut](5))

      val SOBus: FlowShape[SiteOut, Bus.Msg] = b.add {
        Flow[SiteOut].collect {
          case LilaOut.Mlat(millis) => Bus.msg(ClientIn.Mlat(millis), _.mlat)
          case LilaOut.TellFlag(flag, payload) => Bus.msg(ClientIn.Payload(payload), _ flag flag)
          case LilaOut.TellSri(sri, payload) => Bus.msg(ClientIn.Payload(payload), _ sri sri)
          case LilaOut.TellAll(payload) => Bus.msg(ClientIn.Payload(payload), _.all)
        }
      }

      val BusMerge = merge[Bus.Msg](2)

      val BusPublish: SinkShape[Bus.Msg] = b.add {
        Sink.foreach[Bus.Msg](bus.publish)
      }

      val SOUser: FlowShape[LilaOut, UserSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.TellUsers(users, json) => UserSM.TellMany(users, ClientIn.Payload(json))
          case LilaOut.DisconnectUser(user) => UserSM.Kick(user)
        }
      }

      val UserMerge = merge[UserSM.Input](2)

      val User: FlowShape[UserSM.Input, LilaIn] = b.add {
        Flow[UserSM.Input].scan(UserSM.State())(UserSM.apply).mapConcat(_.emit.toList)
      }

      val SOFen: FlowShape[LilaOut, FenSM.Input] = b.add {
        Flow[LilaOut].collect {
          case move: LilaOut.Move => FenSM.Move(move)
        }
      }

      val FenMerge = merge[FenSM.Input](2)

      val Fen: FlowShape[FenSM.Input, LilaIn] = b.add {
        Flow[FenSM.Input].scan(FenSM.State())(FenSM.apply).mapConcat(_.emit.toList)
      }

      val SOLag: FlowShape[LilaOut, LagSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => LagSM.Publish
        }
      }

      val LagMerge = merge[LagSM.Input](2)

      val Lag: FlowShape[LagSM.Input, LilaIn] = b.add {
        Flow[LagSM.Input].scan(LagSM.State())(LagSM.apply).mapConcat(_.emit.toList)
      }

      val SOCount: FlowShape[LilaOut, CountSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => CountSM.Publish
        }
      }

      val CountMerge = merge[CountSM.Input](2)

      val Count: FlowShape[CountSM.Input, LilaIn] = b.add {
        Flow[CountSM.Input].scan(CountSM.State())(CountSM.apply).mapConcat(_.emit.toList)
      }

      val LIMerge = merge[LilaIn](5)

      val SiteInlet: Inlet[LilaIn] = b.add(lilaInSite).in

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

      val LobbyInlet: Inlet[LilaIn] = b.add(lilaInLobby).in

    // format: OFF

    // source      broadcast  collect    merge        machine   merge      sink
    SiteMerge   ~> SOBroad ~> SOBus                          ~> BusMerge ~> BusPublish
                   SOBroad ~> SOFen   ~> FenMerge
                   SOBroad ~> SOLag   ~> LagMerge
                   SOBroad ~> SOUser  ~> UserMerge
                   SOBroad ~> SOCount ~> CountMerge
    ClientToFen                       ~> FenMerge   ~> Fen   ~> LIMerge
    ClientToLag                       ~> LagMerge   ~> Lag   ~> LIMerge
    ClientToUser                      ~> UserMerge  ~> User  ~> LIMerge
    ClientToCount                     ~> CountMerge ~> Count ~> LIMerge
    ClientToLilaSite                                         ~> LIMerge  ~> SiteInlet

    SiteOutlet  ~> SiteMerge

    LobbyOutlet ~> LOBroad ~> LOSite  ~> SiteMerge // merge site messages coming from lobby input into site input
                   LOBroad ~> LOBus                          ~> BusMerge
                   LOBroad                                               ~> LobbyPong
    ClientToLilaLobby                                                    ~> LobbyInlet

    ClosedShape
  })
}
