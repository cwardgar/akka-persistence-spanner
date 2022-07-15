/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.spanner.internal

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.NotUsed
import akka.actor.ActorSystem
import akka.annotation.InternalApi
import akka.persistence.query.Offset
import akka.persistence.spanner.SpannerOffset
import akka.persistence.spanner.SpannerSettings
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.protobuf.struct
import com.google.protobuf.struct.ListValue
import com.google.protobuf.struct.Struct
import com.google.protobuf.struct.Value
import com.google.protobuf.struct.Value.Kind.StringValue
import com.google.spanner.v1.keys.KeySet
import com.google.spanner.v1.mutation.Mutation.Mutation
import com.google.spanner.v1.spanner.ReadRequest
import com.google.spanner.v1.`type`.Type
import com.google.spanner.v1.`type`.TypeCode
import io.grpc.Status
import io.grpc.StatusRuntimeException

/**
 * INTERNAL API
 */
@InternalApi
object SpannerObjectInteractions {
  object Schema {
    // To back Durable Actors
    object Objects {
      def objectTable(settings: SpannerSettings): String =
        objectTable(settings.objectTable, settings.objectTableTagColumn)

      def objectTable(table: String, tagColumn: String): String =
        s"""CREATE TABLE $table (
           |  persistence_id STRING(MAX) NOT NULL,
           |  value BYTES(MAX),
           |  ser_id INT64 NOT NULL,
           |  ser_manifest STRING(MAX) NOT NULL,
           |  sequence_nr INT64 NOT NULL,
           |  write_time TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true),
           |  $tagColumn STRING(MAX) NOT NULL
           |) PRIMARY KEY (persistence_id)""".stripMargin

      def objectsByTagOffsetIndex(settings: SpannerSettings): String =
        objectsByTagOffsetIndex(settings.objectTable, settings.objectTableTagColumn)

      def objectsByTagOffsetIndex(table: String, tagColumn: String): String =
        s"""CREATE INDEX ${table}_${tagColumn}_and_offset
           |ON $table (
           |  $tagColumn,
           |  write_time
           |)""".stripMargin

      /** Columns */
      // Key that uniquely identifies an entity instance - typically by concatenating the entity type and a unique id
      val PersistenceId = "persistence_id" -> TypeCode.STRING
      val Value = "value" -> TypeCode.BYTES
      val SerId = "ser_id" -> TypeCode.INT64
      val SerManifest = "ser_manifest" -> TypeCode.STRING
      val SeqNr = "sequence_nr" -> TypeCode.INT64
      val WriteTime = "write_time" -> TypeCode.TIMESTAMP
      // Tag is defined in SpannerObjectInteractions class because the name is configurable

      /** For use in queries: */
      val OldSeqNr = "old_sequence_nr" -> TypeCode.INT64
      val NewSeqNr = "new_sequence_nr" -> TypeCode.INT64

      case class ObjectRow(
          persistenceId: String,
          value: ByteString,
          serId: Long,
          serManifest: String,
          sequenceNr: Long,
          writeTime: String,
          tag: String
      )
      def deserializeRow(row: Seq[struct.Value]): ObjectRow =
        row match {
          case Seq(persistenceId, serId, serManifest, value, sequenceNr, writeTime, tag) =>
            ObjectRow(
              persistenceId = persistenceId.getStringValue,
              value = ByteString(value.getStringValue).decodeBase64,
              serId = serId.getStringValue.toLong,
              serManifest = serManifest.getStringValue,
              sequenceNr = sequenceNr.getStringValue.toLong,
              writeTime = writeTime.getStringValue,
              tag = tag.getStringValue
            )
        }
    }
  }

  case class Change(
      persistenceId: String,
      bytes: ByteString,
      serId: Long,
      serManifest: String,
      seqNr: Long,
      offset: Offset,
      timestamp: Long
  )

  case class Result(byteString: ByteString, serId: Long, serManifest: String, seqNr: Long)
}

/**
 * INTERNAL API
 *
 * Class for doing spanner interaction outside of an actor to avoid mistakes
 * in future callbacks
 */
@InternalApi
private[spanner] final class SpannerObjectInteractions(spannerGrpcClient: SpannerGrpcClient, settings: SpannerSettings)(
    implicit ec: ExecutionContext,
    system: ActorSystem
) {
  import SpannerObjectInteractions.Change
  import SpannerObjectInteractions.Result
  import SpannerObjectInteractions.Schema.Objects

  val Tag = settings.objectTableTagColumn -> TypeCode.STRING

  val Columns = List(
    Objects.PersistenceId,
    Objects.SerId,
    Objects.SerManifest,
    Objects.Value,
    Objects.SeqNr,
    Objects.WriteTime,
    Tag
  )

  val SqlUpdate =
    s"UPDATE ${settings.objectTable} SET value = @value, ser_id = @ser_id, ser_manifest = @ser_manifest, " +
    s"sequence_nr = @new_sequence_nr, write_time = PENDING_COMMIT_TIMESTAMP(), ${settings.objectTableTagColumn} = @${settings.objectTableTagColumn} " +
    s"WHERE persistence_id = @persistence_id AND sequence_nr = @old_sequence_nr"
  val SqlUpdateTypes: Map[String, TypeCode] =
    List(
      Objects.Value,
      Objects.SerId,
      Objects.SerManifest,
      Objects.NewSeqNr,
      Objects.PersistenceId,
      Objects.OldSeqNr,
      Tag
    ).toMap

  /**
   * @param seqNr sequence number for optimistic locking. starts at 1.
   */
  def upsertObject(
      persistenceId: PersistenceId,
      serId: Long,
      serManifest: String,
      value: ByteString,
      seqNr: Long,
      tag: String
  ): Future[Unit] = {
    require(seqNr > 0, "Sequence number should start at 1")
    spannerGrpcClient.withSession(session => {
      if (seqNr == 1) insertFirst(persistenceId, serId, serManifest, value, seqNr, tag, session)
      else update(persistenceId, serId, serManifest, value, seqNr, tag, session)
    })
  }

  private object CommitTimestamp

  private def insertFirst(
      persistenceId: PersistenceId,
      serId: Long,
      serManifest: String,
      value: ByteString,
      seqNr: Long,
      tag: String,
      session: SessionPool.PooledSession
  ) =
    spannerGrpcClient
      .write(
        Seq(
          Mutation(
            Mutation.Operation.Insert(
              Mutation.Write(
                settings.objectTable,
                Columns.map(_._1),
                List(ListValue(Columns.map {
                  case Objects.PersistenceId => wrapValue(Objects.PersistenceId, persistenceId)
                  case Objects.SerId => wrapValue(Objects.SerId, serId)
                  case Objects.SerManifest => wrapValue(Objects.SerManifest, serManifest)
                  case Objects.SeqNr => wrapValue(Objects.SeqNr, seqNr)
                  case Objects.Value => wrapValue(Objects.Value, value)
                  case Objects.WriteTime => wrapValue(Objects.WriteTime, CommitTimestamp)
                  case Tag => wrapValue(Tag, tag)
                  case other => throw new MatchError(other)
                }))
              )
            )
          )
        )
      )(session)
      .recoverWith {
        case e: StatusRuntimeException =>
          if (e.getStatus.getCode == Status.Code.ALREADY_EXISTS)
            Future.failed(
              new IllegalStateException(
                s"Insert failed: object for persistence id [${persistenceId.id}] already exists"
              )
            )
          else
            Future.failed(e)
      }

  private def update(
      persistenceId: PersistenceId,
      serId: Long,
      serManifest: String,
      value: ByteString,
      seqNr: Long,
      tag: String,
      session: SessionPool.PooledSession
  ): Future[Unit] =
    spannerGrpcClient
      .executeBatchDml(List((SqlUpdate, Struct(SqlUpdateTypes.map {
        case Objects.PersistenceId => wrapNameValue(Objects.PersistenceId, persistenceId)
        case Objects.SerId => wrapNameValue(Objects.SerId, serId)
        case Objects.SerManifest => wrapNameValue(Objects.SerManifest, serManifest)
        case Objects.Value => wrapNameValue(Objects.Value, value)
        case Objects.OldSeqNr => wrapNameValue(Objects.OldSeqNr, seqNr - 1)
        case Objects.NewSeqNr => wrapNameValue(Objects.NewSeqNr, seqNr)
        case Tag => wrapNameValue(Tag, tag)
        case other => throw new MatchError(other)
      }), SqlUpdateTypes.mapValues(Type(_)).toMap)))(session)
      .map(response => {
        val updated = response.resultSets.head.stats.head.rowCount.rowCountExact.head
        if (updated != 1L)
          throw new IllegalStateException(
            s"Update failed: object for persistence id [${persistenceId.id}] could not be updated to sequence number [$seqNr]"
          )
      })

  // TODO maybe return timestamp?
  def getObject(persistenceId: PersistenceId): Future[Option[Result]] =
    spannerGrpcClient
      .withSession(
        session =>
          spannerGrpcClient.client.read(
            ReadRequest(
              session.session.name,
              transaction = None,
              settings.objectTable,
              index = "",
              Seq(Objects.Value, Objects.SerId, Objects.SerManifest, Objects.SeqNr).map(_._1),
              wrapPersistenceId(persistenceId),
              limit = 1
            )
          )
      )
      .map(
        resultSet =>
          resultSet.rows.headOption.map {
            case ListValue(Seq(value, serId, serManifest, seqNr), _) =>
              Result(
                ByteString(value.getStringValue).decodeBase64,
                serId.getStringValue.toLong,
                serManifest.getStringValue,
                seqNr.getStringValue.toLong
              )
          }
      )

  def deleteObject(persistenceId: PersistenceId): Future[Unit] =
    spannerGrpcClient.withSession(session => {
      spannerGrpcClient.write(
        Seq(
          Mutation(Mutation.Operation.Delete(Mutation.Delete(settings.objectTable, wrapPersistenceId(persistenceId))))
        )
      )(session)
    })

  private val ObjectChangesSqlByTag =
    s"""SELECT ${Columns.map(_._1).mkString(", ")}
       |FROM ${settings.objectTable}
       |WHERE ${settings.objectTableTagColumn} = @${settings.objectTableTagColumn}
       |AND write_time >= @write_time
       |ORDER BY write_time, persistence_id""".stripMargin

  private def deserializeAndAddOffset(spannerOffset: SpannerOffset): () => Seq[Value] => immutable.Iterable[Change] = {
    () =>
      var currentTimestamp: String = spannerOffset.commitTimestamp
      var currentSequenceNrs: Map[String, Long] = spannerOffset.seen
      row => {
        def objectToChange(offset: SpannerOffset, obj: Objects.ObjectRow): Change =
          Change(
            persistenceId = obj.persistenceId,
            bytes = obj.value,
            serId = obj.serId,
            serManifest = obj.serManifest,
            seqNr = obj.sequenceNr,
            offset = offset,
            timestamp = SpannerUtils.spannerTimestampToUnixMillis(obj.writeTime)
          )

        val obj = Objects.deserializeRow(row)
        if (obj.writeTime == currentTimestamp) {
          // has this already been seen?
          if (currentSequenceNrs.get(obj.persistenceId).exists(_ >= obj.sequenceNr)) {
            Nil
          } else {
            currentSequenceNrs = currentSequenceNrs.updated(obj.persistenceId, obj.sequenceNr)
            val offset = SpannerOffset(obj.writeTime, currentSequenceNrs)
            objectToChange(offset, obj) :: Nil
          }
        } else {
          // ne timestamp, reset currentSequenceNrs
          currentTimestamp = obj.writeTime
          currentSequenceNrs = Map(obj.persistenceId -> obj.sequenceNr)
          val offset = SpannerOffset(obj.writeTime, currentSequenceNrs)
          objectToChange(offset, obj) :: Nil
        }
      }
  }

  def currentChangesByTag(tag: String, offset: Offset): Source[Change, NotUsed] = {
    val spannerOffset = SpannerUtils.toSpannerOffset(offset)
    spannerGrpcClient
      .streamingQuery(
        ObjectChangesSqlByTag,
        params = Some(
          Struct(
            Map(
              settings.objectTableTagColumn -> Value(StringValue(tag)),
              "write_time" -> Value(StringValue(spannerOffset.commitTimestamp))
            )
          )
        ),
        paramTypes =
          Map(settings.objectTableTagColumn -> Type(TypeCode.STRING), "write_time" -> Type(TypeCode.TIMESTAMP))
      )
      .statefulMapConcat(deserializeAndAddOffset(spannerOffset))
      .mapMaterializedValue(_ => NotUsed)
  }

  def changesByTag(tag: String, offset: Offset): Source[Change, NotUsed] = {
    val initialOffset = SpannerUtils.toSpannerOffset(offset)

    def nextOffset(previousOffset: SpannerOffset, change: Change): SpannerOffset =
      change.offset.asInstanceOf[SpannerOffset]

    ContinuousQuery[SpannerOffset, Change](
      initialOffset,
      nextOffset,
      offset => Some(currentChangesByTag(tag, offset)),
      1, // the same row comes back and is filtered due to how the offset works
      settings.querySettings.refreshInterval
    )
  }

  private val LimitedPersistenceIdsSql =
    s"""SELECT ${Objects.PersistenceId._1}
       |FROM ${settings.objectTable}
       |ORDER BY persistence_id
       |LIMIT @limit""".stripMargin

  private val PagedPersistenceIdsSql =
    s"""SELECT ${Objects.PersistenceId._1}
       |FROM ${settings.objectTable}
       |WHERE ${Objects.PersistenceId._1} > @${Objects.PersistenceId._1}
       |ORDER BY persistence_id
       |LIMIT @limit""".stripMargin

  def currentPersistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] =
    (afterId match {
      case Some(id) =>
        spannerGrpcClient
          .streamingQuery(
            PagedPersistenceIdsSql,
            params = Some(
              Struct(
                Map(
                  Objects.PersistenceId._1 -> Value(StringValue(id)),
                  "limit" -> Value(StringValue(limit.toString))
                )
              )
            )
          )
      case None =>
        spannerGrpcClient
          .streamingQuery(
            LimitedPersistenceIdsSql,
            params = Some(
              Struct(
                Map(
                  "limit" -> Value(StringValue(limit.toString))
                )
              )
            )
          )
    }).map(row => row.head.getStringValue)
      .mapMaterializedValue(_ => NotUsed)

  private def wrapPersistenceId(persistenceId: PersistenceId) =
    Some(KeySet(List(ListValue(List(wrapValue(Objects.PersistenceId, persistenceId.id))))))

  private def wrapNameValue(column: (String, TypeCode), value: Any): (String, Value) =
    (column._1, wrapValue(column, value))

  private def wrapValue(column: (String, TypeCode), value: Any): Value =
    Value((column, value) match {
      case ((_, TypeCode.STRING), stringValue: String) => StringValue(stringValue)
      case ((_, TypeCode.STRING), persistenceId: PersistenceId) => StringValue(persistenceId.id)
      case ((_, TypeCode.INT64), longValue: Long) => StringValue(longValue.toString)
      case ((_, TypeCode.BYTES), bytes: ByteString) =>
        // the protobuf struct data type doesn't have a type for binary data, so we base64-encode it
        // https://github.com/protocolbuffers/protobuf/blob/master/src/google/protobuf/struct.proto#L62
        StringValue(bytes.encodeBase64.utf8String)
      case ((_, TypeCode.TIMESTAMP), CommitTimestamp) =>
        StringValue("spanner.commit_timestamp()")
      case ((name, kind), value) =>
        if (value == null)
          throw new IllegalStateException(s"Cannot wrap null value of type for column [$name] of type [$kind]")
        else
          throw new IllegalStateException(
            s"Cannot wrap value of type [${value.getClass}] for column [$name] of type [$kind]"
          )
    })
}
