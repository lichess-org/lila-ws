package lila.ws

package object ipc {

  trait ClientMsg

  sealed trait ClientFlow extends ClientMsg

  object ClientFlow {
    case object Disconnect extends ClientFlow
  }
}
