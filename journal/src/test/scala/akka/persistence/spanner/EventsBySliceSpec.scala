/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner

import scala.concurrent.duration._

import akka.persistence.query.EventEnvelope
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.spanner.EventsByTagSpec.Current
import akka.persistence.spanner.EventsByTagSpec.Live
import akka.persistence.spanner.EventsByTagSpec.QueryType
import akka.persistence.spanner.TestActors.Persister.PersistMe
import akka.persistence.spanner.scaladsl.SpannerReadJournal
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.Done
import akka.NotUsed
import akka.persistence.spanner.internal.SliceUtils

object EventsBySliceSpec {
  sealed trait QueryType
  case object Live extends QueryType
  case object Current extends QueryType
}

class EventsBySliceSpec extends SpannerSpec {
  import SliceUtils._
  import spannerSettings.maxNumberOfSlices

  val query = PersistenceQuery(testKit.system).readJournalFor[SpannerReadJournal](SpannerReadJournal.Identifier)

  private class Setup {
    val entityTypeHint = nextEntityTypeHint
    val persistenceId = nextPid(entityTypeHint)
    val persister = testKit.spawn(TestActors.Persister(persistenceId))
    val probe = testKit.createTestProbe[Done]
    val sinkProbe = TestSink.probe[EventEnvelope]
  }

  List[QueryType](Current).foreach { queryType =>
    def doQuery(entityTypeHint: String, minSlice: Int, maxSlice: Int, offset: Offset): Source[EventEnvelope, NotUsed] =
      queryType match {
        case Live =>
          query.eventsBySlices(entityTypeHint, minSlice, maxSlice, offset)
        case Current =>
          query.currentEventsBySlices(entityTypeHint, minSlice, maxSlice, offset)
      }

    def assertFinished(probe: TestSubscriber.Probe[EventEnvelope]): Unit =
      queryType match {
        case Live =>
          probe.expectNoMessage()
          probe.cancel()
        case Current =>
          probe.expectComplete()
      }

    s"$queryType EventsBySlices" should {
      "return all events for NoOffset" in new Setup {
        for (i <- 1 to 20) {
          persister ! PersistMe(s"e-$i", probe.ref)
          probe.expectMessage(10.seconds, Done)
        }
        val slice = sliceForPersistenceId(persistenceId, maxNumberOfSlices)
        val result: TestSubscriber.Probe[EventEnvelope] =
          doQuery(entityTypeHint, slice, slice, NoOffset)
            .runWith(sinkProbe)
            .request(21)
        for (i <- 1 to 20) {
          val expectedEvent = s"e-$i"
          withClue(s"Expected event $expectedEvent") {
            result.expectNextPF {
              case EventEnvelope(_, _, _, `expectedEvent`) =>
            }
          }
        }
        assertFinished(result)
      }

      "only return events after an offset" in new Setup {
        for (i <- 1 to 20) {
          persister ! PersistMe(s"e-$i", probe.ref)
          probe.expectMessage(Done)
        }

        val slice = sliceForPersistenceId(persistenceId, maxNumberOfSlices)
        val result: TestSubscriber.Probe[EventEnvelope] =
          doQuery(entityTypeHint, slice, slice, NoOffset)
            .runWith(sinkProbe)
            .request(21)

        result.expectNextN(9)

        val offset = result.expectNext().offset
        result.cancel()

        val withOffset =
          doQuery(entityTypeHint, slice, slice, offset)
            .runWith(TestSink.probe[EventEnvelope])
        withOffset.request(12)
        for (i <- 11 to 20) {
          val expectedEvent = s"e-$i"
          withClue(s"Expected event $expectedEvent") {
            withOffset.expectNextPF {
              case EventEnvelope(
                  SpannerOffset(_, seen),
                  persistenceId,
                  sequenceNr,
                  `expectedEvent`
                  ) if seen(persistenceId) == sequenceNr =>
            }
          }
        }
        assertFinished(withOffset)
      }

      "retrieve from several slices" in new Setup {
        val numberOfPersisters = 40
        val numberOfEvents = 3
        val persistenceIds = (1 to numberOfPersisters).map(_ => nextPid(entityTypeHint)).toVector
        val persisters = persistenceIds.map { pid =>
          println(s"# pid [$pid] in slice [${SliceUtils.sliceForPersistenceId(pid, maxNumberOfSlices)}]") // FIXME
          val ref = testKit.spawn(TestActors.Persister(pid))
          for (i <- 1 to numberOfEvents) {
            ref ! PersistMe(s"e-$i", probe.ref)
            probe.expectMessage(Done)
          }
        }

        maxNumberOfSlices should be(128)
        val ranges = SliceUtils.sliceRanges(4, maxNumberOfSlices)
        ranges(0) should be(0 to 31)
        ranges(1) should be(32 to 63)
        ranges(2) should be(64 to 95)
        ranges(3) should be(96 to 127)

        val allEnvelopes =
          (0 until 4).flatMap { rangeIndex =>
            val result =
              doQuery(entityTypeHint, ranges(rangeIndex).min, ranges(rangeIndex).max, NoOffset)
                .runWith(Sink.seq)
                .futureValue
            println(
              s"# result$rangeIndex (${result.size}): ${result
                .map(env => s"${env.persistenceId} / ${env.sequenceNr} / ${env.event}")
                .mkString(", ")}"
            ) // FIXME
            result.foreach { env =>
              ranges(rangeIndex) should contain(SliceUtils.sliceForPersistenceId(env.persistenceId, maxNumberOfSlices))
            }
            result
          }
        allEnvelopes.size should be(numberOfPersisters * numberOfEvents)
      }

      // FIXME more tests... see EventsByTagSpec
    }
  }
}
