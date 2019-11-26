package lila.ws

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ Scheduler, Behavior, SpawnProtocol, ActorRef, ActorSystem, Props }
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future

object Spawner {

  private val actor: Behavior[SpawnProtocol.Command] = SpawnProtocol()

  private val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(actor, "clients")
  private implicit val timeout: Timeout = Timeout(3.seconds)
  private implicit val scheduler: Scheduler = system.scheduler

  def apply[B](behavior: Behavior[B]): Future[ActorRef[B]] =
    system.ask(SpawnProtocol.Spawn(behavior = behavior, name = "", props = Props.empty, _))
}
