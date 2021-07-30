/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner.state.scaladsl

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try
import akka.Done
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.pattern.ask
import akka.util.ByteString
import akka.persistence.state.scaladsl.DurableStateUpdateStore
import akka.persistence.state.scaladsl.GetObjectResult
import akka.persistence.query.DurableStateChange
import akka.persistence.query.Offset
import akka.persistence.query.scaladsl.DurableStateStoreQuery
import akka.persistence.spanner.SpannerObjectStore
import akka.persistence.typed.PersistenceId
import akka.serialization.Serialization
import akka.serialization.Serializers
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.Materializer
import akka.stream.SystemMaterializer
import akka.util.Timeout

object SpannerDurableStateStore {
  val Identifier = "akka.persistence.spanner.durable-state-store"
}

class SpannerDurableStateStore[A](
    spannerObjectStore: SpannerObjectStore,
    serialization: Serialization,
    implicit val executionContext: ExecutionContext
) extends DurableStateUpdateStore[A]
    with DurableStateStoreQuery[A] {
  def getObject(persistenceId: String): Future[GetObjectResult[A]] =
    spannerObjectStore.getObject(PersistenceId.ofUniqueId(persistenceId)).flatMap { result =>
      result match {
        case Some(r) =>
          for {
            deserialized <- Future
              .fromTry(
                // to Int: Spanner only has INT64
                deserialize(r.byteString.toArray, r.serId.toInt, r.serManifest)
              )
          } yield {
            GetObjectResult(
              Some(deserialized),
              r.seqNr
            )
          }
        case None => Future.successful(GetObjectResult(None, 0L))
      }
    }

  def upsertObject(persistenceId: String, revision: Long, value: A, tag: String): Future[Done] =
    for {
      (bytes: ByteString, serId: Int, serManifest: String) <- Future.fromTry(
        serialize(value)
      )
      _ <- spannerObjectStore.upsertObject(
        "", // cannot extract entityType from persistenceId.
        PersistenceId.ofUniqueId(persistenceId),
        serId,
        serManifest,
        bytes,
        revision
      )
    } yield Done

  def deleteObject(persistenceId: String): Future[Done] =
    spannerObjectStore.deleteObject(PersistenceId.ofUniqueId(persistenceId)).map(_ => Done)

  def currentChanges(tag: String, offset: Offset): Source[DurableStateChange[A], NotUsed] =
    spannerObjectStore.currentChangesByTag(tag, offset).map(change => toDurableStateChange(change))

  def changes(tag: String, offset: Offset): Source[DurableStateChange[A], NotUsed] =
    spannerObjectStore.changesByTag(tag, offset).map(change => toDurableStateChange(change))

  private def toDurableStateChange(change: SpannerObjectStore.Change) =
    new DurableStateChange[A](
      persistenceId = change.persistenceId,
      revision = change.seqNr,
      // to Int: Spanner only has INT64
      value = deserialize(change.bytes.toArray, change.serId.toInt, change.serManifest).get, // crash source if corrupt.
      offset = change.offset,
      timestamp = change.timestamp
    )

  private def serialize(payload: Any): Try[(ByteString, Int, String)] = {
    val p2 = payload.asInstanceOf[AnyRef]
    val serializer = serialization.findSerializerFor(p2)
    val serManifest = Serializers.manifestFor(serializer, p2)
    val serialized = serialization.serialize(p2)
    serialized.map(payload => (ByteString(payload), serializer.identifier, serManifest))
  }

  private def deserialize(bytes: Array[Byte], serId: Int, serManifest: String) =
    serialization
      .deserialize(
        bytes,
        serId,
        serManifest
      )
      .map(_.asInstanceOf[A])
}
