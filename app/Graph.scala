package lila.ws

import akka.stream._
import akka.stream.scaladsl._
import GraphDSL.Implicits._
import javax.inject._

import ipc._

@Singleton
final class Graph @Inject() (system: akka.actor.ActorSystem) {

  private val bus = Bus(system)

  def main(lilaIn: Sink[LilaIn, _]) = RunnableGraph.fromGraph(GraphDSL.create(
    Source.queue[LilaOut](1024, OverflowStrategy.dropNew),
    Source.queue[LilaIn](4096, OverflowStrategy.dropNew),
    Source.queue[LagSM.Input](32, OverflowStrategy.dropNew),
    Source.queue[FenSM.Input](32, OverflowStrategy.dropNew),
    Source.queue[CountSM.Input](32, OverflowStrategy.dropNew),
    Source.queue[UserSM.Input](32, OverflowStrategy.dropNew)
  )((_, _, _, _, _, _)) { implicit builder => (LilaOutlet, ClientToLila, ClientToLag, ClientToFen, ClientToCount, ClientToUser) =>

      val LOB: UniformFanOutShape[LilaOut, LilaOut] = builder.add(Broadcast[LilaOut](5))

      val LilaOutEffects: SinkShape[LilaOut] = builder.add {
        Sink.foreach[LilaOut] {
          case LilaOut.Mlat(millis) => bus.publish(ClientIn.Mlat(millis), _.mlat)
          case LilaOut.TellFlag(flag, json) => bus.publish(ClientIn.AnyJson(json), _ flag flag)
          case LilaOut.TellSri(sri, json) => bus.publish(ClientIn.AnyJson(json), _ sri sri)
          case LilaOut.TellAll(json) => bus.publish(ClientIn.AnyJson(json), _.all)
          case _ =>
        }
      }

      val LOUser: FlowShape[LilaOut, UserSM.Input] = builder.add {
        Flow[LilaOut].collect {
          case LilaOut.TellUser(user, json) => UserSM.TellOne(user, ClientIn.AnyJson(json))
          case LilaOut.TellUsers(users, json) => UserSM.TellMany(users, ClientIn.AnyJson(json))
          case LilaOut.DisconnectUser(user) => UserSM.Kick(user)
        }
      }

      val UserIM: UniformFanInShape[UserSM.Input, UserSM.Input] = builder.add(Merge[UserSM.Input](2))

      val User: FlowShape[UserSM.Input, LilaIn] = builder.add {
        Flow[UserSM.Input].scan(UserSM.State())(UserSM.apply).mapConcat(_.emit.toList)
      }

      val LOFen: FlowShape[LilaOut, FenSM.Input] = builder.add {
        Flow[LilaOut].collect {
          case move: LilaOut.Move => FenSM.Move(move)
        }
      }

      val FenIM: UniformFanInShape[FenSM.Input, FenSM.Input] = builder.add(Merge[FenSM.Input](2))

      val Fen: FlowShape[FenSM.Input, LilaIn] = builder.add {
        Flow[FenSM.Input].scan(FenSM.State())(FenSM.apply).mapConcat(_.emit.toList)
      }

      val LOLag: FlowShape[LilaOut, LagSM.Input] = builder.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => LagSM.Publish
        }
      }

      val LagIM: UniformFanInShape[LagSM.Input, LagSM.Input] = builder.add(Merge[LagSM.Input](2))

      val Lag: FlowShape[LagSM.Input, LilaIn] = builder.add {
        Flow[LagSM.Input].scan(LagSM.State())(LagSM.apply).mapConcat(_.emit.toList)
      }

      val LOCount: FlowShape[LilaOut, CountSM.Input] = builder.add {
        Flow[LilaOut].collect {
          case LilaOut.Mlat(millis) => CountSM.Publish
        }
      }

      val CountIM: UniformFanInShape[CountSM.Input, CountSM.Input] = builder.add(Merge[CountSM.Input](2))

      val Count: FlowShape[CountSM.Input, LilaIn] = builder.add {
        Flow[CountSM.Input].scan(CountSM.State())(CountSM.apply).mapConcat(_.emit.toList)
      }

      val LIM: UniformFanInShape[LilaIn, LilaIn] = builder.add(Merge[LilaIn](5))

      val LilaInlet: Inlet[LilaIn] = builder.add(lilaIn).in

    // format: OFF
    LilaOutlet ~> LOB ~> LilaOutEffects
                  LOB ~> LOFen   ~> FenIM
                  LOB ~> LOLag   ~> LagIM
                  LOB ~> LOCount ~> CountIM
                  LOB ~> LOUser  ~> UserIM
                  ClientToUser   ~> UserIM ~> User ~> LIM
                                      ClientToLila ~> LIM
                       ClientToLag ~> LagIM ~> Lag ~> LIM
                 ClientToCount ~> CountIM ~> Count ~> LIM
                       ClientToFen ~> FenIM ~> Fen ~> LIM ~> LilaInlet

    ClosedShape
  })
}
