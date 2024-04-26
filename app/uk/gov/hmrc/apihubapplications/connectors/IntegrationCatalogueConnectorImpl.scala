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
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.config.AppConfig
import uk.gov.hmrc.apihubapplications.models.api.ApiTeam
import uk.gov.hmrc.apihubapplications.models.exception.{ExceptionRaising, IntegrationCatalogueException}
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

  override def linkApiToTeam(apiTeam: ApiTeam)(implicit hc: HeaderCarrier): Future[Either[IntegrationCatalogueException, Unit]] = {
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

}
