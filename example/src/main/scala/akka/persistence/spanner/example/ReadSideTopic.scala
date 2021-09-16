/*
 * Copyright 2021 Lightbend Inc.
 */

package akka.persistence.spanner.example

import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.ActorRef

object ReadSideTopic {
  final case class ReadSideMetrics(count: Long, maxValue: Long, p99: Long, p50: Long) extends CborSerializable

  def init(context: ActorContext[_]): ActorRef[Topic.Command[ReadSideMetrics]] =
    context.spawn(Topic[ReadSideMetrics]("read-side-metrics"), "read-side-metrics")
}
