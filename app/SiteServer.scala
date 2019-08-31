package lila.ws

import akka.actor._
import akka.stream.Materializer
import javax.inject._
import play.api.Configuration
import play.api.libs.streams.ActorFlow
import play.api.mvc.RequestHeader
import scala.concurrent.{ ExecutionContext, Future }

import ipc._

@Singleton
final class SiteServer @Inject() (
    config: Configuration,
    auth: Auth,
    mongo: Mongo,
    actors: Actors,
)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer) {

  def connect(req: RequestHeader, sri: Sri, flag: Option[Flag]) =
    auth(req) map { user =>
      ActorFlow actorRef { out =>
        Props(new SiteClientActor(out, sri, flag, user, actors))
      }
    }
}
