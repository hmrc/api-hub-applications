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

import cats.implicits.toFoldableOps
import com.google.inject.Inject
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.IntegrationCatalogueConnector
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.application.ApiLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ApiNotFoundException, ApplicationsException}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted.DbApi
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class PopulateApplicationsApiTitleMongoJob @Inject()(
                                        applicationsRepository: ApplicationsRepository,
                                        integrationCatalogueConnector: IntegrationCatalogueConnector,
                                      )(implicit ec: ExecutionContext) extends MongoJob with Logging {

  import PopulateApplicationsApiTitleMongoJob._

  given hc: HeaderCarrier = HeaderCarrier()

  override def run(): Future[Unit] = {
    migrate().map(_ => ())
  }

  private[mongojobs] def migrate(): Future[PopulationSummary] =
    applicationsRepository
      .findAll(
        teamMemberEmail = None,
        owningTeams     = Seq.empty,
        includeDeleted  = true,
      )
      .map(filterApplicationsWithNoApiTitles)
      .flatMap {
        applications =>
          val initialState = (Seq.empty[Result], Map.empty[String, (Option[String], Result)])
          applications.foldM(initialState) { case ((applicationResults, apiTitlesWithResults), application) =>
            populateApiTitle(apiTitlesWithResults, application)
              .map { case (applicationResult, apiTitlesWithResults) =>
                (applicationResults :+ applicationResult, apiTitlesWithResults)
              }
          }
      }.map { case (applicationResults, apiTitlesWithResults) =>
        val apiResults = apiTitlesWithResults.values.map { case (_, apiResult) => apiResult }
        applicationResults ++ apiResults
      }
      .map(
        _.foldRight(PopulationSummary())((result, summary) => summary.add(result))
      )
      .andThen{
        case Success(summary) =>
          logger.info(s"Population complete: summary=${summary.report}")
        case Failure(error) => logger.error("Population failed", error)
      }

  private def filterApplicationsWithNoApiTitles(applications: Seq[Application]) =
    applications.filter(_.apis.exists(_.title.equalsIgnoreCase(DbApi.API_NAME_UNKNOWN)))

  private type ApiTitlesWithResults = Map[String, (Option[String], Result)]
  private type PopulationResult = (Result, ApiTitlesWithResults)
  private def populateApiTitle(apiTitlesWithResults: ApiTitlesWithResults, application: Application): Future[PopulationResult] =
    for {
      newApiTitlesWithResults <- findApiTitles(apiTitlesWithResults, application)
      populationResult        <- updateApplicationApis(newApiTitlesWithResults, application)
    } yield (populationResult, newApiTitlesWithResults)

  private def updateApplicationApis(apiTitlesWithResults: ApiTitlesWithResults, application: Application): Future[Result] = {
    val apisWithTitle = application.apis.flatMap(api => apiTitlesWithResults.get(api.id).map {
      case (maybeTitle, _) => api.copy(title = maybeTitle.getOrElse(DbApi.API_NAME_UNKNOWN))
    })
    val populatedApplication = application.copy(apis = apisWithTitle)
    applicationsRepository.update(populatedApplication).map {
      case Right(_) => ApplicationPopulated
      case Left(_)  => UpdateFailed
    }
  }

  private def findApiTitles(apiTitles: ApiTitlesWithResults, application: Application): Future[ApiTitlesWithResults] = {
    val apisWithNoTitles = application.apis.filterNot(api => apiTitles.contains(api.id))
    apisWithNoTitles.foldM(apiTitles){
      case (acc, applicationApi) =>
        integrationCatalogueConnector.findById(applicationApi.id)(hc)
          .map {
            case Right(api) => acc + (api.id -> (Some(api.title), ApiFound))
            case Left(error) if error.isInstanceOf[ApiNotFoundException] =>
              acc + (applicationApi.id -> (None, ApiNotFound))
            case Left(error) =>
              acc + (applicationApi.id -> (None, IntegrationCatalogueError))
          }
    }
  }

}

object PopulateApplicationsApiTitleMongoJob {

  sealed trait Result
  private case object ApplicationPopulated extends Result
  private case object ApiFound extends Result
  private case object ApiNotFound extends Result
  private case object IntegrationCatalogueError extends Result
  private case object UpdateFailed extends Result

  case class PopulationSummary(applications: Int, apis: Int, apisNotFound: Int, integrationCatalogueErrors: Int, updateFailed: Int) {

    def add(result: Result): PopulationSummary = {
      result match {
        case ApplicationPopulated      => this.copy(applications = applications + 1)
        case ApiFound                  => this.copy(apis = apis + 1)
        case ApiNotFound               => this.copy(apisNotFound = apisNotFound + 1)
        case IntegrationCatalogueError => this.copy(integrationCatalogueErrors = integrationCatalogueErrors + 1)
        case UpdateFailed              => this.copy(applications = applications + 1, updateFailed = updateFailed + 1)
      }
    }

    def report: String = {
      Seq(
        s"applications: $applications",
        s"apis: $apis",
        s"apisNotFound: $apisNotFound",
        s"integrationCatalogueErrors: $integrationCatalogueErrors",
        s"updateFailed: $updateFailed"
      ).mkString(", ")
    }

  }

  object PopulationSummary {
    def apply(): PopulationSummary = {
      PopulationSummary(0, 0, 0, 0, 0)
    }
  }

}