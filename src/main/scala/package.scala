package lila.ws

import org.apache.pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }

export ornicar.scalalib.newtypes.*
export ornicar.scalalib.zeros.*
export ornicar.scalalib.extensions.*
export ornicar.scalalib.time.*
export scala.concurrent.{ ExecutionContext as Executor, Future, Promise }
export scala.concurrent.duration.{ DurationInt, FiniteDuration }
export scala.concurrent.ExecutionContext.parasitic

type Emit[A] = Function[A, Unit]

type ClientSystem   = ActorSystem[Clients.Control]
type ClientBehavior = Behavior[ipc.ClientMsg]
type Client         = ActorRef[ipc.ClientMsg]
type ClientEmit     = Emit[ipc.ClientIn]

type ~[+A, +B] = Tuple2[A, B]
object ~ :
  def apply[A, B](x: A, y: B)                              = Tuple2(x, y)
  def unapply[A, B](x: Tuple2[A, B]): Option[Tuple2[A, B]] = Some(x)

def nowSeconds: Int = (System.currentTimeMillis() / 1000).toInt
val startedAtMillis = System.currentTimeMillis()
