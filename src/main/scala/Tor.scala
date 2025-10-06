package lila.ws

import com.typesafe.scalalogging.Logger

final private class Tor(mongo: Mongo)(using Executor)(using scheduler: Scheduler):

  def isExitNode(ip: IpAddress) = ips.contains(ip)

  private var ips = Set.empty[IpAddress]

  private def refresh(): Future[Unit] =
    mongo.cache
      .get[String]("security.torNodes")
      .map:
        case Some(str) if str.nonEmpty => ips = IpAddress.from(str.split(' ').toSet)
        case _ =>
      .map: _ =>
        Logger(getClass).info(s"Updated Tor exit nodes: ${ips.size} found")

  scheduler.scheduleWithFixedDelay(15.seconds, 30.minutes): () =>
    refresh()
