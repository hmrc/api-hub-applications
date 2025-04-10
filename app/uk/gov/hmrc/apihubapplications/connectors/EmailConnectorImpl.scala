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

import com.google.inject.Inject
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestRequest}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetail
import uk.gov.hmrc.apihubapplications.models.application.{Application, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.{EmailException, ExceptionRaising}
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

class EmailConnectorImpl @Inject()(
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2,
  hipEnvironments: HipEnvironments
)(implicit ec: ExecutionContext) extends EmailConnector with Logging with ExceptionRaising {

  private def getAndValidate(configKey: String): String = {
    val templateId = servicesConfig.getConfString(configKey, "")

    if (templateId.isEmpty) {
      raiseEmailException.missingConfig(configKey)
    }

    templateId
  }

  private val url = url"${servicesConfig.baseUrl("email")}/hmrc/email"
  private val addTeamMemberToApplicationTemplateId = getAndValidate("email.addTeamMemberToApplicationTemplateId")
  private val applicationDeletedToUserTemplateId = getAndValidate("email.deleteApplicationEmailToUserTemplateId")
  private val applicationDeletedToTeamTemplateId = getAndValidate("email.deleteApplicationEmailToTeamTemplateId")
  private val applicationCreatedToCreatorTemplateId = getAndValidate("email.applicationCreatedEmailToCreatorTemplateId")
  private val accessApprovedToTeamTemplateId = getAndValidate("email.accessApprovedEmailToTeamTemplateId")
  private val accessRejectedToTeamTemplateId = getAndValidate("email.accessRejectedEmailToTeamTemplateId")
  private val accessRequestSubmittedToToRequesterTemplateId = getAndValidate("email.accessRequestSubmittedEmailToRequesterTemplateId")
  private val newAccessRequestToApproversTemplateId = getAndValidate("email.newAccessRequestEmailToApproversTemplateId")
  private val teamMemberAddedToTeamTemplateId = getAndValidate("email.teamMemberAddedToTeamTemplateId")
  private val apiOwnershipChangedToOldTeamTemplateId = getAndValidate("email.apiOwnershipChangedToOldTeamTemplateId")
  private val apiOwnershipChangedToNewTeamTemplateId = getAndValidate("email.apiOwnershipChangedToNewTeamTemplateId")
  private val removeTeamMemberFromTeamTemplateId = getAndValidate("email.removeTeamMemberFromTeamTemplateId")
  private val applicationOwnershipChangedToOldTeamTemplateId = getAndValidate("email.applicationOwnershipChangedToOldTeamTemplateId")
  private val applicationOwnershipChangedToNewTeamTemplateId = getAndValidate("email.applicationOwnershipChangedToNewTeamTemplateId")

  private def doPost(request: SendEmailRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    httpClient.post(url)
      .withBody(Json.toJson(request))
      .execute[Either[UpstreamErrorResponse, Unit]]
      .map {
        case Right(()) => Right(())
        case Left(e) => Left(raiseEmailException.unexpectedResponse(e))
      }
      .recover {
        case throwable => Left(raiseEmailException.error(throwable))
      }
  }

  def sendAddTeamMemberEmail(application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val to = application
      .teamMembers
      .filterNot(_.email.equals(application.createdBy.email))
      .map(_.email)

    if (to.nonEmpty) {
      val request = SendEmailRequest(
        to,
        addTeamMemberToApplicationTemplateId,
        Map(
          "applicationname" -> application.name,
          "creatorusername" -> application.createdBy.email
        )
      )
      doPost(request)
    }
    else {
      Future.successful(Right(()))
    }
  }

  def sendApplicationDeletedEmailToCurrentUser(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {

    val request = SendEmailRequest(
        Seq(currentUser),
        applicationDeletedToUserTemplateId,
        Map(
          "applicationname" -> application.name
        )
      )
      doPost(request)
  }

  def sendApplicationCreatedEmailToCreator(application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {

    val request = SendEmailRequest(
      Seq(application.createdBy.email),
      applicationCreatedToCreatorTemplateId,
      Map(
        "applicationname" -> application.name
      )
    )
    doPost(request)
  }

  def sendApplicationDeletedEmailToTeam(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val to = application
      .teamMembers
      .filterNot(_.email.equals(currentUser))
      .map(_.email)

    if (to.nonEmpty) {
      val request = SendEmailRequest(
        to,
        applicationDeletedToTeamTemplateId,
        Map(
          "applicationname" -> application.name
        )
      )
      doPost(request)
    }
    else {
      Future.successful(Right(()))
    }
  }

  private def sendAccessEmailToTeam(application: Application, accessRequest: AccessRequest, templateId: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val to = application
      .teamMembers
      .map(_.email)

    if (to.nonEmpty) {
      val request = SendEmailRequest(
        to,
        templateId,
        Map(
          "applicationname" -> application.name,
          "apispecificationname" -> accessRequest.apiName,
          "environmentname" -> hipEnvironments.forId(accessRequest.environmentId).name
        )
      )
      doPost(request)
    }
    else {
      Future.successful(Right(()))
    }
  }

  override def sendAccessApprovedEmailToTeam(application: Application, accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    sendAccessEmailToTeam(application, accessRequest, accessApprovedToTeamTemplateId)
  }

  override def sendAccessRejectedEmailToTeam(application: Application, accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    sendAccessEmailToTeam(application, accessRequest, accessRejectedToTeamTemplateId)
  }

  override def sendAccessRequestSubmittedEmailToRequester(application: Application, accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      Seq(accessRequest.requestedBy),
      accessRequestSubmittedToToRequesterTemplateId,
      Map(
        "applicationname" -> application.name,
        "apispecificationname" -> accessRequest.apis.map(api => api.apiName).mkString(" and "),
        "environmentname" -> hipEnvironments.forId(accessRequest.environmentId).name
      )
    )
    doPost(request)
  }

  override def sendNewAccessRequestEmailToApprovers(application: Application, accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      servicesConfig.getString("microservice.services.email.approversTeamEmails").split(",").toIndexedSeq,
      newAccessRequestToApproversTemplateId,
      Map(
        "applicationname" -> application.name,
        "apispecificationname" -> accessRequest.apis.map(api => api.apiName).mkString(" and "),
        "environmentname" -> hipEnvironments.forId(accessRequest.environmentId).name
      )
    )

    doPost(request)
  }

  override def sendTeamMemberAddedEmailToTeamMembers(teamMembers: Seq[TeamMember], team: Team)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      teamMembers.map(_.email),
      teamMemberAddedToTeamTemplateId,
      Map("teamname" -> team.name)
    )
    doPost(request)
  }


  override def sendApiOwnershipChangedEmailToOldTeamMembers(currentTeam: Team, newTeam: Team, apiDetail: ApiDetail)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      currentTeam.teamMembers.map(_.email),
      apiOwnershipChangedToOldTeamTemplateId,
      Map("teamname" -> currentTeam.name,
        "otherteamname" -> newTeam.name,
        "apispecificationname" -> apiDetail.title)
    )
    doPost(request)
  }

  override def sendApiOwnershipChangedEmailToNewTeamMembers(team: Team, apiDetail: ApiDetail)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      team.teamMembers.map(_.email),
      apiOwnershipChangedToNewTeamTemplateId,
      Map("teamname" -> team.name, "apispecificationname" -> apiDetail.title)
    )
    doPost(request)
  }

  override def sendRemoveTeamMemberFromTeamEmail(email: String, team: Team)(implicit hc: HeaderCarrier): Future[Either[EmailException,Unit]] = {
    val request = SendEmailRequest(
      Seq(email),
      removeTeamMemberFromTeamTemplateId,
      Map("teamname" -> team.name)
    )
    doPost(request)
  }

  override def sendApplicationOwnershipChangedEmailToOldTeamMembers(currentTeam: Team, newTeam: Team, application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      currentTeam.teamMembers.map(_.email),
      applicationOwnershipChangedToOldTeamTemplateId,
      Map(
        "teamname" -> newTeam.name,
        "oldteamname" -> currentTeam.name,
        "applicationname" -> application.name,
      )
    )
    doPost(request)
  }

  override def sendApplicationOwnershipChangedEmailToNewTeamMembers(team: Team, application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    val request = SendEmailRequest(
      team.teamMembers.map(_.email),
      applicationOwnershipChangedToNewTeamTemplateId,
      Map(
        "teamname" -> team.name,
        "applicationname" -> application.name,
      )
    )
    doPost(request)
  }
}
// Elided class from the email api
// See https://github.com/hmrc/email/blob/main/app/uk/gov/hmrc/email/controllers/model/SendEmailRequest.scala
case class SendEmailRequest(
  to: Seq[String],
  templateId: String,
  parameters: Map[String, String]
)

object SendEmailRequest {

  implicit val formatSendEmailRequest: OFormat[SendEmailRequest] = Json.format[SendEmailRequest]

}
