package lila.ws
package util

import com.github.benmanes.caffeine
import com.github.blemale.scaffeine.*
import kamon.Kamon

import scala.language.implicitConversions

final class CacheApi(using Executor)(using scheduler: Scheduler):

  import CacheApi.*

  // AsyncLoadingCache with monitoring
  def apply[K, V](initialCapacity: Int, name: String)(
      build: Builder => AsyncLoadingCache[K, V]
  ): AsyncLoadingCache[K, V] =
    val cache = build:
      Scaffeine().recordStats().initialCapacity(initialCapacity)
    monitor(name, cache.underlying.synchronous)
    cache

  def notLoadingSync[K, V](initialCapacity: Int, name: String)(
      build: Builder => Cache[K, V]
  ): Cache[K, V] =
    val cache = build:
      Scaffeine().recordStats().initialCapacity(initialCapacity)
    monitor(name, cache.underlying)
    cache

  def monitor(name: String, cache: caffeine.cache.Cache[?, ?]): Unit =
    import Monitor.{ *, given }
    scheduler.scheduleWithFixedDelay(1.minute, 1.minute): () =>
      // pasted from lila
      // https://github.com/lichess-org/lila/blob/master/modules/common/src/main/mon.scala#L62-L80
      val stats = cache.stats
      Kamon
        .gauge("caffeine.request")
        .withTags(tags("name" -> name, "hit" -> true))
        .update(stats.hitCount.toDouble)
      Kamon
        .gauge("caffeine.request")
        .withTags(tags("name" -> name, "hit" -> false))
        .update(stats.missCount.toDouble)
      Kamon.histogram("caffeine.hit.rate").withTag("name", name).record((stats.hitRate * 100000).toLong)
      if stats.totalLoadTime > 0 then
        Kamon
          .gauge("caffeine.load.count")
          .withTags(tags("name" -> name, "success" -> "success"))
          .update(stats.loadSuccessCount.toDouble)
        Kamon
          .gauge("caffeine.load.count")
          .withTags(tags("name" -> name, "success" -> "failure"))
          .update(stats.loadFailureCount.toDouble)
        Kamon
          .gauge("caffeine.loadTime.cumulated")
          .withTag("name", name)
          .update(stats.totalLoadTime / 1000000d) // in millis; too much nanos for Kamon to handle)
        Kamon.timer("caffeine.loadTime.penalty").withTag("name", name).record(stats.averageLoadPenalty.toLong)
      Kamon.gauge("caffeine.eviction.count").withTag("name", name).update(stats.evictionCount.toDouble)
      Kamon.gauge("caffeine.entry.count").withTag("name", name).update(cache.estimatedSize.toDouble)

object CacheApi:

  private type Builder = Scaffeine[Any, Any]
