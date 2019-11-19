package lila

package object ws {

  type Emit[A] = Function[A, Unit]

  type Client = akka.actor.typed.ActorRef[ipc.ClientMsg]

  type ~[+A, +B] = Tuple2[A, B]
  object ~ {
    def apply[A, B](x: A, y: B) = Tuple2(x, y)
    def unapply[A, B](x: Tuple2[A, B]): Option[Tuple2[A, B]] = Some(x)
  }
}
