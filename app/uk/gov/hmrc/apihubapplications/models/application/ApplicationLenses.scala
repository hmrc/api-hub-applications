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
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import java.time.{Clock, LocalDateTime}

object ApplicationLenses {

  val applicationEnvironments: Lens[Application, Environments] =
    Lens[Application, Environments](
      get = _.environments,
      set = (application, environments) => application.copy(environments = environments)
    )

  val environmentScopes: Lens[Environment, Seq[Scope]] =
    Lens[Environment, Seq[Scope]](
      get = _.scopes,
      set = (environment, scopes) => environment.copy(scopes = scopes)
    )

  val environmentCredentials: Lens[Environment, Seq[Credential]] =
    Lens[Environment, Seq[Credential]](
      get = _.credentials,
      set = (environment, credentials) => environment.copy(credentials = credentials)
    )

  val environmentPrimary: Lens[Environments, Environment] =
    Lens[Environments, Environment](
      get = _.primary,
      set = (environments, primary) => environments.copy(primary = primary)
    )

  val applicationPrimary: Lens[Application, Environment] =
    Lens.compose(applicationEnvironments, environmentPrimary)

  val applicationPrimaryScopes: Lens[Application, Seq[Scope]] =
    Lens.compose(applicationPrimary, environmentScopes)

  val applicationPrimaryCredentials: Lens[Application, Seq[Credential]] =
    Lens.compose(applicationPrimary, environmentCredentials)

  val environmentSecondary: Lens[Environments, Environment] =
    Lens[Environments, Environment](
      get = _.secondary,
      set = (environments, secondary) => environments.copy(secondary = secondary)
    )

  val applicationSecondary: Lens[Application, Environment] =
    Lens.compose(applicationEnvironments, environmentSecondary)

  val applicationSecondaryScopes: Lens[Application, Seq[Scope]] =
    Lens.compose(applicationSecondary, environmentScopes)

  val applicationSecondaryCredentials: Lens[Application, Seq[Credential]] =
    Lens.compose(applicationSecondary, environmentCredentials)

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

    def addScopes(environment: EnvironmentName, scopes: Seq[String]): Application =
      environment match {
        case Primary => setPrimaryScopes((getPrimaryScopes ++ scopes.map(Scope(_))).distinct)
        case Secondary => setSecondaryScopes((getSecondaryScopes ++ scopes.map(Scope(_))).distinct)
      }

    def addScopes(hipEnvironment: HipEnvironment, scopes: Seq[String]): Application =
      addScopes(hipEnvironment.environmentName, scopes)

    def getPrimaryScopes: Seq[Scope] =
      applicationPrimaryScopes.get(application)

    def hasPrimaryScope(scopeName: String): Boolean =
      application.getPrimaryScopes.exists(_.name.equals(scopeName))

    def setPrimaryScopes(scopes: Seq[Scope]): Application =
      applicationPrimaryScopes.set(application, scopes)

    def addPrimaryScope(scope: Scope): Application = {
      if (!application.hasPrimaryScope(scope.name)) {
        applicationPrimaryScopes.set(
          application,
          applicationPrimaryScopes.get(application) :+ scope
        )
      }
      else {
        application
      }
    }

    def removePrimaryScope(scopeName: String): Application =
      applicationPrimaryScopes.set(
        application,
        applicationPrimaryScopes.get(application).filterNot(_.name.equals(scopeName))
      )

    def getPrimaryScopeNames: Set[String] =
      applicationPrimaryScopes
        .get(application)
        .map(_.name)
        .toSet

    def getPrimaryMasterCredential: Option[Credential] =
      applicationPrimaryCredentials.get(application)
        .sortWith((a, b) => a.created.isAfter(b.created))
        .headOption

    def getPrimaryCredentials: Seq[Credential] =
      applicationPrimaryCredentials.get(application)

    def setPrimaryCredentials(credentials: Seq[Credential]): Application =
      applicationPrimaryCredentials.set(application, credentials)

    def addPrimaryCredential(credential: Credential): Application =
      applicationPrimaryCredentials.set(
        application,
        applicationPrimaryCredentials.get(application) :+ credential
      )

    def removePrimaryCredential(clientId: String): Application =
      applicationPrimaryCredentials.set(
        application,
        application.getPrimaryCredentials.filterNot(_.clientId == clientId)
      )

    def replacePrimaryCredential(credential: Credential): Application = {
      val index = application.getPrimaryCredentials.indexWhere(_.clientId == credential.clientId)
      if (index < 0 ) {
        throw new IllegalArgumentException(
          s"Application with Id ${application.id.getOrElse("<none>")} does not have a credential with Client Id ${credential.clientId}"
        )
      }

      application.setPrimaryCredentials(
        application.getPrimaryCredentials.updated(index, credential)
      )
    }

    def getSecondaryScopes: Seq[Scope] =
      applicationSecondaryScopes.get(application)

    def hasSecondaryScope(scopeName: String): Boolean =
      application.getSecondaryScopes.exists(_.name.equals(scopeName))

    def setSecondaryScopes(scopes: Seq[Scope]): Application =
      applicationSecondaryScopes.set(application, scopes)

    def addSecondaryScope(scope: Scope): Application = {
      if (!application.hasSecondaryScope(scope.name)) {
        applicationSecondaryScopes.set(
          application,
          applicationSecondaryScopes.get(application) :+ scope
        )
      }
      else {
        application
      }
    }

    def removeSecondaryScope(scopeName: String): Application =
      applicationSecondaryScopes.set(
        application,
        applicationSecondaryScopes.get(application).filterNot(_.name.equals(scopeName))
      )

    def getSecondaryScopeNames: Set[String] =
      applicationSecondaryScopes
        .get(application)
        .map(_.name)
        .toSet

    def getSecondaryMasterCredential: Option[Credential] =
      applicationSecondaryCredentials.get(application)
        .sortWith((a, b) => a.created.isAfter(b.created))
        .headOption

    def getSecondaryCredentials: Seq[Credential] =
      applicationSecondaryCredentials.get(application)

    def setSecondaryCredentials(credentials: Seq[Credential]): Application =
      applicationSecondaryCredentials.set(application, credentials)

    def addSecondaryCredential(credential: Credential): Application =
      applicationSecondaryCredentials.set(
        application,
        applicationSecondaryCredentials.get(application) :+ credential
      )

    def removeSecondaryCredential(clientId: String): Application =
      applicationSecondaryCredentials.set(
        application,
        application.getSecondaryCredentials.filterNot(_.clientId == clientId)
      )

    def updateSecondaryCredential(clientId: String, secret: String): Application = {
      if (!application.getSecondaryCredentials.exists(_.clientId == clientId)) {
        throw new IllegalArgumentException(
          s"Application with Id ${application.id.getOrElse("<none>")} does not have a credential with Client Id $clientId"
        )
      }

      application.setSecondaryCredentials(
          application.getSecondaryCredentials.map {
            case credential @ Credential(id, _, _, _) if id == clientId =>
              credential.copy(
                clientSecret = Some(secret),
                secretFragment = Some(secret.takeRight(4))
              )
            case credential => credential
          }
        )
    }

    def replaceSecondaryCredential(credential: Credential): Application = {
      val index = application.getSecondaryCredentials.indexWhere(_.clientId == credential.clientId)
      if (index < 0 ) {
        throw new IllegalArgumentException(
          s"Application with Id ${application.id.getOrElse("<none>")} does not have a credential with Client Id ${credential.clientId}"
        )
      }

      application.setSecondaryCredentials(
        application.getSecondaryCredentials.updated(index, credential)
      )
    }

    def getMasterCredential(environmentName: EnvironmentName): Option[Credential] = {
      environmentName match {
        case Primary => getPrimaryMasterCredential
        case Secondary => getSecondaryMasterCredential
      }
    }

    def getMasterCredential(hipEnvironment: HipEnvironment): Option[Credential] =
      getMasterCredential(hipEnvironment.environmentName)

    def getCredentials(environmentName: EnvironmentName): Seq[Credential] = {
      environmentName match {
        case Primary => application.getPrimaryCredentials
        case Secondary => application.getSecondaryCredentials
      }
    }

    def getCredentials(hipEnvironment: HipEnvironment): Seq[Credential] =
      getCredentials(hipEnvironment.environmentName)

    def getScopes(environmentName: EnvironmentName): Seq[Scope] = {
      environmentName match {
        case Primary => getPrimaryScopes
        case Secondary => getSecondaryScopes
      }
    }

    def getScopes(hipEnvironment: HipEnvironment): Seq[Scope] =
      getScopes(hipEnvironment.environmentName)

    def addCredential(credential: Credential, environmentName: EnvironmentName): Application = {
      environmentName match {
        case Primary => application.addPrimaryCredential(credential)
        case Secondary => application.addSecondaryCredential(credential)
      }
    }

    def addCredential(credential: Credential, hipEnvironment: HipEnvironment): Application =
      addCredential(credential, hipEnvironment.environmentName)

    def replaceCredential(credential: Credential, environmentName: EnvironmentName): Application = {
      environmentName match {
        case Primary => application.replacePrimaryCredential(credential)
        case Secondary => application.replaceSecondaryCredential(credential)
      }
    }

    def replaceCredential(credential: Credential, hipEnvironment: HipEnvironment): Application =
      replaceCredential(credential, hipEnvironment.environmentName)

    def removeCredential(clientId: String, environmentName: EnvironmentName): Application = {
      environmentName match {
        case Primary => application.removePrimaryCredential(clientId)
        case Secondary => application.removeSecondaryCredential(clientId)
      }
    }

    def removeCredential(clientId: String, hipEnvironment: HipEnvironment): Application =
      removeCredential(clientId, hipEnvironment.environmentName)

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

    def makePublic(): Application = {
      application.setPrimaryCredentials(
        application.getPrimaryCredentials.filter(!_.isHidden)
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
