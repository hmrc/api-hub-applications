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
import uk.gov.hmrc.apihubapplications.models.exception.{ExceptionRaising, SimpleApiDeploymentException}
import uk.gov.hmrc.apihubapplications.models.simpleapideployment._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class SimpleApiDeploymentConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2
)(implicit ec: ExecutionContext) extends SimpleApiDeploymentConnector with Logging with ExceptionRaising with HttpErrorFunctions {

  private lazy val authorizationToken = {
    val clientId = servicesConfig.getConfString("simple-api-deployment.clientId", "")
    val secret = servicesConfig.getConfString("simple-api-deployment.secret", "")

    val endcoded = Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
    s"Basic $endcoded"
  }

  private lazy val serviceUrl = {
    val baseUrl = servicesConfig.baseUrl("simple-api-deployment")
    val path = servicesConfig.getConfString("simple-api-deployment.path", "")

    if (path.isEmpty) {
      baseUrl
    }
    else {
      s"$baseUrl/$path"
    }
  }

  override def validate(oas: String)(implicit hc: HeaderCarrier): Future[Either[SimpleApiDeploymentException, ValidateResponse]] = {
    httpClient.post(url"$serviceUrl/v1/simple-api-deployment/validate")
      .setHeader("Authorization" -> authorizationToken)
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
              .getOrElse(Left(raiseSimpleApiDeploymentException.unexpectedResponse(BAD_REQUEST)))
          }
          else {
            Left(raiseSimpleApiDeploymentException.unexpectedResponse(response.status.intValue))
          }
      )
  }

  override def deployments(request: DeploymentsRequest)(implicit hc: HeaderCarrier): Future[Either[SimpleApiDeploymentException, DeploymentsResponse]] = {
    httpClient.post(url"$serviceUrl/v1/simple-api-deployment/deployments")
      .setHeader("Authorization" -> authorizationToken)
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
              (errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]) => Left(raiseSimpleApiDeploymentException.invalidResponse(errors)),
              deploymentsResponse => Right(deploymentsResponse)
            )
          }
          else if (response.status.intValue == BAD_REQUEST) {
            handleBadRequest(response)
              .map(Right(_))
              .getOrElse(Left(raiseSimpleApiDeploymentException.unexpectedResponse(BAD_REQUEST)))
          }
          else {
            Left(raiseSimpleApiDeploymentException.unexpectedResponse(response.status.intValue))
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

}
