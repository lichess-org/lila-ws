package lila.ws

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, SpawnProtocol, ActorRef, ActorSystem, Props }
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

import ipc.ClientMsg

object Spawner {

  val main: Behavior[SpawnProtocol] =
    Behaviors.setup { context =>
      // Start initial tasks
      // context.spawn(...)

      SpawnProtocol.behavior
    }

  // private val actor = SpawnProtocol()

  private val system: ActorSystem[SpawnProtocol] = ActorSystem(main, "clients")
  private implicit val timeout: Timeout = Timeout(3.seconds)
  private implicit val scheduler = system.scheduler

  def apply[B](behavior: Behavior[B])(implicit ec: ExecutionContext): Future[ActorRef[B]] =
    system.ask(SpawnProtocol.Spawn(behavior = behavior, name = "", props = Props.empty, _))
}
