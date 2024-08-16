/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.apihubapplications.mongojobs

import com.google.inject.Inject
import play.api.Logging
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.AccessRequestsService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class HardDeleteMigrationJob @Inject()(
  applicationsRepository: ApplicationsRepository,
  accessRequestsService: AccessRequestsService
)(implicit ec: ExecutionContext) extends MongoJob with Logging {

  import HardDeleteMigrationJob._

  override def run(): Future[Unit] = {
    migrate().map(_ => ())
  }

  def migrate(): Future[MigrationSummary] = {
    applicationsRepository
      .findAll(None, Seq.empty, true)
      .map(
        applications =>
          applications
            .map(migrateApplication)
      )
      .flatMap(Future.sequence(_))
      .map(
        _.foldRight(MigrationSummary())((result, summary) => summary.add(result))
      )
      .andThen {
        case Success(summary) =>
          logger.info(s"Hard delete migration complete: summary=${summary.report}")
        case Failure(e) =>
          logger.error("Hard delete migration failed", e)
      }
  }

  private def migrateApplication(application: Application): Future[MigrationResult] = {
    if (application.isDeleted) {
      accessRequestsService
        .getAccessRequests(Some(application.safeId), None)
        .flatMap {
          case requests if requests.isEmpty =>
            applicationsRepository
              .delete(application.safeId)
              .map {
                case Right(_) =>
                  HardDeleted
                case Left(e) =>
                  logger.warn(s"Error deleting application ${application.safeId}", e)
                  Failed
              }
          case _ =>
            Future.successful(SoftDeleted)
        }
        .recover {
          case e =>
            logger.warn(s"Error deleting application ${application.safeId}", e)
            Failed
        }
    }
    else {
      Future.successful(NotDeleted)
    }
  }

}

object HardDeleteMigrationJob {

  sealed trait MigrationResult
  case object NotDeleted extends MigrationResult
  case object SoftDeleted extends MigrationResult
  case object HardDeleted extends MigrationResult
  case object Failed extends MigrationResult

  case class MigrationSummary(applications: Int, softDeleted: Int, hardDeleted: Int, failed: Int) {

    def add(result: MigrationResult): MigrationSummary = {
      result match {
        case NotDeleted => this.copy(applications = applications + 1)
        case SoftDeleted => this.copy(applications = applications + 1, softDeleted = softDeleted + 1)
        case HardDeleted => this.copy(applications = applications + 1, hardDeleted = hardDeleted + 1)
        case Failed => this.copy(applications = applications + 1, failed = failed + 1)
      }
    }

    def deleted: Int = this.softDeleted + this.hardDeleted + this.failed

    def report: String = {
      Seq(
        s"applications: $applications",
        s"deleted: $deleted",
        s"soft deleted: $softDeleted",
        s"hard deleted: $hardDeleted",
        s"failed: $failed"
      ).mkString(", ")
    }

  }

  object MigrationSummary {
    def apply(): MigrationSummary = {
      MigrationSummary(0, 0, 0, 0)
    }
  }

}
