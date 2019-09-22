package lila.ws

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success }

import lila.ws._
import lila.ws.util.Util.{ reqName, origin }

object LilaWs extends App {

  implicit val system: ActorSystem = ActorSystem("lilaWs")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val allowedOrigins = Set(
    Configuration.csrfOrigin,
    "file://",
    "http://localhost:8080",
    "ionic://localhost",
  )

  val mongo = new Mongo
  val server = new Server(
    new Auth(mongo, new SeenAtUpdate(mongo)),
    new Stream(new Redis)
  )

  def connectWebsocket(wsFlow: Future[Server.WebsocketFlow]) =
    onComplete(wsFlow) {
      case Success(flow) => handleWebSocketMessages(flow)
      case Failure(err) => complete(err.toString)
    }

  def CsrfCheck(f: => Route): Route =
    optionalHeaderValueByName("Origin") {
      case None => f // for exotic clients and acid ape chess
      case Some(origin) if allowedOrigins(origin) => f
      case _ => complete(StatusCodes.Forbidden, "Cross origin request forbidden")
    }

  def ValidSri(f: Sri => Route): Route =
    parameter("sri".as(Unmarshaller strict Sri.from)) {
      case None => complete(StatusCodes.BadRequest, "Missing or invalid sri")
      case Some(sri) => f(sri)
    }

  val routes = extractRequest { req =>
    CsrfCheck {
      concat(
        ValidSri { sri =>
          val serverReq = Server.Request(reqName(req), sri, Auth sessionIdFromReq req)
          concat(
            parameter("flag".as(Unmarshaller strict Flag.make).?) { flag =>
              def handler = connectWebsocket(server.connectToSite(serverReq.copy(flag = flag.flatten)))
              concat(
                path("socket" / "v" ~ IntNumber) { _ => handler },
                path("socket") { handler }
              )
            },
            concat(
              path("analysis" / "socket" / "v" ~ IntNumber) { _ =>
                connectWebsocket(server.connectToSite(serverReq))
              },
              path("analysis" / "socket") {
                connectWebsocket(server.connectToSite(serverReq))
              }
            ),
            concat(
              path("lobby" / "socket" / "v" ~ IntNumber) { _ =>
                connectWebsocket(server.connectToLobby(serverReq))
              },
              path("lobby" / "socket") {
                connectWebsocket(server.connectToLobby(serverReq))
              }
            )
          )
        },
        path("api" / "socket") {
          val serverReq = Server.Request(reqName(req), Sri.random, None, Some(Flag.api))
          connectWebsocket(server.connectToSite(serverReq))
        }
      )
    }
  }

  Http()
    .bindAndHandle(routes, Configuration.bindHost, Configuration.bindPort)
    .onComplete {
      case Success(bound) =>
        println(s"lila-ws online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
      case Failure(e) =>
        Console.err.println(s"lila-ws could not start!")
        e.printStackTrace()
        system.terminate()
    }

  Await.result(system.whenTerminated, Duration.Inf)
}
