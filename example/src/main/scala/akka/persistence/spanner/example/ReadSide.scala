/*
 * Copyright 2021 Lightbend Inc.
 */

package akka.persistence.spanner.example

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.stream.{KillSwitches, SharedKillSwitch}
import com.typesafe.config.Config
import org.HdrHistogram.Histogram
import akka.actor.typed.scaladsl.LoggerOps
import scala.concurrent.duration._

import akka.persistence.spanner.internal.SliceUtils

object ReadSide {
  sealed trait Command
  private case object ReportMetrics extends Command

  object Settings {
    def apply(config: Config): Settings =
      Settings(config.getInt("processors"), config.getInt("tags-per-processor"))
  }

  case class Settings(nrProcessors: Int, tagsPerProcessor: Int) {
    val nrTags: Int = nrProcessors * tagsPerProcessor
  }

  def apply(
      system: ActorSystem[_],
      topic: ActorRef[Topic.Command[ReadSideTopic.ReadSideMetrics]],
      settings: Settings
  ): Unit = {
    system.log.info("Running {} processors", settings.nrProcessors)
    val killSwitch: SharedKillSwitch = KillSwitches.shared("eventProcessorSwitch")
    ShardedDaemonProcess(system).init(
      "tag-processor",
      settings.nrProcessors,
      i => behavior(topic, i, settings, killSwitch),
      ShardedDaemonProcessSettings(system).withShardingSettings(ClusterShardingSettings(system).withRole("read")),
      None
    )
  }

  private def behavior(
      topic: ActorRef[Topic.Command[ReadSideTopic.ReadSideMetrics]],
      nr: Int,
      settings: Settings,
      killSwitch: SharedKillSwitch
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startTimerAtFixedRate(ReportMetrics, 10.second)
      Behaviors.setup { ctx =>
        // milliseconds, highest value = 1 minute
        val histogram = new Histogram(10 * 1000 * 60, 2)

        if (settings.tagsPerProcessor > 0) {
          val start = settings.tagsPerProcessor * nr
          val end = start + (settings.tagsPerProcessor) - 1
          val tags = (start to end).map(i => s"tag-$i")
          ctx.log.info("Processor {} processing tags {}", nr, tags)
          // maybe easier to just have these as different actors
          // my thinking is we can start with a large number of tags and scale out
          // read side processors later
          // having more tags will also increase write throughput/latency as it'll write to
          // many partitions
          // downside is running many streams/queries against c*
          tags.foreach(
            tag =>
              new EventProcessorStream[ConfigurablePersistentActor.Event](
                ctx.system,
                ctx.executionContext,
                s"processor-$nr",
                tag,
                -1,
                -1
              ).runQueryStream(killSwitch, histogram)
          )
        } else {
          val maxNumberOfSlices = 128
          val ranges = SliceUtils.sliceRanges(settings.nrProcessors, maxNumberOfSlices)
          new EventProcessorStream[ConfigurablePersistentActor.Event](
            ctx.system,
            ctx.executionContext,
            s"processor-$nr",
            "",
            ranges(nr).min,
            ranges(nr).max
          ).runQueryStream(killSwitch, histogram)
        }

        Behaviors
          .receiveMessage[Command] {
            case ReportMetrics =>
              if (histogram.getTotalCount > 0) {
                topic ! Topic.Publish(
                  ReadSideTopic.ReadSideMetrics(
                    histogram.getTotalCount,
                    histogram.getMaxValue,
                    histogram.getValueAtPercentile(99),
                    histogram.getValueAtPercentile(50)
                  )
                )
                histogram.reset()
              }
              Behaviors.same
          }
          .receiveSignal {
            case (_, PostStop) =>
              killSwitch.shutdown()
              Behaviors.same
          }
      }
    }
}
