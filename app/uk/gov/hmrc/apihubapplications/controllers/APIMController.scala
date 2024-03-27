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

package uk.gov.hmrc.apihubapplications.controllers

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.connectors.APIMConnector
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.apim.{DeploymentsRequest, InvalidOasResponse, SuccessfulDeploymentsResponse}
import uk.gov.hmrc.apihubapplications.models.application.{Primary, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.ApimException
import uk.gov.hmrc.apihubapplications.models.exception.ApimException.InvalidResponse
import uk.gov.hmrc.apihubapplications.models.requests.DeploymentStatus
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class APIMController @Inject()(identify: IdentifierAction,
                               cc: ControllerComponents,
                               apimConnector: APIMConnector)(implicit ec: ExecutionContext)
  extends BackendController(cc) with Logging {

  def generate: Action[JsValue] = identify.compose(Action(parse.json)).async {
    implicit request =>
      val jsReq = request.body
      jsReq.validate[DeploymentsRequest] match {
        case JsSuccess(deploymentsRequest, _) => apimConnector.deployToSecondary(deploymentsRequest) map {
          case Right(response: InvalidOasResponse) => BadRequest(Json.toJson(response))
          case Right(response: SuccessfulDeploymentsResponse) => Ok(Json.toJson(response))
          case Left(e: ApimException) if e.issue equals InvalidResponse => BadRequest
          case Left(_) => InternalServerError
        }
        case e: JsError =>
          logger.warn(s"Error parsing request body: ${JsError.toJson(e)}")
          Future.successful(BadRequest)
      }
  }

  def getDeploymentStatus(publisherRef: String): Action[AnyContent] = identify.compose(Action).async {
    implicit request =>
      for {
        secondaryStatus <- apimConnector.getDeployment(publisherRef, Secondary)
        primaryStatus <- apimConnector.getDeployment(publisherRef, Primary)
      } yield (primaryStatus, secondaryStatus) match {
        case (Right(maybePrimaryStatus), Right(maybeSecondaryStatus)) => Ok(Json.toJson(DeploymentStatus(maybePrimaryStatus.isDefined, maybeSecondaryStatus.isDefined)))
        case _ => BadGateway
      }
  }
}
