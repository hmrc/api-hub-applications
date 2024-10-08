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

import uk.gov.hmrc.apihubapplications.models.accessRequest.{AccessRequest, AccessRequestRequest}
import uk.gov.hmrc.apihubapplications.models.api.ApiDetail
import uk.gov.hmrc.apihubapplications.models.application.{Application, TeamMember}
import uk.gov.hmrc.apihubapplications.models.exception.EmailException
import uk.gov.hmrc.apihubapplications.models.team.Team
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future

trait EmailConnector {

  def sendAddTeamMemberEmail(application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApplicationDeletedEmailToCurrentUser(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApplicationDeletedEmailToTeam(application: Application, currentUser: String)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApplicationCreatedEmailToCreator(application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendAccessApprovedEmailToTeam(application: Application, accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendAccessRejectedEmailToTeam(application: Application, accessRequest: AccessRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendAccessRequestSubmittedEmailToRequester(application: Application, accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendNewAccessRequestEmailToApprovers(application: Application, accessRequest: AccessRequestRequest)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendTeamMemberAddedEmailToTeamMembers(teamMembers: Seq[TeamMember], team: Team)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApiOwnershipChangedEmailToOldTeamMembers(currentTeam: Team, newTeam: Team, api: ApiDetail)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApiOwnershipChangedEmailToNewTeamMembers(team: Team, api: ApiDetail)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendRemoveTeamMemberFromTeamEmail(email: String, team: Team)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApplicationOwnershipChangedEmailToOldTeamMembers(currentTeam: Team, newTeam: Team, application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

  def sendApplicationOwnershipChangedEmailToNewTeamMembers(team: Team, application: Application)(implicit hc: HeaderCarrier): Future[Either[EmailException, Unit]]

}
