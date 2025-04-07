package lila.ws

import play.api.libs.json.JsValue

// this set comes from lila/modules/streamer and is updated a few times per minute at most
object Streamer:
  @volatile private var streams = Map.empty[User.Id, JsValue]

  def set(newStreams: Iterable[(User.Id, JsValue)]): Unit =
    streams = newStreams.toMap

  def intersect(userIds: Iterable[User.Id]): Map[User.Id, JsValue] =
    streams.view.filterKeys(userIds.toSet).toMap
