/*
 * Copyright 2023 HM Revenue & Customs
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
import play.api.libs.json.Json
import uk.gov.hmrc.apihubapplications.models.application.EnvironmentName
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, IdmsException, Secret}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IdmsConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2
)(implicit ec: ExecutionContext) extends IdmsConnector with Logging {

  override def createClient(environmentName: EnvironmentName, client: Client)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients"

    httpClient.post(url)
      .setHeader(("Accept", "application/json"))
      .withBody(Json.toJson(client))
      .execute[ClientResponse]
      .map(Right(_))
      .recover {
        case throwable =>
          idmsError(throwable)
      }
  }

  override def fetchClient(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-secret"

    httpClient.get(url)
      .setHeader(("Accept", "application/json"))
      .execute[Either[UpstreamErrorResponse, Secret]]
      .map {
        case Right(secret) => Right(ClientResponse(clientId, secret.secret))
        case Left(e) if e.statusCode == 404 =>
          val message = s"Client not found: clientId=$clientId"
          logger.error(message, e)
          Left(IdmsException(message))
        case Left(e) =>
          badIdmsStatus(e)
      }
      .recover {
        case throwable =>
          idmsError(throwable)
      }
  }

  private def baseUrlForEnvironment(environmentName: EnvironmentName): String = {
    val baseUrl = servicesConfig.baseUrl(s"idms-$environmentName")
    val path = servicesConfig.getConfString(s"idms-$environmentName.path", "")

    if (path.isEmpty) {
      baseUrl
    }
    else {
      s"$baseUrl/$path"
    }
  }

  override def newSecret(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Secret]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-secret"

    httpClient.post(url)
      .setHeader(("Accept", "application/json"))
      .execute[Either[UpstreamErrorResponse, Secret]]
      .map {
        case Right(secret) => Right(secret)
        case Left(e) if e.statusCode == 404 =>
          val message = s"Client not found: clientId=$clientId"
          logger.error(message, e)
          Left(IdmsException(message))
        case Left(e) =>
          badIdmsStatus(e)
      }
      .recover {
        case throwable =>
          idmsError(throwable)
      }

  }

  private def idmsError(throwable: Throwable) = {
    val message = "Error calling IDMS"
    logger.error(message, throwable)
    Left(IdmsException(message, throwable))
  }

  private def badIdmsStatus(e: UpstreamErrorResponse) = {
    val message = s"Unexpected response ${e.statusCode} returned from IDMS"
    logger.error(message, e)
    Left(IdmsException(message))
  }

  override def addClientScope(environmentName: EnvironmentName, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-scopes/$scopeId"

    httpClient.put(url)
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(_) => Right({})
        case Left(e) if e.statusCode == 404 =>
          val message = s"Client not found: clientId=$clientId"
          logger.error(message, e)
          Left(IdmsException(message))
        case Left(e) =>
          badIdmsStatus(e)
      }
      .recover {
        case throwable =>
          idmsError(throwable)
      }
  }
}
