package lila.ws
package netty

import io.netty.channel.ServerChannel
import io.netty.channel.EventLoopGroup
import scala.util.control.NonFatal

// Adapted from https://github.com/ReactiveMongo/ReactiveMongo/blob/5560470ee409da827feee161c16631042e194263/driver/src/main/scala/core/netty/Pack.scala#L15
private final class Pack(
    val eventLoopGroupFactory: Int => EventLoopGroup,
    val channelClass: Class[? <: ServerChannel]
)

private object Pack:
  private val kqueuePkg: String = "io.netty.channel.kqueue"
  private val epollPkg: String = "io.netty.channel.epoll"

  def instance: Pack =
    epoll.orElse(kqueue).getOrElse { throw RuntimeException("Can't initialize either Netty Epoll or Kqueue") }

  private def epoll: Option[Pack] =
    try
      Some(Class.forName(s"${epollPkg}.EpollServerSocketChannel")).map { cls =>
        val chanClass = cls.asInstanceOf[Class[? <: ServerChannel]]
        val groupClass = Class
          .forName(s"${epollPkg}.EpollEventLoopGroup")
          .asInstanceOf[Class[? <: EventLoopGroup]]

        val groupCtor = groupClass.getDeclaredConstructor(classOf[Int])
        new Pack(
          nThreads => groupCtor.newInstance(Int.box(nThreads)),
          chanClass
        )
      }
    catch
      case NonFatal(cause) =>
        None

  private def kqueue: Option[Pack] =
    try
      Some(Class.forName(s"${kqueuePkg}.KQueueServerSocketChannel")).map { cls =>
        val chanClass = cls.asInstanceOf[Class[? <: ServerChannel]]
        val groupClass = Class
          .forName(s"${kqueuePkg}.KQueueEventLoopGroup")
          .asInstanceOf[Class[? <: EventLoopGroup]]
        val groupCtor = groupClass.getDeclaredConstructor(classOf[Int])
        new Pack(
          nThreads => groupCtor.newInstance(Int.box(nThreads)),
          chanClass
        )
      }
    catch
      case NonFatal(cause) =>
        None
