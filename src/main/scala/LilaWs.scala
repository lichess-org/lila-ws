package lila.ws

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success }

import lila.ws._
import lila.ws.util.Util._

object LilaWs extends App {

  implicit val system: ActorSystem = ActorSystem("lilaWs")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val server = new Server(new Auth(new Mongo), new Stream)

  def connectWebsocket(wsFlow: Future[Server.WebsocketFlow]) =
    onComplete(wsFlow) {
      case Success(flow) => handleWebSocketMessages(flow)
      case Failure(err) => complete(err.toString)
    }

  val routes =
    parameter("sri".as(Unmarshaller strict Sri.apply)) { sri =>
      parameter("flag".as(Unmarshaller strict Flag.make)) { flag =>
        optionalCookie("lila2") { authCookie =>
          extractRequest { req =>
            get {
              concat(
                path("socket/v" / IntNumber) { version =>
                  connectWebsocket(server.connectToSite(Server.Request(
                    name = reqName(req),
                    sri = sri,
                    flag = flag,
                    authCookie = authCookie
                  )))
                },
                path("analysis/socket/v" / IntNumber) { version =>
                  connectWebsocket(server.connectToSite(Server.Request(
                    name = reqName(req),
                    sri = sri,
                    flag = flag,
                    authCookie = authCookie
                  )))
                },
                path("lobby/socket/v" / IntNumber) { version =>
                  connectWebsocket(server.connectToLobby(Server.Request(
                    name = reqName(req),
                    sri = sri,
                    flag = flag,
                    authCookie = authCookie
                  )))
                }
              )
            }
          }
        }
      }
    }

  Http()
    .bindAndHandle(routes, "0.0.0.0", Configuration.port)
    .onComplete {
      case Success(bound) =>
        println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
      case Failure(e) =>
        Console.err.println(s"Server could not start!")
        e.printStackTrace()
        system.terminate()
    }

  Await.result(system.whenTerminated, Duration.Inf)
}
