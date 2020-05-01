package lila.ws

import akka.actor.typed.Scheduler
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import ipc._

final class KeepAlive(lila: Lila, scheduler: Scheduler)(implicit ec: ExecutionContext) {

  import KeepAlive._

  val study     = new AliveRooms
  val tour      = new AliveRooms
  val simul     = new AliveRooms
  val challenge = new AliveRooms
  val team      = new AliveRooms
  val swiss     = new AliveRooms

  scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds) { () =>
    lila.emit.study(study.getAndClear)
    lila.emit.tour(tour.getAndClear)
    lila.emit.simul(simul.getAndClear)
    lila.emit.challenge(challenge.getAndClear)
    lila.emit.team(team.getAndClear)
    lila.emit.swiss(swiss.getAndClear)
  }
}

object KeepAlive {

  type Seconds = Int

  final class AliveRooms {

    private val rooms = collection.mutable.Set[RoomId]()

    def apply(roomId: RoomId) = rooms += roomId

    def getAndClear: LilaIn.KeepAlives = {
      val ret = LilaIn.KeepAlives(rooms.toSet)
      rooms.clear
      ret
    }
  }
}
