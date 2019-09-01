package lila.ws

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

  def main(lilaIn: Sink[LilaIn, _]) = RunnableGraph.fromGraph(GraphDSL.create(
    Source.queue[LilaOut](8192, overflow), // from lila redis
    Source.queue[LilaIn](8192, overflow), // clients -> lila (forward, notified)
    Source.queue[LagSM.Input](256, overflow), // clients -> lag machine
    Source.queue[FenSM.Input](256, overflow), // clients -> fen machine
    Source.queue[CountSM.Input](256, overflow), // clients -> count machine
    Source.queue[UserSM.Input](256, overflow) // clients -> user machine
  )((_, _, _, _, _, _)) { implicit b => (LilaOutlet, ClientToLila, ClientToLag, ClientToFen, ClientToCount, ClientToUser) =>

      val LOBroad: UniformFanOutShape[LilaOut, LilaOut] = b.add(Broadcast[LilaOut](5))

      val LOBus: FlowShape[LilaOut, Bus.Msg] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => Bus.msg(ClientIn.Mlat(millis), _.mlat)
          case LilaOut.TellFlag(flag, json) => Bus.msg(ClientIn.AnyJson(json), _ flag flag)
          case LilaOut.TellSri(sri, json) => Bus.msg(ClientIn.AnyJson(json), _ sri sri)
          case LilaOut.TellAll(json) => Bus.msg(ClientIn.AnyJson(json), _.all)
        }
      }

      val BusPublish: SinkShape[Bus.Msg] = b.add {
        Sink.foreach[Bus.Msg](bus.publish)
      }

      val LOUser: FlowShape[LilaOut, UserSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.TellUser(user, json) => UserSM.TellOne(user, ClientIn.AnyJson(json))
          case LilaOut.TellUsers(users, json) => UserSM.TellMany(users, ClientIn.AnyJson(json))
          case LilaOut.DisconnectUser(user) => UserSM.Kick(user)
        }
      }

      val UserMerge: UniformFanInShape[UserSM.Input, UserSM.Input] = b.add(Merge[UserSM.Input](2))

      val User: FlowShape[UserSM.Input, LilaIn] = b.add {
        Flow[UserSM.Input].scan(UserSM.State())(UserSM.apply).mapConcat(_.emit.toList)
      }

      val LOFen: FlowShape[LilaOut, FenSM.Input] = b.add {
        Flow[LilaOut].collect {
          case move: LilaOut.Move => FenSM.Move(move)
        }
      }

      val FenMerge: UniformFanInShape[FenSM.Input, FenSM.Input] = b.add(Merge[FenSM.Input](2))

      val Fen: FlowShape[FenSM.Input, LilaIn] = b.add {
        Flow[FenSM.Input].scan(FenSM.State())(FenSM.apply).mapConcat(_.emit.toList)
      }

      val LOLag: FlowShape[LilaOut, LagSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => LagSM.Publish
        }
      }

      val LagMerge: UniformFanInShape[LagSM.Input, LagSM.Input] = b.add(Merge[LagSM.Input](2))

      val Lag: FlowShape[LagSM.Input, LilaIn] = b.add {
        Flow[LagSM.Input].scan(LagSM.State())(LagSM.apply).mapConcat(_.emit.toList)
      }

      val LOCount: FlowShape[LilaOut, CountSM.Input] = b.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => CountSM.Publish
        }
      }

      val CountMerge: UniformFanInShape[CountSM.Input, CountSM.Input] = b.add(Merge[CountSM.Input](2))

      val Count: FlowShape[CountSM.Input, LilaIn] = b.add {
        Flow[CountSM.Input].scan(CountSM.State())(CountSM.apply).mapConcat(_.emit.toList)
      }

      val LIMerge: UniformFanInShape[LilaIn, LilaIn] = b.add(Merge[LilaIn](5))

      val LilaInlet: Inlet[LilaIn] = b.add(lilaIn).in

    // format: OFF
    LilaOutlet ~> LOBroad ~> LOBus   ~> BusPublish
                  LOBroad ~> LOFen   ~> FenMerge
                  LOBroad ~> LOLag   ~> LagMerge
                  LOBroad ~> LOCount ~> CountMerge
                  LOBroad ~> LOUser  ~> UserMerge
                         ClientToFen ~> FenMerge   ~> Fen   ~> LIMerge
                         ClientToLag ~> LagMerge   ~> Lag   ~> LIMerge
                       ClientToCount ~> CountMerge ~> Count ~> LIMerge
                        ClientToUser ~> UserMerge  ~> User  ~> LIMerge
                                               ClientToLila ~> LIMerge ~> LilaInlet

    ClosedShape
  })
}
