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

package uk.gov.hmrc.apihubapplications.controllers

import com.google.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{JsError, JsSuccess, JsValue, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.apihubapplications.controllers.actions.IdentifierAction
import uk.gov.hmrc.apihubapplications.models.application.Application
import uk.gov.hmrc.apihubapplications.models.event.EntityType
import uk.gov.hmrc.apihubapplications.models.exception.*
import uk.gov.hmrc.apihubapplications.models.requests.TeamMemberRequest
import uk.gov.hmrc.apihubapplications.models.team.{AddEgressesRequest, NewTeam, RenameTeamRequest}
import uk.gov.hmrc.apihubapplications.services.{ApplicationsSearchService, EventsService, TeamsService}
import uk.gov.hmrc.crypto.{ApplicationCrypto, Crypted}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EventsController @Inject()(
  cc: ControllerComponents,
  identify: IdentifierAction,
  eventsService: EventsService,
  crypto: ApplicationCrypto
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  def findByUser(encryptedEmail: String): Action[AnyContent] = identify.async {
    eventsService.findByUser(decrypt(encryptedEmail)).map(
      events => Ok(Json.toJson(events))
    )
  }

  def findById(id: String): Action[AnyContent] = identify.async {
    eventsService.findById(id).map(
      events => Ok(Json.toJson(events))
    )
  }

  def findByEvent(entityType: String, entityId: String): Action[AnyContent] = identify.async {
    val maybeEntityType = EntityType.enumerable.withName(entityType.toUpperCase)

    if (maybeEntityType.isEmpty) {
      Future.successful(BadRequest)
    }

    eventsService.findByEntity(entityId, maybeEntityType.get) flatMap  (events => Future.successful(Ok(Json.toJson(events))))
  }

  private def decrypt(encrypted: String): String = {
    crypto.QueryParameterCrypto.decrypt(Crypted(encrypted)).value
  }

}
