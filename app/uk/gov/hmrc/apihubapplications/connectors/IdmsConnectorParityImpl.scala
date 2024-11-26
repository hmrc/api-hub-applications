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
import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.exception.{ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{ignoreClientNotFound, useFirstException}
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IdmsConnectorParityImpl @Inject()(
  httpClient: HttpClientV2,
  hipEnvironments: HipEnvironments
)(implicit ec: ExecutionContext) extends IdmsConnector
  with Logging
  with ExceptionRaising
  with ProxySupport
  with CorrelationIdSupport {

  import ProxySupport.*
  import CorrelationIdSupport.*

  override def createClient(environmentName: EnvironmentName, client: Client)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients"
    val context = Seq("environmentName" -> environmentName, "client" -> client)
      .withCorrelationId()

    httpClient.post(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withBody(Json.toJson(client))
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, ClientResponse]]
      .map {
        case Right(clientResponse) => Right(clientResponse)
        case Left(e) => Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def fetchClient(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients/$clientId/client-secret"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.get(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, Secret]]
      .map {
        case Right(secret) => Right(ClientResponse(clientId, secret.secret))
        case Left(e) if e.statusCode == 404 =>
          Left(raiseIdmsException.clientNotFound(clientId))
        case Left(e) =>
          Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def deleteClient(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients/$clientId"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.delete(url)
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(()) => Right(())
        case Left(e) if e.statusCode == NOT_FOUND => Left(raiseIdmsException.clientNotFound(clientId))
        case Left(e) => Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def newSecret(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Secret]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients/$clientId/client-secret"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.post(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, Secret]]
      .map {
        case Right(secret) => Right(secret)
        case Left(e) if e.statusCode == 404 =>
          Left(raiseIdmsException.clientNotFound(clientId))
        case Left(e) =>
          Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def addClientScope(environmentName: EnvironmentName, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients/$clientId/client-scopes/$scopeId"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId, "scopeId" -> scopeId)
      .withCorrelationId()

    httpClient.put(url)
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(_) => Right(())
        case Left(e) if e.statusCode == 404 =>
          Left(raiseIdmsException.clientNotFound(clientId))
        case Left(e) =>
          Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def deleteClientScope(environmentName: EnvironmentName, clientId: String, scopeId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients/$clientId/client-scopes/$scopeId"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId, "scopeId" -> scopeId)
      .withCorrelationId()

    httpClient.delete(url)
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(_) => Right(())
        case Left(e) if e.statusCode == NOT_FOUND => Left(raiseIdmsException.clientNotFound(clientId))
        case Left(e) => Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def fetchClientScopes(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Seq[ClientScope]]] = {
    val hipEnvironment = hipEnvironments.forEnvironmentName(environmentName)

    val url = url"${hipEnvironment.apimUrl}/identity/clients/$clientId/client-scopes"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.get(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, Seq[ClientScope]]]
      .map {
        case Right(scopes) => Right(scopes)
        case Left(e) if e.statusCode == 404 =>
          Left(raiseIdmsException.clientNotFound(clientId))
        case Left(e) =>
          Left(raiseIdmsException.unexpectedResponse(e, context))
      }
      .recover {
        case throwable =>
          Left(raiseIdmsException.error(throwable, context))
      }
  }

  override def deleteAllClients(application: Application)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    Future.sequence(
        hipEnvironments.environments.flatMap(
          hipEnvironment =>
            application.getCredentials(hipEnvironment.environmentName).map(
              credential =>
                deleteClient(hipEnvironment.environmentName, credential.clientId)
            )
        )
      )
      .map(ignoreClientNotFound)
      .map(useFirstException)
      .map {
        case Right(_) => Right(())
        case Left(e) => Left(e)
      }
  }

  private def headersForEnvironment(hipEnvironment: HipEnvironment): Seq[(String, String)] = {
    Seq(
      Some(("Authorization", authorizationForEnvironment(hipEnvironment))),
      hipEnvironment.apiKey.map(apiKey => ("x-api-key", apiKey))
    ).flatten
  }

  private def authorizationForEnvironment(hipEnvironment: HipEnvironment): String = {
    val clientId = hipEnvironment.clientId
    val secret = hipEnvironment.secret

    val endcoded = Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
    s"Basic $endcoded"
  }

}
