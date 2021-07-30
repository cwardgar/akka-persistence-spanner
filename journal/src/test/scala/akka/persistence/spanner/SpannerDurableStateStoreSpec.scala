/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner

import scala.concurrent.duration._
import akka.persistence.query.NoOffset
import akka.persistence.query.DurableStateChange
import akka.persistence.spanner.state.scaladsl.SpannerDurableStateStore
import akka.persistence.spanner.SpannerObjectStore.{Change, Result}
import akka.persistence.state.DurableStateStoreRegistry
import akka.persistence.state.scaladsl.GetObjectResult
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.Sink
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString

class SpannerDurableStateStoreSpec extends SpannerSpec("SpannerDurableStateStoreSpec") {
  override def withObjectStore: Boolean = true

  val store = DurableStateStoreRegistry(testKit.system)
    .durableStateStoreFor[SpannerDurableStateStore[String]](SpannerDurableStateStore.Identifier)
  val entityType = "string-entity"
  val tag = "tag"

  "The spanner durable state store" should {
    "save and retrieve a value" in {
      val persistenceId = PersistenceId(entityType, "my-persistenceId").id
      val value = "Genuinely Collaborative"

      store.upsertObject(persistenceId, 1L, value, tag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
    }

    "produce None when fetching a non-existing key" in {
      val key = PersistenceId(entityType, "nonexistent-id").id
      store.getObject(key).futureValue should be(GetObjectResult(None, 0L))
    }

    "update a value" in {
      val persistenceId = PersistenceId(entityType, "id-to-be-updated").id
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, tag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))

      val updatedValue = "Open to Feedback"
      store.upsertObject(persistenceId, 2L, updatedValue, tag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(updatedValue), 2L))
    }

    "support deletions" in {
      val persistenceId = PersistenceId(entityType, "to-be-added-and-removed").id
      val value = "Genuinely Collaborative"
      store.upsertObject(persistenceId, 1L, value, tag).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(Some(value), 1L))
      store.deleteObject(persistenceId).futureValue
      store.getObject(persistenceId).futureValue should be(GetObjectResult(None, 0L))
    }

    "support querying for current changes" in {
      val tag = "current-changes"
      val persistenceId1 = PersistenceId(entityType, "id-1").id
      val value1 = "Genuinely Collaborative"
      store.upsertObject(persistenceId1, 1L, value1, tag).futureValue
      val persistenceId2 = PersistenceId(entityType, "id-2").id
      val value2 = "Open to Feedback"
      store.upsertObject(persistenceId2, 1L, value2, tag).futureValue

      val changes1 = store.currentChanges(tag, NoOffset).runWith(Sink.seq).futureValue
      changes1 should have size (2)
      val change1 = changes1.head

      change1.persistenceId should be(persistenceId1)
      change1.revision should be(1L)
      change1.value should be(value1)

      val change2 = changes1(1)

      change2.persistenceId should be(persistenceId2)
      change2.revision should be(1L)
      change2.value should be(value2)

      val changes2 = store.currentChanges(tag, change1.offset).runWith(Sink.seq).futureValue
      changes2 should have size 1

      val change22 = changes2.head
      change22.persistenceId should be(persistenceId2)
      change22.revision should be(1L)
      change22.value should be(value2)

      val value3 = "Genuine and Sincere in all Communications"
      store.upsertObject(persistenceId1, 2L, value3, tag).futureValue

      val changes3 = store.currentChanges(tag, change22.offset).runWith(Sink.seq).futureValue
      changes3 should have size 1
      val change3 = changes3.head
      change3.persistenceId should be(persistenceId1)
      change3.revision should be(2L)
      change3.value should be(value3)
    }

    "support continuous changes query" in {
      val entityType = "continuous-changes"
      val tag = "continous-changes-1"
      val persistenceId1 = PersistenceId(entityType, "id-1").id
      val value1 = "Genuinely Collaborative"
      store.upsertObject(persistenceId1, 1L, value1, tag).futureValue
      val persistenceId2 = PersistenceId(entityType, "id-2").id
      val value2 = "Open to Feedback"
      store.upsertObject(persistenceId2, 1L, value2, tag).futureValue

      val probe = store.changes(tag, NoOffset).runWith(TestSink.probe[DurableStateChange[String]])
      probe.request(100)

      val change1 = probe.expectNext()
      change1.persistenceId should be(persistenceId1)
      val change2 = probe.expectNext()
      change2.persistenceId should be(persistenceId2)
      probe.expectNoMessage(1.second)

      val persistenceId3 = PersistenceId(entityType, "id-3").id
      val value3 = "Genuine and Sincere in all Communications"
      store.upsertObject(persistenceId3, 1L, value3, tag).futureValue
      val change3 = probe.expectNext()
      change3.persistenceId should be(persistenceId3)

      val value4 = "Always prefer Hacks over Well Engineered Solutions"
      store.upsertObject(persistenceId1, 2L, value4, tag).futureValue
      val change4 = probe.expectNext()

      change4.persistenceId should be(persistenceId1)
      change4.value should be(value4)
      change4.revision should be(2L)

      probe.cancel()
    }
  }
}
