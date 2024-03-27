package lila.ws

// this set comes from lila/modules/streamer and is updated a few times per minute at most
object Streamer:
  @volatile private var streams = Map.empty[User.Id, String]

  def set(newStreams: Iterable[(User.Id, String)]): Unit =
    streams = newStreams.toMap

  def intersect(userIds: Iterable[User.Id]): Map[User.Id, String] =
    streams.view.filterKeys(userIds.toSet).toMap
