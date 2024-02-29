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
import uk.gov.hmrc.apihubapplications.models.simpleapideployment.{GenerateMetadata, GenerateResponse, ValidationFailuresResponse}
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

  override def validate(oas: String)(implicit hc: HeaderCarrier): Future[Either[SimpleApiDeploymentException, Unit]] = {
    httpClient.post(url"$serviceUrl/v1/simple-api-deployment/validate")
      .setHeader("Authorization" -> authorizationToken)
      .setHeader("Content-Type" -> "application/yaml")
      .withBody(oas)
      .execute[HttpResponse]
      .map (
        response =>
          if (is2xx(response.status)) {
            Right(())
          }
          else if (response.status.intValue == BAD_REQUEST) {
            Left(handleBadRequest(response))
          }
          else {
            Left(raiseSimpleApiDeploymentException.unexpectedResponse(response.status.intValue))
          }
      )
  }

  override def generate(metadata: GenerateMetadata, oas: String)(implicit hc: HeaderCarrier): Future[Either[SimpleApiDeploymentException, GenerateResponse]] = {
    httpClient.post(url"$serviceUrl/v1/simple-api-deployment/generate")
      .setHeader("Authorization" -> authorizationToken)
      .setHeader("Content-Type" -> "multipart/form-data")
      .withBody(
        Source(
          Seq(
            DataPart("metadata", Json.toJson(metadata).toString()),
            DataPart("openapi", oas)
          )
        )
      )
      .execute[HttpResponse]
      .map (
        response =>
          if (is2xx(response.status)) {
            response.json.validate[GenerateResponse].fold(
              (errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]) => Left(raiseSimpleApiDeploymentException.invalidResponse(errors)),
              generateResponse => Right(generateResponse)
            )
          }
          else if (response.status.intValue == BAD_REQUEST) {
            Left(handleBadRequest(response))
          }
          else {
            Left(raiseSimpleApiDeploymentException.unexpectedResponse(response.status.intValue))
          }
      )
  }

  private def handleBadRequest(response: HttpResponse): SimpleApiDeploymentException = {
    response.json.validate[ValidationFailuresResponse].fold(
      _ => raiseSimpleApiDeploymentException.unexpectedResponse(BAD_REQUEST),
      validationFailureResponse => raiseSimpleApiDeploymentException.invalidOasDocument(validationFailureResponse)
    )
  }

}
