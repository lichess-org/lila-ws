package lila.ws

import akka.actor.Scheduler
import javax.inject._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import ipc._
import util.Util.nowSeconds

@Singleton
final class KeepAlive @Inject() (lila: Lila.Emits, scheduler: Scheduler)(implicit ec: ExecutionContext) {

  import KeepAlive._

  val study = new AliveRooms
  val tour = new AliveRooms
  val simul = new AliveRooms
  val challenge = new AliveRooms

  scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds) { () =>
    lila.study(study.getAndClear)
    lila.tour(tour.getAndClear)
    lila.simul(simul.getAndClear)
    lila.challenge(challenge.getAndClear)
  }
}

object KeepAlive {

  type Seconds = Int

  final class AliveRooms {

    private var rooms = Set.empty[RoomId]

    def apply(roomId: RoomId) = rooms = rooms + roomId

    def getAndClear: LilaIn.KeepAlives = {
      val ret = LilaIn.KeepAlives(rooms)
      rooms = Set.empty
      ret
    }
  }
}
