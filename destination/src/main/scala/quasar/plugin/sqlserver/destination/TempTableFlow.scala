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

import quasar.api.Column
import quasar.api.resource.ResourcePath
import quasar.connector.{MonadResourceErr, ResourceError}
import quasar.lib.jdbc.{Ident, Slf4sLogHandler}
import quasar.lib.jdbc.destination.WriteMode
import quasar.plugin.sqlserver._

import cats.{Alternative, ~>}
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._

import doobie._
import doobie.implicits._
import doobie.free.connection.commit

import fs2.Chunk

import java.lang.CharSequence

import org.slf4s.Logger

sealed trait TempTableFlow {
  def ingest(chunk: Chunk[CharSequence]): ConnectionIO[Unit]
  def replace: ConnectionIO[Unit]
  def append: ConnectionIO[Unit]
}

object TempTableFlow {
  def apply[F[_]: Sync: MonadResourceErr](
      xa: Transactor[F],
      logger: Logger,
      writeMode: WriteMode,
      path: ResourcePath,
      schema: String,
      columns: NonEmptyList[(HI, SQLServerType)],
      idColumn: Option[Column[_]],
      filterColumn: Option[Column[_]],
      retry: ConnectionIO ~> ConnectionIO)
      : Resource[F, TempTableFlow] = {

    val log = Slf4sLogHandler(logger)

    def checkWriteMode(unsafeName: String, unsafeSchema: Option[String]): F[Unit] = {
      val existing: ConnectionIO[Boolean] = ifExists(log)(unsafeName, unsafeSchema).option map { results =>
        results.exists(_ === 1)
      }
      writeMode match {
        case WriteMode.Create => existing.transact(xa) flatMap { exists =>
          MonadResourceErr[F].raiseError(
            ResourceError.accessDenied(
              path,
              "Create mode is set but the table exists already".some,
              none)).whenA(exists)
        }
        case _ =>
          ().pure[F]
      }
    }
    // No retries in temp table init and checkWriteMode, since there is nothing yet inserted
    val acquire: F[(TempTable, TempTableFlow)] = for {
      (objFragment, unsafeName, unsafeSchema) <- pathFragment[F](schema, path)
      _ <- checkWriteMode(unsafeName, unsafeSchema)
      tempTable = TempTable(log, writeMode, unsafeName, unsafeSchema, objFragment, columns, idColumn, filterColumn)
      _ <- {
        tempTable.drop >>
        tempTable.create >>
        commit
      }.transact(xa)
    } yield {
      val flow = new TempTableFlow {
        def ingest(chunk: Chunk[CharSequence]): ConnectionIO[Unit] = retry {
          tempTable.ingest(chunk) >> commit
        }
        def replace = retry {
          tempTable.persist >> commit
        }
        def append = retry {
          tempTable.append >> commit
        }
      }
      (tempTable, flow)
    }
    // We don't need retry here too, because when we're in `release`
    // 1. Everything was OK, and temp table is empty, so, spending 10 minutes to just drop empty table blocking
    // subsequent pushes doesn't seem good idea
    // 2. Everything failed, we already tried to apply temp table for 10 minutes and had no success, other 10 minutes
    // to just release is wasting time.
    val release: ((TempTable, TempTableFlow)) => F[Unit] = { case (tempTable, _) =>
      (tempTable.drop >> commit).transact(xa)
    }
    Resource.make(acquire)(release).map(_._2)
  }

  private trait TempTable {
    def ingest(chunk: Chunk[CharSequence]): ConnectionIO[Unit]
    def drop: ConnectionIO[Unit]
    def create: ConnectionIO[Unit]
    def persist: ConnectionIO[Unit]
    def append: ConnectionIO[Unit]
  }

  private object TempTable {
    def apply(
        log: LogHandler,
        writeMode: WriteMode,
        unsafeName: String,
        unsafeSchema: Option[String],
        tableFragment: Fragment,
        columns: NonEmptyList[(HI, SQLServerType)],
        idColumn: Option[Column[_]],
        filterColumn: Option[Column[_]])
        : TempTable = {
      val schema = unsafeSchema.getOrElse("dbo")
      val tempName = s"precog_temp_$unsafeName"
      val hyName = SQLServerHygiene.hygienicIdent(Ident(tempName))
      val hySchema = SQLServerHygiene.hygienicIdent(Ident(schema))
      val tempFragment = Fragment.const0(hySchema.forSqlName) ++ fr0"." ++ Fragment.const0(hyName.forSqlName)
      val uName = hyName.unsafeForSqlName
      val uSchema = hySchema.unsafeForSqlName

      new TempTable {
        def ingest(chunk: Chunk[CharSequence]): ConnectionIO[Unit] =
          insertChunk(log)(tempFragment, columns, chunk)

        def drop: ConnectionIO[Unit] =
          ifExists(log)(uName, uSchema.some).option flatMap { results =>
            if (results.exists(_ === 1)) {
              (fr"DROP TABLE" ++ tempFragment)
                .updateWithLogHandler(log)
                .run
                .void
            } else {
              ().pure[ConnectionIO]
            }
          }

        def create: ConnectionIO[Unit] = {
          ifExists(log)(uName, uSchema.some).option.flatMap({ results =>
            if (results.exists(_ === 0)) {
              val columnsObj = createColumnSpecs(columns)
              (fr"CREATE TABLE" ++ tempFragment ++ fr0" " ++ columnsObj)
                .updateWithLogHandler(log)
                .run
                .void
            } else {
              ().pure[ConnectionIO]
            }
          }) >>
          filterColumn.traverse_({ (col: Column[_]) =>
            val colFragment = Fragments.parentheses {
              Fragment.const(SQLServerHygiene.hygienicIdent(Ident(col.name)).forSqlName)
            }
            createIndex(log)(tempFragment, hyName.unsafeForSqlName, colFragment, indexName(unsafeName))
          })
        }

        private def truncate: ConnectionIO[Unit] =
          ifExists(log)(uName, uSchema.some).option flatMap { results =>
            if (results.exists(_ === 1)) {
              (fr"TRUNCATE TABLE" ++ tempFragment)
                .updateWithLogHandler(log)
                .run
                .void
            } else {
              ().pure[ConnectionIO]
            }
          }

        private def insertInto: ConnectionIO[Unit] =
          (fr"INSERT INTO" ++
            tableFragment ++ fr0" " ++
            fr"SELECT * FROM" ++
            tempFragment)
            .updateWithLogHandler(log)
            .run
            .void

        private def rename: ConnectionIO[Unit] =
          (fr0"EXEC SP_RENAME '" ++
            tempFragment ++ fr0"', '" ++
            Fragment.const0(SQLServerHygiene.hygienicIdent(Ident(unsafeName)).unsafeForSqlName) ++ fr0"'")
            .updateWithLogHandler(log)
            .run
            .void

        private def filterTempIds(idColumn: Column[_]): ConnectionIO[Unit] = {
          val mkColumn: String => Fragment = parent =>
            Fragment.const0(parent) ++ fr0"." ++
            Fragment.const0(SQLServerHygiene.hygienicIdent(Ident(idColumn.name)).forSqlName)

          val fragment =
            fr"DELETE target FROM" ++ tableFragment ++
            fr" target INNER JOIN" ++ tempFragment ++ fr" temp" ++
            fr"ON" ++ mkColumn("target") ++ fr0"=" ++ mkColumn("temp")

          fragment.updateWithLogHandler(log).run.void
        }

        def append: ConnectionIO[Unit] = {
          val mbFilter = filterColumn traverse_ { col => filterTempIds(col) }
          mbFilter >>
          insertInto >>
          truncate
        }

        def persist: ConnectionIO[Unit] = {
          val mbCreateIndex =
            (Alternative[Option].guard(idColumn.map(_.name) =!= filterColumn.map(_.name)) *> idColumn) traverse_ { col =>
              val colFragment = Fragments.parentheses {
                Fragment.const(SQLServerHygiene.hygienicIdent(Ident(col.name)).forSqlName)
              }
              createIndex(log)(tableFragment, unsafeName, colFragment, indexName(unsafeName))
            }
          val prepare = writeMode match {
            case WriteMode.Create =>
              createTable(log)(tableFragment, columns) >>
              mbCreateIndex >>
              insertInto >>
              truncate
            case WriteMode.Replace =>
              dropTableIfExists(log)(tableFragment) >>
              rename >>
              // We don't remove temp table in `persist`, it's handled in Resource instead.
              create
            case WriteMode.Truncate =>
              // This is `insertInto` instead of `renameTable` because user might want to preserve indices and so on
              truncateTable(log)(tableFragment, unsafeName, unsafeSchema, columns)  >>
              insertInto >>
              truncate
            case WriteMode.Append =>
              createTableIfNotExists(log)(tableFragment, unsafeName, unsafeSchema, columns) >>
              insertInto >>
              truncate
          }

          prepare >> mbCreateIndex
        }
      }
    }
  }
}
