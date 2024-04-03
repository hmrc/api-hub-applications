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
import play.api.mvc.MultipartFormData.DataPart
import uk.gov.hmrc.apihubapplications.models.application.{EnvironmentName, Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.apim._
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
      .map (
        response =>
          if (is2xx(response.status)) {
            Right(SuccessfulValidateResponse)
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
              .map(Right(_))
              .getOrElse(Left(raiseApimException.unexpectedResponse(BAD_REQUEST)))
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue))
          }
      )
  }

  override def deployToSecondary(request: DeploymentsRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    httpClient.post(url"${baseUrlForEnvironment(Secondary)}/v1/simple-api-deployment/deployments")
      .setHeader("Authorization" -> authorizationForEnvironment(Secondary))
      .setHeader("Accept" -> "application/json")
      .withBody(
        Source(
          Seq(
            DataPart("metadata", Json.toJson(DeploymentsMetadata(request)).toString()),
            DataPart("openapi", request.oas)
          )
        )
      )
      .withProxy
      .execute[HttpResponse]
      .map (
        response =>
          if (is2xx(response.status)) {
            response.json.validate[SuccessfulDeploymentsResponse].fold(
              (errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]) => Left(raiseApimException.invalidResponse(errors)),
              deploymentsResponse => Right(deploymentsResponse)
            )
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
              .map(Right(_))
              .getOrElse(Left(raiseApimException.unexpectedResponse(BAD_REQUEST)))
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue))
          }
      )
  }

  private def handleBadRequest(response: HttpResponse): Option[InvalidOasResponse] = {
    if (response.body.isEmpty) {
      None
    }
    else {
      response.json.validate[Seq[ValidationFailure]].fold(
        _ => {
          logger.warn(s"Unknown response body from Simple OAS Deployment service:${System.lineSeparator()}${response.body}")
          None
        },
        failures =>
          Some(InvalidOasResponse(failures))
      )
    }
  }

  override def getDeployment(publisherReference: String, environment: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]] = {
    val useProxyForSecondary = servicesConfig.getConfBool(s"apim-$environment.useProxy", true)
    httpClient.get(url"${baseUrlForEnvironment(environment)}/v1/oas-deployments/${publisherReference}")
      .setHeader(headersForEnvironment(environment): _*)
      .withProxyIfRequired(environment, useProxyForSecondary)
      .execute[Either[UpstreamErrorResponse, SuccessfulDeploymentResponse]]
      .map {
        case Right(deployment) => Right(Some(deployment))
        case Left(e) if e.statusCode == 404 => Right(None)
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode))
      }
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
