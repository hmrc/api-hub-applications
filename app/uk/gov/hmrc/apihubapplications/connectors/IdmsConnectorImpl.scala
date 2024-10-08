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
import play.api.http.Status.NOT_FOUND
import play.api.libs.json.Json
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.apihubapplications.models.application.{Application, EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.{ExceptionRaising, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope, Secret}
import uk.gov.hmrc.apihubapplications.services.helpers.Helpers.{ignoreClientNotFound, useFirstException}
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IdmsConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2
)(implicit ec: ExecutionContext) extends IdmsConnector
  with Logging
  with ExceptionRaising
  with ProxySupport
  with CorrelationIdSupport {

  import ProxySupport.*
  import CorrelationIdSupport.*

  override def createClient(environmentName: EnvironmentName, client: Client)(implicit hc: HeaderCarrier): Future[Either[IdmsException, ClientResponse]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients"
    val context = Seq("environmentName" -> environmentName, "client" -> client)
      .withCorrelationId()

    httpClient.post(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(environmentName)*)
      .withBody(Json.toJson(client))
      .withProxyIfRequired(environmentName)
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
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-secret"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.get(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(environmentName)*)
      .withProxyIfRequired(environmentName)
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
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.delete(url)
      .setHeader(headersForEnvironment(environmentName)*)
      .withProxyIfRequired(environmentName)
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

  override def deleteAllClients(application: Application)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {
    val primaryFutures = application.environments.primary.credentials.map(credential => deleteClient(Primary, credential.clientId))
    val secondaryFutures = application.environments.secondary.credentials.map(credential => deleteClient(Secondary, credential.clientId))
    val allFutures = primaryFutures ++ secondaryFutures

    Future.sequence(allFutures)
      .map(ignoreClientNotFound)
      .map(useFirstException)
      .map {
        case Right(_) => Right(())
        case Left(e) => Left(e)
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

  private def headersForEnvironment(environmentName: EnvironmentName): Seq[(String, String)] = {
    Seq(("Authorization", authorizationForEnvironment(environmentName))) :++
      (environmentName match {
        case Primary => Seq.empty
        case Secondary => Seq(("x-api-key", servicesConfig.getConfString(s"idms-$environmentName.apiKey", "")))
      })
  }

  private def authorizationForEnvironment(environmentName: EnvironmentName): String = {
    val clientId = servicesConfig.getConfString(s"idms-$environmentName.clientId", "")
    val secret = servicesConfig.getConfString(s"idms-$environmentName.secret", "")

    val endcoded = Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
    s"Basic $endcoded"
  }

  override def newSecret(environmentName: EnvironmentName, clientId: String)(implicit hc: HeaderCarrier): Future[Either[IdmsException, Secret]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-secret"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.post(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(environmentName)*)
      .withProxyIfRequired(environmentName)
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
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-scopes/$scopeId"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId, "scopeId" -> scopeId)
      .withCorrelationId()

    httpClient.put(url)
      .setHeader(headersForEnvironment(environmentName)*)
      .withProxyIfRequired(environmentName)
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

  override def deleteClientScope(
    environmentName: EnvironmentName,
    clientId: String,
    scopeId: String
  )(implicit hc: HeaderCarrier): Future[Either[IdmsException, Unit]] = {

    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-scopes/$scopeId"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId, "scopeId" -> scopeId)
      .withCorrelationId()

    httpClient.delete(url)
      .setHeader(headersForEnvironment(environmentName)*)
      .withProxyIfRequired(environmentName)
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

  override def fetchClientScopes(
    environmentName: EnvironmentName,
    clientId: String
  )(implicit hc: HeaderCarrier): Future[Either[IdmsException, Seq[ClientScope]]] = {
    val url = url"${baseUrlForEnvironment(environmentName)}/identity/clients/$clientId/client-scopes"
    val context = Seq("environmentName" -> environmentName, "clientId" -> clientId)
      .withCorrelationId()

    httpClient.get(url)
      .setHeader(("Accept", "application/json"))
      .setHeader(headersForEnvironment(environmentName)*)
      .withProxyIfRequired(environmentName)
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

}
