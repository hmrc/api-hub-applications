/*
 * Copyright 2025 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.accessRequest.AccessRequest
import uk.gov.hmrc.apihubapplications.repositories.AccessRequestsRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class AccessRequestEnvironmentJob @Inject()(
  repository: AccessRequestsRepository,
  hipEnvironments: HipEnvironments
) (implicit ec: ExecutionContext) extends MongoJob with Logging {

  import AccessRequestEnvironmentJob.*

  override def run(): Future[Unit] = {
    setEnvironment().map(_ => ())
  }

  def setEnvironment(): Future[MigrationSummary] = {
    repository.find(None, None)
      .map(_.map(processAccessRequest))
      .flatMap(Future.sequence(_))
      .map(
        _.foldRight(MigrationSummary())((result, summary) => summary.add(result))
      )
      .andThen {
        case Success(summary) =>
          logger.info(s"Set access request environment migration complete: summary=${summary.report}")
        case Failure(e) =>
          logger.error("Set access request environment migration failed", e)
      }
  }

  private def processAccessRequest(accessRequest: AccessRequest): Future[MigrationResult] = {
    accessRequest.environmentId match {
      case Some(_) => Future.successful(NothingToDo)
      case _ =>
        repository.update(
          accessRequest.copy(environmentId = Some(hipEnvironments.production.id))
        ).map {
          case Right(_) => Processed
          case Left(e) =>
            logger.warn(s"Error processing access request ${accessRequest.id}", e)
            Failed
        }
    }
  }

}

object AccessRequestEnvironmentJob {

  sealed trait MigrationResult

  case object Processed extends MigrationResult
  case object NothingToDo extends MigrationResult
  case object Failed extends MigrationResult

  case class MigrationSummary(accessRequests: Int, processed: Int, failed: Int) {

    def add(migrationResult: MigrationResult): MigrationSummary = {
      migrationResult match {
        case Processed => this.copy(accessRequests = accessRequests + 1, processed = processed + 1)
        case NothingToDo =>  this.copy(accessRequests = accessRequests + 1)
        case Failed => this.copy(accessRequests = accessRequests + 1, failed = failed + 1)
      }
    }

    def report: String = {
      Seq(
        s"accessRequests: $accessRequests",
        s"processed: $processed",
        s"failed: $failed"
      ).mkString(", ")
    }

  }

  object MigrationSummary {

    def apply(): MigrationSummary = {
      MigrationSummary(0, 0, 0)
    }

  }

}
