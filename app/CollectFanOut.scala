package lila.ws

import akka.NotUsed
import akka.stream.Graph
import akka.stream.scaladsl.{ Flow, GraphDSL, Broadcast }

import akka.stream.{ FanOutShape, Inlet, Outlet }

final class Collect2FanOutShape[In, Out1, Out2](_init: FanOutShape.Init[In]) extends FanOutShape[In](_init) {
  def this(in: Inlet[In], out1: Outlet[Out1], out2: Outlet[Out2]) = this(FanOutShape.Ports(in, out1 :: out2 :: Nil))

  override protected def construct(init: FanOutShape.Init[In]): FanOutShape[In] = new Collect2FanOutShape(init)
  // override def deepCopy(): TwoTypesFanOutShape[In, Out1, Out2] = super.deepCopy().asInstanceOf[TwoTypesFanOutShape[In, Out1, Out2]]

  val out1: Outlet[Out1] = newOutlet[Out1]("out1")
  val out2: Outlet[Out2] = newOutlet[Out2]("out2")
}

object CollectFanOut {

  def collect2[In, Out1, Out2](
    collect1: PartialFunction[In, Out1],
    collect2: PartialFunction[In, Out2]
  ): Graph[Collect2FanOutShape[In, Out1, Out2], NotUsed] =
    GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val input = b.add(Flow[In])
      val bcast = b.add(Broadcast[In](outputPorts = 2))
      val out1 = b.add(Flow[In].collect(collect1))
      val out2 = b.add(Flow[In].collect(collect2))

      // format: OFF
      input ~> bcast ~> out1
               bcast ~> out2
      // format: ON

      new Collect2FanOutShape(input.in, out1.out, out2.out)
    }

}
