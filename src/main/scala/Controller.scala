package lila.ws

import akka.actor.typed.{ ActorSystem, Behavior }
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.netty.handler.codec.http.HttpResponseStatus
import javax.inject._
import scala.concurrent.{ Future, ExecutionContext }

import util.RequestHeader

@Singleton
final class Controller @Inject() (
    config: Config,
    mongo: Mongo,
    auth: Auth,
    lila: Lila,
    lilaHandler: LilaHandler,
    services: Services,
    monitor: Monitor
)(implicit
    ec: ExecutionContext,
    system: ActorSystem[Clients.Control]
) {

  import Controller._

  def site(req: RequestHeader, emit: ClientEmit): Response =
    WebSocket(req) { sri =>
      auth(req) map { user =>
        Right(SiteClientActor.start {
          ClientActor.Deps(emit, ClientActor.Req(req, sri, user), services)
        })
      }
    }
  // new RateLimit(
  //       maxCredits = 50,
  //       duration = 20.seconds,
  //       name = s"site ${reqName(req)}"
  //     ))

  private def WebSocket(req: RequestHeader)(f: Sri => Response): Response =
    CSRF.check(req) {
      ValidSri(req)(f)
    }

  private def ValidSri(req: RequestHeader)(f: Sri => Response): Response = req.sri match {
    case Some(validSri) => f(validSri)
    case None => Future successful Left(HttpResponseStatus.BAD_REQUEST)
  }

  private object CSRF {

    val csrfDomain = config.getString("csrf.origin")
    val appOrigins = Set(
      "ionic://localhost", // ios
      "capacitor://localhost", // capacitor (ios next)
      "http://localhost", // android
      "http://localhost:8080", // local dev
      "file://"
    )

    def check(req: RequestHeader)(f: => Response): Response = req.origin match {
      case None => f // for exotic clients and acid ape chess
      case Some(origin) if origin == csrfDomain || appOrigins(origin) => f
      case Some(origin) =>
        logger.info(s"""CSRF origin: "$origin" ${req.name}""")
        Future successful Left(HttpResponseStatus.FORBIDDEN)
    }
  }
}

object Controller {

  val logger = Logger("Controller")

  type Response = Future[Either[HttpResponseStatus, ClientBehavior]]
}
