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
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import play.api.http.Status._
import play.api.libs.json.{JsPath, Json, JsonValidationError}
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.libs.ws.WSBodyWritables.bodyWritableOf_Multipart
import play.api.mvc.MultipartFormData.DataPart
import uk.gov.hmrc.apihubapplications.models.apim._
import uk.gov.hmrc.apihubapplications.models.application.{EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ExceptionRaising}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class APIMConnectorImpl @Inject()(
                                   servicesConfig: ServicesConfig,
                                   httpClient: HttpClientV2
                                 )(implicit ec: ExecutionContext) extends APIMConnector with Logging with ExceptionRaising with HttpErrorFunctions with ProxySupport {

  import ProxySupport._

  override def validateInPrimary(oas: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, ValidateResponse]] = {
    httpClient.post(url"${baseUrlForEnvironment(Primary)}/v1/simple-api-deployment/validate")
      .setHeader("Authorization" -> authorizationForEnvironment(Primary))
      .setHeader("Content-Type" -> "application/yaml")
      .withBody(oas)
      .execute[HttpResponse]
      .map(
        response =>
          if (is2xx(response.status)) {
            Right(SuccessfulValidateResponse)
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue))
          }
      )
  }

  override def deployToSecondary(request: DeploymentsRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    val useProxyForSecondary = servicesConfig.getConfBool(s"apim-secondary.useProxy", true)
    val metadata = Json.toJson(CreateMetadata(request))
    val context = Seq("metadata" -> Json.prettyPrint(metadata))

    httpClient.post(url"${baseUrlForEnvironment(Secondary)}/v1/simple-api-deployment/deployments")
      .setHeader(headersForEnvironment(Secondary)*)
      .withProxyIfRequired(Secondary, useProxyForSecondary)
      .setHeader("Accept" -> "application/json")
      .withBody(
        Source(
          Seq(
            DataPart("metadata", metadata.toString()),
            DataPart("openapi", request.oas)
          )
        )
      )
      .execute[HttpResponse]
      .map(
        response =>
          if (is2xx(response.status)) {
            handleSuccessfulRequest(response)
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue, context))
          }
      )
  }

  override def redeployToSecondary(publisherReference: String, request: RedeploymentRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    val useProxyForSecondary = servicesConfig.getConfBool(s"apim-secondary.useProxy", true)
    val metadata = Json.toJson(UpdateMetadata(request))
    val context = Seq("publisherReference" -> publisherReference, "metadata" -> Json.prettyPrint(metadata))

    httpClient.put(url"${baseUrlForEnvironment(Secondary)}/v1/simple-api-deployment/deployments/$publisherReference")
      .setHeader(headersForEnvironment(Secondary)*)
      .withProxyIfRequired(Secondary, useProxyForSecondary)
      .setHeader("Accept" -> "application/json")
      .withBody(
        Source(
          Seq(
            DataPart("metadata", metadata.toString()),
            DataPart("openapi", request.oas)
          )
        )
      )
      .execute[HttpResponse]
      .map(
        response =>
          if (is2xx(response.status)) {
            handleSuccessfulRequest(response)
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
          }
          else if (response.status.intValue == NOT_FOUND) {
            Left(raiseApimException.serviceNotFound(publisherReference))
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue, context))
          }
      )
  }

  private def handleSuccessfulRequest(response: HttpResponse): Either[ApimException, SuccessfulDeploymentsResponse] = {
    response.json.validate[SuccessfulDeploymentsResponse].fold(
      (errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]) => Left(raiseApimException.invalidResponse(errors)),
      deploymentsResponse => {
        logger.info(s"APIM Response: ${Json.prettyPrint(Json.toJson(deploymentsResponse))}")
        Right(deploymentsResponse)
      }
    )
  }

  private def handleBadRequest(response: HttpResponse): Either[ApimException, InvalidOasResponse] = {
    (if (response.body.isEmpty) {
      None
    }
    else {
      response.json.validate[FailuresResponse]
        .fold(
          _ => {
            logger.warn(s"Unknown response body from Simple OAS Deployment service:${System.lineSeparator()}${response.body}")
            None
          },
          failure => {
            logger.warn(s"Received failure response from Simple OAS Deployment service: ${response.json}")
            Some(InvalidOasResponse(failure))
          }
        )
    })
      .map(Right(_))
      .getOrElse(Left(raiseApimException.unexpectedResponse(BAD_REQUEST)))
  }

  override def getDeployment(publisherReference: String, environment: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]] = {
    val useProxyForSecondary = servicesConfig.getConfBool(s"apim-$environment.useProxy", true)
    val url = url"${baseUrlForEnvironment(environment)}/v1/oas-deployments/$publisherReference"
    val context = Seq("publisherReference" -> publisherReference, "environment" -> environment)

    httpClient.get(url)
      .setHeader(headersForEnvironment(environment)*)
      .withProxyIfRequired(environment, useProxyForSecondary)
      .execute[Either[UpstreamErrorResponse, SuccessfulDeploymentResponse]]
      .map {
        case Right(deployment) => Right(Some(deployment))
        case Left(e) if e.statusCode == 404 => Right(None)
        case Left(e) if e.statusCode == 403 =>
          logger.warn(s"Received 403 back from APIM ${environment.toString} whilst looking up publisher reference: $publisherReference and useProxyForSecondary: $useProxyForSecondary. Full url: ${url.toString}")
          Right(None)
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode, context))
      }
  }

  override def getDeploymentDetails(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentDetails]] = {
    val context = Seq("publisherReference" -> publisherReference)

    httpClient.get(url"${baseUrlForEnvironment(Secondary)}/v1/simple-api-deployment/deployments/$publisherReference")
      .setHeader("Authorization" -> authorizationForEnvironment(Secondary))
      .setHeader("Accept" -> "application/json")
      .execute[Either[UpstreamErrorResponse, DetailsResponse]]
      .map {
        case Right(detailsResponse) => Right(detailsResponse.toDeploymentDetails)
        case Left(e) if e.statusCode == 404 => Left(raiseApimException.serviceNotFound(publisherReference))
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode, context))
      }
  }

  override def promoteToProduction(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    val context = Seq("publisherReference" -> publisherReference)
    val deploymentFrom = DeploymentFrom(
      env = "env/test",
      serviceId = publisherReference
    )

    httpClient.put(url"${baseUrlForEnvironment(Primary)}/v1/simple-api-deployment/deployment-from")
      .setHeader("Authorization" -> authorizationForEnvironment(Primary))
      .setHeader("Content-Type" -> "application/json")
      .setHeader("Accept" -> "application/json")
      .withBody(Json.toJson(deploymentFrom))
      .execute[HttpResponse]
      .map(
        response =>
          if (is2xx(response.status)) {
            handleSuccessfulRequest(response)
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
          }
          else if (response.status.intValue == NOT_FOUND) {
            Left(raiseApimException.serviceNotFound(publisherReference))
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue, context))
          }
      )
  }

  private def baseUrlForEnvironment(environmentName: EnvironmentName): String = {
    val baseUrl = servicesConfig.baseUrl(s"apim-$environmentName")
    val path = servicesConfig.getConfString(s"apim-$environmentName.path", "")

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
        case Secondary => Seq(("x-api-key", servicesConfig.getConfString(s"apim-$environmentName.apiKey", "")))
      })
  }

  private def authorizationForEnvironment(environmentName: EnvironmentName): String = {
    val clientId = servicesConfig.getConfString(s"apim-$environmentName.clientId", "")
    val secret = servicesConfig.getConfString(s"apim-$environmentName.secret", "")

    val encoded = Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
    s"Basic $encoded"
  }

}
