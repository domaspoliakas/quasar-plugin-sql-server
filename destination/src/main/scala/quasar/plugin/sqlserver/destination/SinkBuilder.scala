/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.sqlserver.destination

import slamdata.Predef._

import quasar.plugin.sqlserver._

import quasar.api.push.OffsetKey
import quasar.api.Column
import quasar.api.resource.ResourcePath
import quasar.connector.{AppendEvent, DataEvent, MonadResourceErr}
import quasar.connector.destination.{WriteMode => QWriteMode, ResultSink}, ResultSink.{UpsertSink, AppendSink}
import quasar.connector.render.RenderConfig
import quasar.lib.jdbc._
import quasar.lib.jdbc.destination.{WriteMode => JWriteMode}

import cats.~>
import cats.data.NonEmptyList
import cats.effect.{Effect, LiftIO}
import cats.effect.concurrent.Ref
import cats.implicits._

import doobie._
import doobie.free.connection.commit
import doobie.implicits._

import fs2.{Pipe, Stream}

import java.lang.CharSequence

import org.slf4s.Logger

import skolems.∀

object SinkBuilder {
  type Consume[F[_], Event[_], A] =
    Pipe[F, Event[OffsetKey.Actual[A]], OffsetKey.Actual[A]]

  def upsert[F[_]: Effect: MonadResourceErr](
      xa: Transactor[F],
      writeMode: JWriteMode,
      schema: String,
      logger: Logger)(
      args: UpsertSink.Args[SQLServerType])
      : (RenderConfig[CharSequence], ∀[Consume[F, DataEvent[CharSequence, *], *]]) = {
    val consume = ∀[Consume[F, DataEvent[CharSequence, *], *]](upsertPipe(
      xa,
      args.writeMode,
      writeMode,
      schema,
      args.path,
      Some(args.idColumn),
      args.columns,
      logger))
    (renderConfig(args.columns), consume)
  }

  def append[F[_]: Effect: MonadResourceErr](
      xa: Transactor[F],
      writeMode: JWriteMode,
      schema: String,
      logger: Logger)(
      args: AppendSink.Args[SQLServerType])
      : (RenderConfig[CharSequence], ∀[Consume[F, AppendEvent[CharSequence, *], *]]) = {
    val consume = ∀[Consume[F, AppendEvent[CharSequence, *], *]](upsertPipe(
      xa,
      args.writeMode,
      writeMode,
      schema,
      args.path,
      args.pushColumns.primary,
      args.columns,
      logger))
    (renderConfig(args.columns), consume)
  }


  private def upsertPipe[F[_]: Effect: MonadResourceErr, A](
      xa: Transactor[F],
      writeMode: QWriteMode,
      jwriteMode: JWriteMode,
      schema: String,
      path: ResourcePath,
      idColumn: Option[Column[SQLServerType]],
      inputColumns: NonEmptyList[Column[SQLServerType]],
      logger: Logger)
      : Pipe[F, DataEvent[CharSequence, OffsetKey.Actual[A]], OffsetKey.Actual[A]] = { events =>

    val logHandler = Slf4sLogHandler(logger)

    val toConnectionIO = Effect.toIOK[F] andThen LiftIO.liftK[ConnectionIO]

    val hyColumns = hygienicColumns(inputColumns)

    def logEvents(event: DataEvent[CharSequence, _]): F[Unit] = event match {
      case DataEvent.Create(chunk) =>
        trace(logger)(s"Loading chunk with size: ${chunk.size}")
      case DataEvent.Delete(idBatch) =>
        trace(logger)(s"Deleting ${idBatch.size} records")
      case DataEvent.Commit(_) =>
        trace(logger)(s"Commit")
    }

    def handleEvents(
      refMode: Ref[ConnectionIO, QWriteMode],
      objFragment: Fragment,
      unsafeName: String,
      unsafeSchema: Option[String],
      tempTable: TempTable,
      columns: NonEmptyList[(HI, SQLServerType)])
      : Pipe[ConnectionIO, DataEvent[CharSequence, OffsetKey.Actual[A]], Option[OffsetKey.Actual[A]]] = _ evalMap {

      case DataEvent.Create(chunk) =>
        insertChunk(logHandler)(tempTable.obj, hyColumns, chunk) >>
        commit.as(none[OffsetKey.Actual[A]])

      case DataEvent.Delete(ids) =>
        none[OffsetKey.Actual[A]].pure[ConnectionIO]

      case DataEvent.Commit(offset) => refMode.get flatMap {
        case QWriteMode.Replace =>
          TempTable.applyTempTable(logHandler)(
            jwriteMode,
            tempTable,
            objFragment,
            unsafeName,
            unsafeSchema,
            columns,
            idColumn) >>
          refMode.set(QWriteMode.Append) >>
          commit.as(offset.some)
        case QWriteMode.Append =>
          val mbFilter = idColumn traverse_ { col =>
            TempTable.filterTempIds(logHandler)(tempTable, objFragment, col)
          }
         mbFilter >>
         TempTable.insertInto(logHandler)(tempTable, objFragment) >>
         TempTable.truncateTempTable(logHandler)(tempTable) >>
         commit.as(offset.some)
      }
    }

    def trace(logger: Logger)(msg: => String): F[Unit] =
      Effect[F].delay(logger.trace(msg))

    Stream.force {
      for {
        (objFragment, unsafeName, unsafeSchema) <- pathFragment[F](schema, path)

        refMode <- Ref.in[F, ConnectionIO, QWriteMode](writeMode)

        hygienicColumns = inputColumns.map { c =>
          (SQLServerHygiene.hygienicIdent(Ident(c.name)), c.tpe)
        }

        tempTable = TempTable.fromName(unsafeName, unsafeSchema)

        prepareTempTable =
          TempTable.dropTempTable(logHandler)(tempTable) >>
          TempTable.createTempTable(logHandler)(tempTable, hygienicColumns) >>
          commit

        putToTempTable =
          events.evalTap(logEvents).translate(toConnectionIO)
            .through(handleEvents(refMode, objFragment, unsafeName, unsafeSchema, tempTable, hyColumns))
            .unNone

        finalize =
          TempTable.dropTempTable(logHandler)(tempTable) >>
          commit


        logStart = trace(logger)("Starting load")
        logEnd = trace(logger)("Finished load")

      } yield {
        Stream.eval_(logStart) ++
        Stream.eval_(prepareTempTable.transact(xa)) ++
        putToTempTable.translate(λ[ConnectionIO ~> F](_.transact(xa))) ++
        Stream.eval_(finalize.transact(xa)) ++
        Stream.eval_(logEnd)
      }
    }
  }
}
