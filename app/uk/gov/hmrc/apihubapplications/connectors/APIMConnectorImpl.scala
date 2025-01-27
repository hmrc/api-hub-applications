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
import org.apache.pekko.stream.scaladsl.Source
import play.api.libs.ws.DefaultBodyWritables.writeableOf_String
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.libs.ws.WSBodyWritables.bodyWritableOf_Multipart
import play.api.Logging
import play.api.http.Status.{BAD_REQUEST, NOT_FOUND}
import play.api.libs.json.{JsPath, Json, JsonValidationError}
import play.api.mvc.MultipartFormData.DataPart
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}
import uk.gov.hmrc.apihubapplications.models.api.EgressGateway
import uk.gov.hmrc.apihubapplications.models.apim.{ApiDeployment, CreateMetadata, DeploymentDetails, DeploymentFrom, DeploymentResponse, DeploymentsRequest, DeploymentsResponse, DetailsResponse, FailuresResponse, InvalidOasResponse, RedeploymentRequest, SuccessfulDeploymentResponse, SuccessfulDeploymentsResponse, SuccessfulValidateResponse, UpdateMetadata, ValidateResponse}
import uk.gov.hmrc.apihubapplications.models.exception.{ApimException, ExceptionRaising}
import uk.gov.hmrc.http.{HeaderCarrier, HttpErrorFunctions, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class APIMConnectorImpl @Inject()(
  httpClient: HttpClientV2,
  hipEnvironments: HipEnvironments
)(implicit ec: ExecutionContext) extends APIMConnector
  with Logging
  with ExceptionRaising
  with HttpErrorFunctions
  with ProxySupport
  with CorrelationIdSupport {

  import APIMConnectorImpl.*
  import ProxySupport.*
  import CorrelationIdSupport.*

  override def validateInPrimary(oas: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, ValidateResponse]] = {
    val hipEnvironment = hipEnvironments.productionEnvironment

    httpClient.post(url"${hipEnvironment.apimUrl}/v1/simple-api-deployment/validate")
      .setHeader("Authorization" -> authorizationForEnvironment(hipEnvironment))
      .setHeader("Content-Type" -> "application/yaml")
      .withCorrelationId()
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
    val hipEnvironment = hipEnvironments.deploymentEnvironment

    val metadata = Json.toJson(CreateMetadata(request))
    val context = Seq("metadata" -> Json.prettyPrint(metadata))
      .withCorrelationId()

    httpClient.post(url"${hipEnvironment.apimUrl}/v1/simple-api-deployment/deployments")
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .setHeader("Accept" -> "application/json")
      .withCorrelationId()
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
    val hipEnvironment = hipEnvironments.deploymentEnvironment

    val metadata = Json.toJson(UpdateMetadata(request))
    val context = Seq("publisherReference" -> publisherReference, "metadata" -> Json.prettyPrint(metadata))
      .withCorrelationId()

    httpClient.put(url"${hipEnvironment.apimUrl}/v1/simple-api-deployment/deployments/$publisherReference")
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .setHeader("Accept" -> "application/json")
      .withCorrelationId()
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

  override def getDeployment(publisherReference: String, hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Option[DeploymentResponse]]] = {
    val url = url"${hipEnvironment.apimUrl}/v1/oas-deployments/$publisherReference"
    val context = Seq("publisherReference" -> publisherReference, "hipEnvironment" -> hipEnvironment.id)
      .withCorrelationId()

    httpClient.get(url)
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .execute[Either[UpstreamErrorResponse, SuccessfulDeploymentResponse]]
      .map {
        case Right(deployment) => Right(Some(deployment))
        case Left(e) if e.statusCode == 404 => Right(None)
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode, context))
      }
      . recover {
        case NonFatal(e) => Left(raiseApimException.error(e, context))
      }
  }

  override def getDeploymentDetails(publisherReference: String)(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentDetails]] = {
    val hipEnvironment = hipEnvironments.deploymentEnvironment

    val context = Seq("publisherReference" -> publisherReference)
      .withCorrelationId()

    httpClient.get(url"${hipEnvironment.apimUrl}/v1/simple-api-deployment/deployments/$publisherReference")
      .setHeader(headersForEnvironment(hipEnvironment)*)
      .setHeader("Accept" -> "application/json")
      .withCorrelationId()
      .withProxyIfRequired(hipEnvironment)
      .execute[Either[UpstreamErrorResponse, DetailsResponse]]
      .map {
        case Right(detailsResponse) => Right(detailsResponse.toDeploymentDetails)
        case Left(e) if e.statusCode == 404 => Left(raiseApimException.serviceNotFound(publisherReference))
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode, context))
      }
  }

  override def promoteAPI(
                           publisherReference: String,
                           environmentFrom: HipEnvironment,
                           environmentTo: HipEnvironment,
                           egress: String
                         )(implicit hc: HeaderCarrier): Future[Either[ApimException, DeploymentsResponse]] = {
    val context = Seq(
      "publisherReference" -> publisherReference,
      "environmentFrom" -> environmentFrom.id,
      "environmentTo" -> environmentTo.id,
      "egress" -> egress,
    )
      .withCorrelationId()
    val deploymentFrom = DeploymentFrom(
      env = environmentFrom.apimEnvironmentName,
      egress = egress,
      serviceId = publisherReference
    )

    httpClient.put(url"${environmentTo.apimUrl}/v1/simple-api-deployment/deployment-from")
      .setHeader("Authorization" -> authorizationForEnvironment(environmentTo))
      .setHeader("Content-Type" -> "application/json")
      .setHeader("Accept" -> "application/json")
      .withCorrelationId()
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

  override def getDeployments(hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[ApiDeployment]]] = {
    val context = Seq("hipEnvironment" -> hipEnvironment.id)
      .withCorrelationId()

    httpClient.get(url"${hipEnvironment.apimUrl}/v1/oas-deployments")
      .setHeader(headersForEnvironment(hipEnvironment) *)
      .withProxyIfRequired(hipEnvironment)
      .withCorrelationId()
      .setHeader("Accept" -> "application/json")
      .execute[Either[UpstreamErrorResponse, Seq[ApiDeployment]]]
      .map {
        case Right(apiDeployments) => Right(apiDeployments)
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode, context))
      }
  }

  override def listEgressGateways(hipEnvironment: HipEnvironment)(implicit hc: HeaderCarrier): Future[Either[ApimException, Seq[EgressGateway]]] = {
    val context = Seq.empty.withCorrelationId()

    httpClient.get(url"${hipEnvironment.apimUrl}/v1/simple-api-deployment/egress-gateways")
      .setHeader(headersForEnvironment(hipEnvironment) *)
      .setHeader("Accept" -> "application/json")
      .withCorrelationId()
      .withProxyIfRequired(hipEnvironment)
      .execute[Either[UpstreamErrorResponse, Seq[EgressGateway]]]
      .map {
        case Right(egressGateways) => Right(egressGateways)
        case Left(e) => Left(raiseApimException.unexpectedResponse(e.statusCode, context))
      }
  }

}

object APIMConnectorImpl extends Logging with ExceptionRaising {

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

}
