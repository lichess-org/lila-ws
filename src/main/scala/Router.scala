package lila.ws

import io.netty.handler.codec.http.HttpResponseStatus

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
      case Array("simul", id, "socket", _)      => controller.simul(Simul.Id(id), req)
      case Array("tournament", id, "socket", _) => controller.tournament(Tour.Id(id), req)
      case Array("study", id, "socket", _)      => controller.study(Study.Id(id), req)
      case Array("watch", id, _, _)             => controller.roundWatch(Game.Id(id), req)
      case Array("play", id, _)                 => controller.roundPlay(Game.FullId(id), req)
      case Array("challenge", id, "socket", _)  => controller.challenge(Challenge.Id(id), req)
      case Array("team", id)                    => controller.team(Team.Id(id), req)
      case Array("swiss", id)                   => controller.swiss(Swiss.Id(id), req)
      case Array("racer", id)                   => controller.racer(Racer.Id(id), req)
      case _                                    => Future successful Left(HttpResponseStatus.NOT_FOUND)
