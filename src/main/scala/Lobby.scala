package lila.ws

import akka.actor.typed.Scheduler
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import ipc.ClientIn.LobbyPong
import ipc.LilaIn

@Singleton
final class Lobby @Inject() (
    lila: Lila,
    groupedWithin: GroupedWithin
)(implicit scheduler: Scheduler, ec: ExecutionContext) {

  private val lilaIn = lila.emit.lobby

  val connect = groupedWithin[(Sri, Option[User.ID])](6, 479.millis) { connects =>
    lilaIn(LilaIn.ConnectSris(connects))
  }

  val disconnect = groupedWithin[Sri](50, 487.millis) { sris =>
    lilaIn(LilaIn.DisconnectSris(sris))
  }

  object pong {

    private var value = LobbyPong(0, 0)

    def get = value

    def update(f: LobbyPong => LobbyPong): Unit = {
      value = f(value)
    }
  }
}
