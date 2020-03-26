package lila

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }

package object ws {

  type Emit[A] = Function[A, Unit]

  type ClientSystem   = ActorSystem[Clients.Control]
  type ClientBehavior = Behavior[ipc.ClientMsg]
  type Client         = ActorRef[ipc.ClientMsg]
  type ClientEmit     = Emit[ipc.ClientIn]

  type ~[+A, +B] = Tuple2[A, B]
  object ~ {
    def apply[A, B](x: A, y: B)                              = Tuple2(x, y)
    def unapply[A, B](x: Tuple2[A, B]): Option[Tuple2[A, B]] = Some(x)
  }

  @inline implicit def toOrnicarAddKcombinator[A](any: A) =
    new ornicarAddKcombinator(any)
}

final class ornicarAddKcombinator[A](private val any: A) extends AnyVal {
  def kCombinator(sideEffect: A => Unit): A = {
    sideEffect(any)
    any
  }
  def ~(sideEffect: A => Unit): A = kCombinator(sideEffect)
  def pp: A                       = kCombinator(println)
  def pp(msg: String): A          = kCombinator(a => println(s"[$msg] $a"))
}
