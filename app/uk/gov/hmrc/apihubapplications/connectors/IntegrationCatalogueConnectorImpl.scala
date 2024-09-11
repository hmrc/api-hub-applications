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

package uk.gov.hmrc.apihubapplications.connectors

import com.google.inject.Inject
import play.api.Logging
import play.api.http.HeaderNames._
import play.api.http.MimeTypes.JSON
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.apihubapplications.config.AppConfig
import uk.gov.hmrc.apihubapplications.models.api.{ApiDetail, ApiTeam}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, ExceptionRaising}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

class IntegrationCatalogueConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2,
  appConfig: AppConfig
)(implicit ec: ExecutionContext) extends IntegrationCatalogueConnector with Logging with ExceptionRaising {

  private val baseUrl = servicesConfig.baseUrl("integration-catalogue")
  private val appAuthToken = appConfig.appAuthToken

  override def linkApiToTeam(apiTeam: ApiTeam)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    httpClient.post(url"$baseUrl/integration-catalogue/apis/team")
      .setHeader(CONTENT_TYPE -> JSON)
      .setHeader(AUTHORIZATION -> appAuthToken)
      .withBody(Json.toJson(apiTeam))
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(_) => Right(())
        case Left(e) => Left(raiseIntegrationCatalogueException.unexpectedResponse(e.statusCode))
      }
  }

  override def findById(apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, ApiDetail]] = {
    httpClient.get(url"$baseUrl/integration-catalogue/integrations/$apiId")
      .setHeader(ACCEPT -> JSON)
      .setHeader(AUTHORIZATION -> appAuthToken)
      .execute[Either[UpstreamErrorResponse, ApiDetail]]
      .map {
        case Right(apiDetail) => Right(apiDetail)
        case Left(e) if e.statusCode == NOT_FOUND => Left(raiseApiNotFoundException.forId(apiId))
        case Left(e) => Left(raiseIntegrationCatalogueException.unexpectedResponse(e.statusCode))
      }
  }

  override def updateApiTeam(apiId: String, teamId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    httpClient.put(url"$baseUrl/integration-catalogue/apis/$apiId/teams/$teamId")
      .setHeader((ACCEPT, JSON))
      .setHeader(AUTHORIZATION -> appAuthToken)
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(()) => Right(())
        case Left(e) if e.statusCode == NOT_FOUND => Left(raiseApiNotFoundException.forId(apiId))
        case Left(e) => Left(raiseIntegrationCatalogueException.unexpectedResponse(e.statusCode))
      }
  }


  override def removeApiTeam(apiId: String)(implicit hc: HeaderCarrier): Future[Either[ApplicationsException, Unit]] = {
    httpClient.delete(url"$baseUrl/integration-catalogue/apis/$apiId/teams")
      .setHeader((ACCEPT, JSON))
      .setHeader(AUTHORIZATION -> appAuthToken)
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(()) => Right(())
        case Left(e) if e.statusCode == NOT_FOUND => Left(raiseApiNotFoundException.forId(apiId))
        case Left(e) => Left(raiseIntegrationCatalogueException.unexpectedResponse(e.statusCode))
      }
  }

}
