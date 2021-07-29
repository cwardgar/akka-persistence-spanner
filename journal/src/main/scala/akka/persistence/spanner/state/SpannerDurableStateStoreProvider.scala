/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner.state

import scala.concurrent.ExecutionContext
import akka.actor.ExtendedActorSystem
import akka.persistence.state.scaladsl.DurableStateStore
import akka.persistence.state.javadsl.{DurableStateStore => JDurableStateStore}
import akka.persistence.state.DurableStateStoreProvider
import akka.serialization.SerializationExtension
import akka.stream.{Materializer, SystemMaterializer}
import akka.persistence.spanner.SpannerObjectStore

class SpannerDurableStateStoreProvider[A](system: ExtendedActorSystem) extends DurableStateStoreProvider {
  implicit val sys = system
  val serialization = SerializationExtension(system)
  val executionContext = system.dispatcher
  val spannerObjectStore = SpannerObjectStore()

  override val scaladslDurableStateStore: DurableStateStore[Any] =
    new scaladsl.SpannerDurableStateStore[Any](spannerObjectStore, serialization, executionContext)

  // TODO delegate to scaladsl version
  override val javadslDurableStateStore: JDurableStateStore[AnyRef] = ???
  // new javadsl.SpannerDurableStateStore[AnyRef](
  //   new scaladsl.SpannerDurableStateStore[AnyRef](spannerObjectStore, serialization, executionContext)
  // )
}
