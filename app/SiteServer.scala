package lila.ws

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.stream.scaladsl._
import akka.stream.{ Materializer, OverflowStrategy }
import javax.inject._
import play.api.Configuration
import play.api.mvc.RequestHeader
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class SiteServer @Inject() (
    config: Configuration,
    auth: Auth,
    stream: Stream
)(implicit
    ec: ExecutionContext,
    system: akka.actor.ActorSystem,
    mat: Materializer
) {

  private val bus = Bus(system)
  private val queues = stream.start

  def connect(req: RequestHeader, sri: Sri, flag: Option[Flag]) =
    auth(req) map { user =>
      actorFlow { out =>
        SiteClientActor.empty(SiteClientActor.Deps(out, queues, sri, flag, user, bus))
      }
    }

  private def actorFlow(
    behaviour: akka.actor.ActorRef => Behavior[ClientMsg],
    bufferSize: Int = 16,
    overflowStrategy: OverflowStrategy = OverflowStrategy.dropNew
  )(implicit factory: akka.actor.ActorRefFactory, mat: Materializer): Flow[ClientOut, ClientIn, _] = {

    import akka.actor.{ Status, Terminated, OneForOneStrategy, SupervisorStrategy }

    val (outActor, publisher) = Source.actorRef[ClientIn](bufferSize, overflowStrategy)
      .toMat(Sink.asPublisher(false))(Keep.both).run()

    Flow.fromSinkAndSource(
      Sink.actorRef(factory.actorOf(akka.actor.Props(new akka.actor.Actor {
        val flowActor: ActorRef[ClientMsg] = context.spawn(behaviour(outActor), "flowActor")
        context.watch(flowActor)

        def receive = {
          case Status.Success(_) | Status.Failure(_) => flowActor ! ClientFlow.Disconnect
          case Terminated(_) => context.stop(self)
          case incoming: ClientOut => flowActor ! incoming
        }

        override def supervisorStrategy = OneForOneStrategy() {
          case _ => SupervisorStrategy.Stop
        }
      })), Status.Success(())),
      Source.fromPublisher(publisher)
    )
  }
}
