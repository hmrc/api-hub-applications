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

package uk.gov.hmrc.apihubapplications.testhelpers

import uk.gov.hmrc.apihubapplications.connectors.EmailConnector
import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestRequest}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetail
import uk.gov.hmrc.apihubapplications.models.application.{Application, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.EmailException
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

class FakeEmailConnector extends EmailConnector {

  override def sendAddTeamMemberEmail(application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = {
    Future.successful(Right(()))
  }

  override def sendApplicationDeletedEmailToCurrentUser(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendApplicationCreatedEmailToCreator(application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendApplicationDeletedEmailToTeam(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendAccessApprovedEmailToTeam(application: Application, accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendAccessRejectedEmailToTeam(application: Application, accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendAccessRequestSubmittedEmailToRequester(application: Application, accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendNewAccessRequestEmailToApprovers(application: Application, accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendTeamMemberAddedEmailToTeamMembers(teamMembers: Seq[TeamMember], team: Team)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendApiOwnershipChangedEmailToOldTeamMembers(currentTeam: Team, newTeam: Team, api: ApiDetail)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendApiOwnershipChangedEmailToNewTeamMembers(team: Team, api: ApiDetail)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] = Future.successful(Right(()))

  override def sendRemoveTeamMemberFromTeamEmail(email: String, team: Team)(implicit hc: HeaderCarrier): Future[Either[EmailException,Unit]] = Future.successful(Right(()))

  override def sendApplicationOwnershipChangedEmailToOldTeamMembers(currentTeam: Team, newTeam: Team, application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] =
    Future.successful(Right(()))

  override def sendApplicationOwnershipChangedEmailToNewTeamMembers(team: Team, application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]] =
    Future.successful(Right(()))
}
