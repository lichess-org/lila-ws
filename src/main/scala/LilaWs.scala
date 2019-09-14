package lila.ws

import akka.actor.{ ActorRef, ActorSystem }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.ActorMaterializer
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success }

import lila.ws._
import lila.ws.util.Util.reqName

object LilaWs extends App {

  implicit val system: ActorSystem = ActorSystem("lilaWs")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val server = new Server(new Auth(new Mongo), new Stream(new Redis))

  def connectWebsocket(wsFlow: Future[Server.WebsocketFlow]) =
    onComplete(wsFlow) {
      case Success(flow) => handleWebSocketMessages(flow)
      case Failure(err) => complete(err.toString)
    }

  val routes = parameter("sri".as(Unmarshaller strict Sri.apply)) { sri =>
    optionalCookie("lila2") { authCookie =>
      extractRequest { req =>
        val serverReq = Server.Request(reqName(req), sri, authCookie)
        concat(
          path("socket" / "v" ~ IntNumber) { version =>
            parameter("flag".as(Unmarshaller strict Flag.make).?) { flag =>
              connectWebsocket(server.connectToSite(serverReq.copy(flag = flag.flatten)))
            }
          },
          path("analysis" / "socket" / "v" ~ IntNumber) { version =>
            connectWebsocket(server.connectToSite(serverReq))
          },
          path("lobby" / "socket" / "v" ~ IntNumber) { version =>
            connectWebsocket(server.connectToLobby(serverReq))
          }
        )
      }
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
