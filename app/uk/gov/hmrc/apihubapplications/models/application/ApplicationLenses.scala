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

package uk.gov.hmrc.apihubapplications.models.application

import uk.gov.hmrc.apihubapplications.models.Lens
import uk.gov.hmrc.apihubapplications.config.{HipEnvironment, HipEnvironments}

import java.time.{Clock, LocalDateTime}

object ApplicationLenses {

  val applicationCredentials: Lens[Application, Set[Credential]] =
    Lens[Application, Set[Credential]](
      get = _.credentials,
      set = (application, credentials) => application.copy(credentials = credentials)
    )

  val applicationTeamMembers: Lens[Application, Seq[TeamMember]] =
    Lens[Application, Seq[TeamMember]](
      get = _.teamMembers,
      set = (application, teamMembers) => application.copy(teamMembers = teamMembers)
    )

  val applicationIssues: Lens[Application, Seq[String]] =
    Lens[Application, Seq[String]](
      get = _.issues,
      set = (application, issues) => application.copy(issues = issues)
    )

  val applicationApis: Lens[Application, Seq[Api]] =
    Lens[Application, Seq[Api]](
      get = _.apis,
      set = (application, apis) => application.copy(apis = apis)
    )

  val applicationDeleted: Lens[Application, Option[Deleted]] =
    Lens[Application, Option[Deleted]](
      get = _.deleted,
      set = (application, deleted) => application.copy(deleted = deleted)
    )

  implicit class ApplicationLensOps(application: Application) {

    def safeId: String = {
      application.id match {
        case Some(id) => id
        case _ => throw new IllegalStateException(s"Application does not have an Id when expected to do so: name=${application.name}")
      }
    }

    def setCredentials(hipEnvironment: HipEnvironment, credentials: Seq[Credential]): Application =
      applicationCredentials.set(
        application,
        applicationCredentials
          .get(application)
          .filterNot(_.environmentId == hipEnvironment.id)
          ++ credentials
      )

    def updateCredential(hipEnvironment: HipEnvironment, clientId: String, secret: String): Application = {
      application.getCredentials(hipEnvironment)
        .find(_.clientId == clientId)
        .map(credential =>
          application.replaceCredential(
            hipEnvironment,
            credential.copy(
              clientSecret = Some(secret),
              secretFragment = Some(secret.takeRight(4))
            )
          )
        )
        .getOrElse(
          throw new IllegalArgumentException(
            s"Application with Id ${application.id.getOrElse("<none>")} does not have a credential with Client Id $clientId"
          )
        )
    }

    def getMasterCredential(hipEnvironment: HipEnvironment): Option[Credential] =
      application.getCredentials(hipEnvironment)
        .sortBy(_.created)
        .reverse
        .headOption

    private implicit val credentialOrdering: Ordering[Credential] =
      Ordering.by[Credential, (LocalDateTime, String)](credential => ((credential.created, credential.clientId)))

    def getCredentials(hipEnvironment: HipEnvironment): Seq[Credential] =
      applicationCredentials
        .get(application)
        .filter(_.environmentId == hipEnvironment.id)
        .toSeq
        .sorted

    def getAllCredentials(): Seq[Credential] = {
      applicationCredentials.get(application).toSeq.sortBy(_.created)
    }

    def addCredential(hipEnvironment: HipEnvironment, credential: Credential): Application = {
      setCredentials(
        hipEnvironment,
        application.getCredentials(hipEnvironment) :+ credential
      )
    }

    def replaceCredential(hipEnvironment: HipEnvironment, credential: Credential): Application = {
      val index = getCredentials(hipEnvironment).indexWhere(_.clientId == credential.clientId)
      if (index < 0) {
        throw new IllegalArgumentException(
          s"Application with Id ${application.id.getOrElse("<none>")} does not have a credential with Client Id ${credential.clientId}"
        )
      }

      setCredentials(
        hipEnvironment,
        getCredentials(hipEnvironment).updated(index, credential)
      )
    }

    def removeCredential(hipEnvironment: HipEnvironment, clientId: String): Application = {
      setCredentials(
        hipEnvironment,
        application.getCredentials(hipEnvironment).filterNot(_.clientId == clientId)
      )
    }

    def setTeamId(teamId: String): Application = {
      application.copy(teamId = Some(teamId))
    }

    def unsetTeamId(): Application = {
      application.copy(teamId = None)
    }

    def setTeamName(teamName: String): Application = {
      application.copy(teamName = Some(teamName))
    }

    def unsetTeamName(): Application = {
      application.copy(teamName = None)
    }

    def hasTeamMember(email: String): Boolean =
      applicationTeamMembers.get(application)
        .exists(teamMember => teamMember.email.equalsIgnoreCase(email))

    def hasTeamMember(teamMember: TeamMember): Boolean =
      hasTeamMember(teamMember.email)

    def addTeamMember(teamMember: TeamMember): Application =
      applicationTeamMembers.set(
        application,
        applicationTeamMembers.get(application) :+ teamMember
      )

    def addTeamMember(email: String): Application =
      addTeamMember(TeamMember(email))

    def assertTeamMember(email: String): Application = {
      if (application.hasTeamMember(email)) {
        application
      }
      else {
        application.addTeamMember(email)
      }
    }

    def setTeamMembers(teamMembers: Seq[TeamMember]): Application = {
      applicationTeamMembers.set(application, teamMembers)
    }

    def isTeamMigrated: Boolean = {
      application.teamId.isDefined
    }

    def setIssues(issues: Seq[String]): Application = {
      applicationIssues.set(application, issues)
    }

    def addIssue(issue: String): Application = {
      applicationIssues.set(
        application,
        applicationIssues.get(application) :+ issue
      )
    }

    def makePublic(hipEnvironments: HipEnvironments): Application = {
      application.setCredentials(
        hipEnvironments.production,
        application.getCredentials(hipEnvironments.production).filter(!_.isHidden)
      )
    }

    def setApis(apis: Seq[Api]): Application = {
      applicationApis.set(
        application,
        apis
      )
    }

    def addApi(api: Api): Application = {
      application.setApis(application.apis :+ api)
    }

    def removeApi(id: String): Application = {
      application.setApis(application.apis.filterNot(_.id == id))
    }

    def replaceApi(api: Api): Application = {
      application.apis.indexWhere(_.id == api.id) match {
        case -1 => addApi(api)
        case index => application.setApis(application.apis.updated(index, api))
      }
    }

    def hasApi(id: String): Boolean = {
      application.apis.exists(_.id.equals(id))
    }

    def updated(clock: Clock): Application = {
      application.copy(lastUpdated = LocalDateTime.now(clock))
    }

    def delete(deleted: Deleted): Application = {
      applicationDeleted.set(application, Some(deleted))
    }

    def isDeleted: Boolean = {
      application.deleted.isDefined
    }

    def removeTeam(clock: Clock): Application = {
      unsetTeamName().
        setTeamMembers(Seq.empty).
        unsetTeamName()
        .unsetTeamId()
        .updated(clock)
    }
  }

}
