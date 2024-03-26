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
import uk.gov.hmrc.apihubapplications.models.simpleapideployment._
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpResponse, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class APIMConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2
)(implicit ec: ExecutionContext) extends APIMConnector with Logging with ExceptionRaising with HttpErrorFunctions {

  override def validatePrimary(oas: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, ValidateResponse]] = {
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

  override def generateSecondary(request: GenerateRequest)(implicit hc: HeaderCarrier): Future[Either[ApimException, GenerateResponse]] = {
    httpClient.post(url"${baseUrlForEnvironment(Secondary)}/v1/simple-api-deployment/generate")
      .setHeader("Authorization" -> authorizationForEnvironment(Secondary))
      .setHeader("Accept" -> "application/json")
      .withBody(
        Source(
          Seq(
            DataPart("metadata", Json.toJson(GenerateMetadata(request)).toString()),
            DataPart("openapi", request.oas)
          )
        )
      )
      .withProxy
      .execute[HttpResponse]
      .map (
        response =>
          if (is2xx(response.status)) {
            response.json.validate[SuccessfulGenerateResponse].fold(
              (errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]) => Left(raiseApimException.invalidResponse(errors)),
              generateResponse => Right(generateResponse)
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

  override def status(publisherReference: String, environment: EnvironmentName)(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]] = {
    httpClient.get(url"${baseUrlForEnvironment(Secondary)}/v1/oas-discovery/oas/${publisherReference}")
      .setHeader(headersForEnvironment(environment): _*)
      .withProxy
      .execute[HttpResponse]
      .map(
        response =>
          if (is2xx(response.status)) {
            response.json.validate[DeploymentResponse].fold(
              (errors: collection.Seq[(JsPath, collection.Seq[JsonValidationError])]) => Left(raiseApimException.invalidResponse(errors)),
              generateResponse => Right(Some(generateResponse))
            )
          }
          else if (response.status.intValue == NOT_FOUND) {
            Right(Option.empty)
          }
          else {
            Left(raiseApimException.unexpectedResponse(response.status.intValue))
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

    val endcoded = Base64.getEncoder.encodeToString(s"$clientId:$secret".getBytes("UTF-8"))
    s"Basic $endcoded"
  }

}
