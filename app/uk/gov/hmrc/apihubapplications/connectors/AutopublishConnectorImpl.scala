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

package uk.gov.hmrc.apihubapplications.connectors

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.http.Status.NOT_FOUND
import uk.gov.hmrc.apihubapplications.models.exception.{AutopublishException, ExceptionRaising}
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AutopublishConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2
)(implicit ec: ExecutionContext) extends AutopublishConnector with Logging with ExceptionRaising {

  private val baseUrl = servicesConfig.baseUrl("integration-catalogue-autopublish")

  override def forcePublish(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[AutopublishException, Unit]] = {
    httpClient.put(url"$baseUrl/integration-catalogue-autopublish/apis/$publisherReference/publish")
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(_) => Right(())
        case Left(e) if e.statusCode == NOT_FOUND => Left(raiseAutopublishException.deploymentNotFound(publisherReference))
        case Left(e) => Left(raiseAutopublishException.unexpectedResponse(e.statusCode))
      }
  }

}
