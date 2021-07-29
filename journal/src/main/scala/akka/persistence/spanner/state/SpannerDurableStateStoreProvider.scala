/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner.state

import scala.concurrent.ExecutionContext

import akka.actor.ExtendedActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.state.scaladsl.DurableStateStore
import akka.persistence.state.javadsl.{DurableStateStore => JDurableStateStore}
import akka.persistence.state.DurableStateStoreProvider
import akka.persistence.spanner.internal.SpannerGrpcClientExtension
import akka.persistence.spanner.internal.SpannerObjectInteractions
import akka.persistence.spanner.SpannerObjectStore
import akka.persistence.spanner.SpannerOffset
import akka.persistence.spanner.SpannerSettings
import akka.serialization.SerializationExtension
import akka.stream.Materializer
import akka.stream.SystemMaterializer

import com.typesafe.config.Config

class SpannerDurableStateStoreProvider(system: ExtendedActorSystem, config: Config, cfgPath: String)
    extends DurableStateStoreProvider {
  implicit val sys = system
  implicit val executionContext = system.dispatcher

  private val sharedConfigPath = cfgPath.replaceAll("""\.durable-state-store$""", "")
  private val spannerSettings = new SpannerSettings(system.settings.config.getConfig(sharedConfigPath))
  private val serialization = SerializationExtension(system)

  private val grpcClient = SpannerGrpcClientExtension(system.toTyped).clientFor(sharedConfigPath)

  val spannerInteractions = new SpannerObjectInteractions(
    grpcClient,
    spannerSettings
  )
  val spannerObjectStore = new SpannerObjectStore(spannerInteractions)

  override val scaladslDurableStateStore: DurableStateStore[Any] =
    new scaladsl.SpannerDurableStateStore[Any](spannerObjectStore, serialization, executionContext)

  override val javadslDurableStateStore: JDurableStateStore[AnyRef] =
    new javadsl.SpannerDurableStateStore[AnyRef](
      new scaladsl.SpannerDurableStateStore[AnyRef](spannerObjectStore, serialization, executionContext)
    )
}
