package lila.ws

import io.netty.handler.codec.http.HttpResponseStatus
import scala.concurrent.Future

import util.RequestHeader

final class Router(controller: Controller):

  def apply(req: RequestHeader): Controller.Response =
    req.path drop 1 split '/' match
      case Array("socket") | Array("socket", _) => controller.site(req)
      case Array("analysis", "socket")          => controller.site(req)
      case Array("analysis", "socket", _)       => controller.site(req)
      case Array("api", "socket")               => controller.api(req)
      case Array("lobby", "socket")             => controller.lobby(req)
      case Array("lobby", "socket", _)          => controller.lobby(req)
      case Array("simul", id, "socket", _)      => controller.simul(id, req)
      case Array("tournament", id, "socket", _) => controller.tournament(id, req)
      case Array("study", id, "socket", _)      => controller.study(id, req)
      case Array("watch", id, _, _)             => controller.roundWatch(Game.Id(id), req)
      case Array("play", id, _)                 => controller.roundPlay(Game.FullId(id), req)
      case Array("challenge", id, "socket", _)  => controller.challenge(Challenge.Id(id), req)
      case Array("team", id)                    => controller.team(id, req)
      case Array("swiss", id)                   => controller.swiss(id, req)
      case Array("racer", id)                   => controller.racer(id, req)
      case _                                    => Future successful Left(HttpResponseStatus.NOT_FOUND)
