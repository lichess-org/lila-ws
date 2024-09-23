package lila.ws
package netty

import com.typesafe.config.Config
import io.netty.channel.epoll.{ EpollEventLoopGroup, EpollServerSocketChannel }
import io.netty.channel.kqueue.{ KQueueEventLoopGroup, KQueueServerSocketChannel }
import io.netty.channel.{ Channel, EventLoopGroup }
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame

import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit }

final class WorkerLoop(config: Config)(using Executor):
  private val isMacOS                = System.getProperty("os.name").toLowerCase.startsWith("mac")
  private val step                   = config.getInt("netty.flush.step")
  private val interval: Long         = config.getDuration("netty.flush.interval").toNanos
  private val maxDelay: Long         = config.getDuration("netty.flush.max-delay").toNanos
  private val maxDelayFactor: Double = interval.toDouble / maxDelay
  private val flushQ                 = new ConcurrentLinkedQueue[Channel]()

  val channelClass = if isMacOS then classOf[KQueueServerSocketChannel] else classOf[EpollServerSocketChannel]
  val parentGroup  = makeGroup(1)
  val group        = makeGroup(config.getInt("netty.threads"))

  private val f = group.scheduleAtFixedRate(() => flush(), 1_000_000_000L, interval, TimeUnit.NANOSECONDS)

  def writeShaped(channel: Channel, frame: TextWebSocketFrame): Unit =
    channel.write(frame)
    flushQ.add(channel)

  def shutdown(): Unit =
    f.cancel(false)
    parentGroup.shutdownGracefully()
    group.shutdownGracefully()

  private def flush(): Unit =
    val channelsToFlush = step.atLeast((flushQ.size * maxDelayFactor).toInt)
    val iterator        = flushQ.iterator()
    for count <- 0 until channelsToFlush if iterator.hasNext do
      iterator.next().flush()
      iterator.remove()

  private def makeGroup(n: Int): EventLoopGroup =
    if isMacOS then new KQueueEventLoopGroup(n)
    else new EpollEventLoopGroup(n)
