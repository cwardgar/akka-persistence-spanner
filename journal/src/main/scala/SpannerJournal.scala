/*
 * Copyright (C) 2018-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.spanner

import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.persistence.journal.AsyncWriteJournal

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try

class SpannerJournal extends AsyncWriteJournal {
  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = ???

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = ???

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(
      recoveryCallback: PersistentRepr => Unit
  ): Future[Unit] = ???

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = ???
}
