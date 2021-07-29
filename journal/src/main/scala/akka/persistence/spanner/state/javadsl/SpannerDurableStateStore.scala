/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner.state.javadsl

import java.util.Optional
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext
import akka.Done
import akka.NotUsed
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.query.javadsl.DurableStateStoreQuery
import akka.persistence.state.javadsl.DurableStateUpdateStore
import akka.persistence.state.javadsl.GetObjectResult
import akka.persistence.spanner.state.scaladsl.{SpannerDurableStateStore => ScalaSpannerDurableStateStore}
import akka.stream.javadsl.Source

object SpannerDurableStateStore {
  val Identifier = ScalaSpannerDurableStateStore.Identifier
}

class SpannerDurableStateStore[A](scalaStore: akka.persistence.spanner.state.scaladsl.SpannerDurableStateStore[A])(
    implicit ec: ExecutionContext
) extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A] {
  def getObject(persistenceId: String): CompletionStage[GetObjectResult[A]] =
    toJava(
      scalaStore
        .getObject(persistenceId)
        .map(x => GetObjectResult(Optional.ofNullable(x.value.getOrElse(null.asInstanceOf[A])), x.revision))
    )

  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): CompletionStage[Done] =
    toJava(scalaStore.upsertObject(persistenceId, revision, value, tag))

  def deleteObject(persistenceId: String): CompletionStage[Done] =
    toJava(scalaStore.deleteObject(persistenceId))

  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], NotUsed] =
    scalaStore.currentChanges(tag, offset).asJava

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], NotUsed] =
    scalaStore.changes(tag, offset).asJava
}
