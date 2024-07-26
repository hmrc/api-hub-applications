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

package uk.gov.hmrc.apihubapplications.services

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Secondary}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.requests.AddApiRequest
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.services.helpers.{ApplicationEnrichers, ScopeFixer}
import uk.gov.hmrc.http.HeaderCarrier

import java.time.Clock
import scala.concurrent.{ExecutionContext, Future}

trait ApplicationsApiService {

  def addApi(applicationId: String, newApi: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

  def removeApi(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]]

}

@Singleton
class ApplicationsApiServiceImpl @Inject()(
  searchService: ApplicationsSearchService,
  accessRequestsService: AccessRequestsService,
  repository: ApplicationsRepository,
  idmsConnector: IdmsConnector,
  scopeFixer: ScopeFixer,
  clock: Clock
)(implicit ec: ExecutionContext) extends ApplicationsApiService with Logging with ExceptionRaising {

  override def addApi(applicationId: String, newApi: AddApiRequest)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {

    def doRepositoryUpdate(application: Application, newApi: AddApiRequest): Future[Either[ApplicationsException, Unit]] = {
      repository.update(
        application
          .removeApi(newApi.id)
          .addApi(Api(newApi.id, newApi.endpoints))
          .updated(clock)
      )
    }

    searchService.findById(applicationId, enrich = true).flatMap {
      case Right(application) =>
        val updated = application.removeApi(newApi.id)
        ApplicationEnrichers.process(
          updated,
          newApi.scopes.distinct.map(scope => ApplicationEnrichers.scopeAddingApplicationEnricher(Secondary, updated, idmsConnector, scope))
        ) flatMap {
          case Right(_) => doRepositoryUpdate(application, newApi) map {
            case Right(_) => Right(())
            case Left(e) => Left(e)
          }
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) => Future.successful(Left(e))
    }

  }

  override def removeApi(applicationId: String, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    searchService.findById(applicationId, enrich = true).flatMap {
      case Right(application) if application.hasApi(apiId) => removeApi(application, apiId)
      case Right(_) => Future.successful(Left(raiseApiNotFoundException.forApplication(applicationId, apiId)))
      case Left(_: ApplicationNotFoundException) => Future.successful(Left(raiseApplicationNotFoundException.forId(applicationId)))
      case Left(e) => Future.successful(Left(e))
    }
  }

  private def removeApi(application: Application, apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    val updated = application
      .removeApi(apiId)
      .updated(clock)

    scopeFixer.fix(updated).flatMap {
      case Right(fixed) =>
        accessRequestsService.cancelAccessRequests(fixed.safeId, apiId).flatMap {
          case Right(_) => repository.update(fixed)
          case Left(e) => Future.successful(Left(e))
        }
      case Left(e) =>
        Future.successful(Left(e))
    }
  }

}
