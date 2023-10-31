package lila.ws

import org.apache.pekko.actor.typed.Scheduler

import ipc.*

final class KeepAlive(lila: Lila, scheduler: Scheduler)(using Executor):

  import KeepAlive.*

  val study     = new AliveRooms
  val tour      = new AliveRooms
  val simul     = new AliveRooms
  val challenge = new AliveRooms
  val team      = new AliveRooms
  val swiss     = new AliveRooms
  val racer     = new AliveRooms

  scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds) { () =>
    lila.emit.study(study.getAndClear)
    lila.emit.tour(tour.getAndClear)
    lila.emit.simul(simul.getAndClear)
    lila.emit.challenge(challenge.getAndClear)
    lila.emit.team(team.getAndClear)
    lila.emit.swiss(swiss.getAndClear)
    lila.emit.racer(racer.getAndClear)
  }

object KeepAlive:

  type Seconds = Int

  final class AliveRooms:

    private val rooms = collection.mutable.Set[RoomId]()

    def apply(roomId: RoomId) = rooms += roomId

    def getAndClear: LilaIn.KeepAlives =
      val ret = LilaIn.KeepAlives(rooms.toSet)
      rooms.clear()
      ret
