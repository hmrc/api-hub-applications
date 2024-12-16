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

package uk.gov.hmrc.apihubapplications.models.applications

import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.Lens
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.applications.ApplicationLensesSpec.*
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.util.Random

class ApplicationLensesSpec extends LensBehaviours {

  "applicationEnvironments" - {
    "must get the correct Environments" in {
      val expected = randomEnvironments()
      val application = testApplication.copy(environments = expected)

      val actual = applicationEnvironments.get(application)
      actual mustBe expected
    }

    "must set the Environments correctly" in {
      val expected = randomEnvironments()
      val application = applicationEnvironments.set(testApplication, expected)

      application.environments mustBe expected
    }
  }

  "environmentScopes" - {
    "must add scopes correctly with secondary as APPROVED and primary as PENDING" in{
      val app = testApplication
      val primaryScopes = Seq("test-scope-primary-1", "test-scope-primary-2")
      val secondaryScopes = Seq("test-scope-secondary-1", "test-scope-secondary-2")

      val expectedPrimaryScopes = primaryScopes.map(scopeName => Scope(scopeName))
      val expectedSecondaryScopes = secondaryScopes.map(scopeName => Scope(scopeName))

      val updatedApp = app.addScopes(Primary, primaryScopes).addScopes(Secondary, secondaryScopes)

      val actualPrimaryScopes = updatedApp.environments.primary.scopes
      val actualSecondaryScopes = updatedApp.environments.secondary.scopes

      actualPrimaryScopes mustBe expectedPrimaryScopes
      actualSecondaryScopes mustBe expectedSecondaryScopes
    }

    "must get the correct Scopes" in {
      val expected = randomScopes()
      val environment = randomEnvironment().copy(scopes = expected)

      val actual = environmentScopes.get(environment)
      actual mustBe expected
    }

    "must set the scopes correctly" in {
      val expected = randomScopes()
      val environment = randomEnvironment().copy(scopes = Seq.empty)

      val actual = environmentScopes.set(environment, expected).scopes
      actual mustBe expected
    }
  }

  "environmentCredentials" - {
    "must get the correct credentials" in {
      val expected = randomCredentials()
      val environment = randomEnvironment().copy(credentials = expected)

      val actual = environmentCredentials.get(environment)
      actual mustBe expected
    }

    "must set the credentials correctly" in {
      val expected = randomCredentials()
      val environment = randomEnvironment().copy(credentials = Seq.empty)

      val actual = environmentCredentials.set(environment, expected).credentials
      actual mustBe expected
    }
  }

  "environmentPrimary" - {
    "must" - {
      behave like environmentsToEnvironmentLens(
        environmentPrimary,
        _.primary
      )
    }
  }

  "applicationPrimary" - {
    "must" - {
      behave like applicationToEnvironmentLens(
        applicationPrimary,
        _.primary
      )
    }
  }

  "applicationPrimaryScopes" - {
    "must" - {
      behave like applicationToScopesLens(
        applicationPrimaryScopes,
        _.primary
      )
    }
  }

  "applicationPrimaryCredentials" - {
    "must" - {
      behave like applicationToCredentialsLens(
        applicationPrimaryCredentials,
        _.primary
      )
    }
  }

  "environmentSecondary" - {
    "must" - {
      behave like environmentsToEnvironmentLens(
        environmentSecondary,
        _.secondary
      )
    }
  }

  "applicationSecondary" - {
    "must" - {
      behave like applicationToEnvironmentLens(
        applicationSecondary,
        _.secondary
      )
    }
  }

  "applicationSecondaryScopes" - {
    "must" - {
      behave like applicationToScopesLens(
        applicationSecondaryScopes,
        _.secondary
      )
    }
  }

  "applicationSecondaryCredentials" - {
    "must" - {
      behave like applicationToCredentialsLens(
        applicationSecondaryCredentials,
        _.secondary
      )
    }
  }

  "applicationTeamMembers" - {
    "must get the correct team members" in {
      val application = testApplication.copy(teamMembers = randomTeamMembers())
      val actual = applicationTeamMembers.get(application)
      actual mustBe application.teamMembers
    }

    "must set the team members correctly" in {
      val application = testApplication.copy(teamMembers = randomTeamMembers())
      val expected = randomTeamMembers()
      val actual = applicationTeamMembers.set(application, expected).teamMembers
      actual mustBe expected
    }
  }

  "applicationIssues" - {
    "must get the correct issues in " in {
      val application = testApplication.copy(issues = randomIssues())
      val actual = applicationIssues.get(application)
      actual mustBe application.issues
    }

    "must set the issues correctly" in {
      val application = testApplication.copy(issues = randomIssues())
      val expected = randomIssues()
      val actual = applicationIssues.set(application, expected).issues
      actual mustBe expected
    }
  }

  "ApplicationLensOps" - {
    "getScopes(Primary)" - {
      "must" - {
        behave like applicationScopesGetterFunction(
          applicationPrimaryScopes,
          application => ApplicationLensOps(application).getScopes(Primary)
        )
      }
    }

    "getScopes(Secondary)" - {
      "must" - {
        behave like applicationScopesGetterFunction(
          applicationSecondaryScopes,
          application => ApplicationLensOps(application).getScopes(Secondary)
        )
      }
    }

    "setScopes(Primary)" - {
      "must" - {
        behave like applicationScopesSetterFunction(
          applicationPrimaryScopes,
          (application, scopes) => ApplicationLensOps(application).setScopes(Primary, scopes)
        )
      }
    }

    "setScopes(Secondary)" - {
      "must" - {
        behave like applicationScopesSetterFunction(
          applicationSecondaryScopes,
          (application, scopes) => ApplicationLensOps(application).setScopes(Secondary, scopes)
        )
      }
    }

    "addScope(Primary)" - {
      "must" - {
        behave like applicationAddScopeFunction(
          applicationPrimaryScopes,
          (application, scope) => ApplicationLensOps(application).addScope(Primary, scope.name)
        )
      }
    }

    "addScope(Secondary)" - {
      "must" - {
        behave like applicationAddScopeFunction(
          applicationSecondaryScopes,
          (application, scope) => ApplicationLensOps(application).addScope(Secondary, scope.name)
        )
      }
    }

    "getMasterCredential(Primary)" - {
      "must return the most recently created credential" in {
        val master = randomCredential().copy(created = LocalDateTime.now())
        val credential1 = randomCredential().copy(created = LocalDateTime.now().minusDays(1))
        val credential2 = randomCredential().copy(created = LocalDateTime.now().minusDays(2))

        val application = testApplication
          .setCredentials(Primary, Seq(credential1, master, credential2))

        application.getMasterCredential(Primary) mustBe Some(master)
      }
    }

    "getMasterCredential(Secondary)" - {
      "must return the most recently created credential" in {
        val master = randomCredential().copy(created = LocalDateTime.now())
        val credential1 = randomCredential().copy(created = LocalDateTime.now().minusDays(1))
        val credential2 = randomCredential().copy(created = LocalDateTime.now().minusDays(2))

        val application = testApplication
          .setCredentials(Secondary, Seq(credential1, master, credential2))

        application.getMasterCredential(Secondary) mustBe Some(master)
      }
    }

    "getCredentials(Primary)" - {
      "must" - {
        behave like applicationCredentialsGetterFunction(
          applicationPrimaryCredentials,
          application => ApplicationLensOps(application).getCredentials(Primary)
        )
      }
    }

    "getCredentials(Secondary)" - {
      "must" - {
        behave like applicationCredentialsGetterFunction(
          applicationSecondaryCredentials,
          application => ApplicationLensOps(application).getCredentials(Secondary)
        )
      }
    }

    "setCredentials(Primary)" - {
      "must" - {
        behave like applicationCredentialsSetterFunction(
          applicationPrimaryCredentials,
          (application, credentials) => ApplicationLensOps(application).setCredentials(Primary, credentials)
        )
      }
    }

    "setCredentials(Secondary)" - {
      "must" - {
        behave like applicationCredentialsSetterFunction(
          applicationSecondaryCredentials,
          (application, credentials) => ApplicationLensOps(application).setCredentials(Secondary, credentials)
        )
      }

      "must keep environments and credentials in sync" in {
        val application = testApplication
        val credential1 = Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
        val credential2 = Credential("test-client-id-2", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)

        val expected = application.copy(
          environments = Environments(
            primary = Environment(
              scopes = Seq.empty,
              credentials = Seq(credential1)
            ),
            secondary = Environment(
              scopes = Seq.empty,
              credentials = Seq(credential2)
            )
          ),
          credentials = Set(credential1, credential2)
        )

        val actual = application
          .setCredentials(Primary, Seq(credential1))
          .setCredentials(Secondary, Seq(credential2))

        actual mustBe expected
      }
    }

    "addCredential(Primary)" - {
      "must" - {
        behave like applicationAddCredentialFunction(
          applicationPrimaryCredentials,
          (application, credential) => ApplicationLensOps(application).addCredential(Primary, credential)
        )
      }
    }

    "addCredential(Secondary)" - {
      "must" - {
        behave like applicationAddCredentialFunction(
          applicationSecondaryCredentials,
          (application, credential) => ApplicationLensOps(application).addCredential(Secondary, credential)
        )
      }
    }

    "removeCredential(Primary)" - {
      "must" - {
        behave like applicationRemoveCredentialFunction(
          applicationPrimaryCredentials,
          (application, clientId) => ApplicationLensOps(application).removeCredential(Primary, clientId)
        )
      }
    }

    "replaceCredential(Primary)" - {
      "must replace the correct credential" in {
        val credential1 = randomCredential()
        val credential2 = randomCredential()
        val credential3 = randomCredential()

        val application = testApplication.setCredentials(Primary, Seq(credential1, credential2, credential3))

        val credential = application.getCredentials(Primary)(1)

        val updatedCredential = credential.copy(
          created = credential.created.plusDays(1),
          clientSecret = Some("updated-secret")
        )

        val expected = application.setCredentials(Primary, Seq(credential1, updatedCredential, credential3))
        val actual = application.replaceCredential(Primary, updatedCredential)

        actual mustBe expected
      }

      "must throw IllegalArgumentException if the credential does not exist" in {
        val credential = randomCredential()
        val application = testApplication

        an[IllegalArgumentException] mustBe thrownBy (application.replaceCredential(Primary, credential))
      }
    }

    "replaceCredential(Secondary)" - {
      "must replace the correct credential" in {
        val credential1 = randomCredential()
        val credential2 = randomCredential()
        val credential3 = randomCredential()

        val application = testApplication.setCredentials(Secondary, Seq(credential1, credential2, credential3))

        val credential = application.getCredentials(Secondary)(1)

        val updatedCredential = credential.copy(
          created = credential.created.plusDays(1),
          clientSecret = Some("updated-secret")
        )

        val expected = application.setCredentials(Secondary, Seq(credential1, updatedCredential, credential3))
        val actual = application.replaceCredential(Secondary, updatedCredential)

        actual mustBe expected
      }

      "must throw IllegalArgumentException if the credential does not exist" in {
        val credential = randomCredential()
        val application = testApplication

        an[IllegalArgumentException] mustBe thrownBy (application.replaceCredential(Secondary, credential))
      }
    }

    "updateCredential(Primary)" - {
      "must correctly update a specific credential" in {
        val credential1 = Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
        val credential2 = Credential("test-client-id-2", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
        val credential3 = Credential("test-client-id-3", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)

        val credential2Updated = Credential(credential2.clientId, credential2.created, Some("test-secret"), Some("cret"), FakeHipEnvironments.primaryEnvironment.id)

        val application = testApplication
          .setCredentials(Primary, Seq(credential1, credential2, credential3))

        val expected = testApplication
          .setCredentials(Primary, Seq(credential1, credential2Updated, credential3))

        application.updateCredential(Primary, credential2Updated.clientId, "test-secret") mustBe expected
      }

      "must throw IllegalArgumentException when the credential does not exist" in {
        an [IllegalArgumentException] mustBe thrownBy(
          testApplication.updateCredential(Primary, "test-client-id", "test-secret")
        )
      }
    }
    
    "updateCredential(Secondary)" - {
      "must correctly update a specific credential" in {
        val credential1 = Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)
        val credential2 = Credential("test-client-id-2", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)
        val credential3 = Credential("test-client-id-3", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)

        val credential2Updated = Credential(credential2.clientId, credential2.created, Some("test-secret"), Some("cret"), FakeHipEnvironments.secondaryEnvironment.id)

        val application = testApplication
          .setCredentials(Secondary, Seq(credential1, credential2, credential3))

        val expected = testApplication
          .setCredentials(Secondary, Seq(credential1, credential2Updated, credential3))

        application.updateCredential(Secondary, credential2Updated.clientId, "test-secret") mustBe expected
      }

      "must throw IllegalArgumentException when the credential does not exist" in {
        an [IllegalArgumentException] mustBe thrownBy(
          testApplication.updateCredential(Secondary, "test-client-id", "test-secret")
        )
      }
    }
    
    "hasTeamMember" - {
      "must return true when the given email address belongs to a team member" in {
        val application = testApplication.copy(
          teamMembers = Seq(
            TeamMember("JoBlOgGs@EmAiL.cOm"),
            TeamMember("notjobloggs@email.com")
          )
        )

        application.hasTeamMember("jobloggs@email.com") mustBe true
      }

      "must return false when the given email does not belong to a team member" in {
        val application = testApplication.copy(
          teamMembers = Seq(
            TeamMember("team-member-1@email.com"),
            TeamMember("team-member-2@email.com")
          )
        )

        application.hasTeamMember("team-member-3@email.com") mustBe false
      }
    }

    "addTeamMember" - {
      "must add a team member with the given email to the application" in {
        val existing = Seq(
          TeamMember("team-member-1@email.com"),
          TeamMember("team-member-2@email.com")
        )

        val application = testApplication.copy(teamMembers = existing)
        val added = TeamMember(email = "team-member-3@email.com")

        val actual = application.addTeamMember(added.email).teamMembers
        actual mustBe existing :+ added
      }
    }

    "assertTeamMember" - {
      "must add a new team member when not already in the team" in {
        val existing = Seq(
          TeamMember("team-member-1@email.com"),
          TeamMember("team-member-2@email.com")
        )

        val application = testApplication.copy(teamMembers = existing)
        val added = TeamMember(email = "team-member-3@email.com")

        val actual = application.assertTeamMember(added.email).teamMembers
        actual mustBe existing :+ added
      }

      "must not add a new team member when already in the team" in {
        val existing = Seq(
          TeamMember("team-member-1@email.com"),
          TeamMember("team-member-2@email.com")
        )

        val application = testApplication.copy(teamMembers = existing)
        val added = TeamMember(email = "team-member-2@email.com")

        val actual = application.assertTeamMember(added.email).teamMembers
        actual mustBe existing
      }
    }

    "setIssues" - {
      "must set the issues correctly" in {
        val application = testApplication
        val expected = randomIssues()

        val actual = application.setIssues(expected).issues
        actual mustBe expected
      }
    }

    "addIssue" - {
      "must add the issue" in {
        val existingIssues = randomIssues()
        val application = testApplication.setIssues(existingIssues)
        val issue = "new-issue"

        val actual = application.addIssue(issue).issues
        actual mustBe existingIssues :+ issue
      }
    }

    "makePublic" - {
      "must remove hidden primary credentials" in {
        val hidden = randomCredential().copy(secretFragment = None)
        val visible = randomCredential()

        val application = testApplication.setCredentials(Primary, Seq(hidden, visible))

        application.makePublic().getCredentials(Primary) mustBe Seq(visible)
      }
    }
    
    "replaceApi" - {
      "must append the API if it is not already present" in {
        val api1 = Api("api-1", "api-1-name")
        val api2 = Api("api-2", "api-2-name")

        val application = testApplication.copy(apis = Seq(api1))

        val updatedApplication = application.replaceApi(api2)
        updatedApplication.apis mustBe Seq(api1, api2)
      }

      "must replace the API if it is already present, preserving the order of the API list" in {
        val api1 = Api("api-1", "api-1-name")
        val api2 = Api("api-2", "api-2-name")
        val api3 = Api("api-3", "api-3-name")
        val replacementApi2 = Api("api-2", "new api 2")
        
        val application = testApplication.copy(apis = Seq(api1, api2, api3))

        val updatedApplication = application.replaceApi(replacementApi2)
        updatedApplication.apis mustBe Seq(api1, replacementApi2, api3)
      }
    }
  }

}

object ApplicationLensesSpec {

  val testApplication: Application = Application(Some("test-id"), "test-name", Creator("test-email"), Seq(TeamMember("test-email")))

  def randomEnvironments(): Environments = Environments(
    primary = Environment(),
    secondary = Environment()
  )

  private def randomEnvironment(): Environment =
    Environment(
      scopes = randomScopes(),
      credentials = randomCredentials()
    )

  private def randomCredentials(): Seq[Credential] =
    (0 to Random.nextInt(5))
      .map(_ => randomCredential())

  private def randomCredential(): Credential = {
    val clientSecret = s"test-client-secret${randomString()}"
    Credential(
      clientId = s"test-client-id${randomString()}",
      created = LocalDateTime.now(),
      clientSecret = Some(clientSecret),
      secretFragment = Some(clientSecret.takeRight(4)),
      environmentId = "test-environment-id"
    )
  }

  private def randomScopes(): Seq[Scope] =
    (0 to Random.nextInt(5))
      .map(_ => randomScope())

  private def randomScope(): Scope =
    Scope(
      name = s"test-scope${randomString()}"
    )

  private def randomTeamMember(): TeamMember =
    TeamMember(email = randomString())

  private def randomTeamMembers(): Seq[TeamMember] =
    (0 to Random.nextInt(5))
      .map(_ => randomTeamMember())

  private def randomIssues(): Seq[String] =
    (0 to Random.nextInt(5))
      .map(_ => randomString())

  private def randomString(): String = Random.alphanumeric.take(Random.nextInt(10) + 10).mkString

  trait LensBehaviours extends AnyFreeSpecLike with Matchers  {

      def environmentsToEnvironmentLens(
        lens: Lens[Environments, Environment],
        getEnvironment: Environments => Environment
      ): Unit = {
        "it must get the correct environment" in {
          val environments = randomEnvironments()
          val expected = getEnvironment(environments)

          val actual = lens.get(environments)
          actual mustBe expected
        }

        "it must set the environment correctly" in {
          val environments = Environments()
          val expected = randomEnvironment()

          val actual = getEnvironment(lens.set(environments, expected))
          actual mustBe expected
        }
      }

      def applicationToEnvironmentLens(
        lens: Lens[Application, Environment],
        getEnvironment: Environments => Environment
      ): Unit = {
        "it must get the correct environment" in {
          val application = testApplication.copy(environments = randomEnvironments())
          val expected = getEnvironment(application.environments)

          val actual = lens.get(application)
          actual mustBe expected
        }

        "it must set the environment correctly" in {
          val application = testApplication
          val expected = randomEnvironment()

          val actual = getEnvironment(lens.set(application, expected).environments)
          actual mustBe expected
        }
      }

      def applicationToScopesLens(
        lens: Lens[Application, Seq[Scope]],
        getsEnvironment: Environments => Environment
      ): Unit = {
        "it must get the correct scopes" in {
          val application = testApplication.copy(environments = randomEnvironments())
          val expected = getsEnvironment(application.environments).scopes

          val actual = lens.get(application)
          actual mustBe expected
        }

        "it must set the scopes correctly" in {
          val application = testApplication
          val expected = randomScopes()

          val actual = getsEnvironment(lens.set(application, expected).environments).scopes
          actual mustBe expected
        }
      }

      def applicationToCredentialsLens(
        lens: Lens[Application, Seq[Credential]],
        getsEnvironment: Environments => Environment
      ): Unit = {
        "it must get the correct credentials" in {
          val application = testApplication.copy(environments = randomEnvironments())
          val expected = getsEnvironment(application.environments).credentials

          val actual = lens.get(application)
          actual mustBe expected
        }

        "it must set the credentials correctly" in {
          val application = testApplication
          val expected = randomCredentials()

          val actual = getsEnvironment(lens.set(application, expected).environments).credentials
          actual mustBe expected
        }
      }

      def applicationScopesGetterFunction(
        lens: Lens[Application, Seq[Scope]],
        getsScopes: Application => Seq[Scope]
      ): Unit = {
        "it must get the correct scopes" in {
          val expected = randomScopes()
          val actual = getsScopes(lens.set(testApplication, expected))
          actual mustBe expected
        }
      }

      def applicationScopesSetterFunction(
        lens: Lens[Application, Seq[Scope]],
        setsScopes: (Application, Seq[Scope]) => Application
      ): Unit = {
        "must set the scopes correctly" in {
          val expected = randomScopes()
          val actual = lens.get(setsScopes(testApplication, expected))
          actual mustBe expected
        }
      }

      def applicationAddScopeFunction(
        lens: Lens[Application, Seq[Scope]],
        addsScope: (Application, Scope) => Application
      ): Unit = {
        "must add the scope correctly" in {
          val scopes = randomScopes()
          val newScope = randomScope()
          val expected = scopes :+ newScope
          val application = lens.set(testApplication, scopes)

          val actual = lens.get(addsScope(application, newScope))
          actual mustBe expected
        }
      }

      def applicationCredentialsGetterFunction(
        lens: Lens[Application, Seq[Credential]],
        getsCredentials: Application => Seq[Credential]
      ): Unit = {
        "it must get the correct credentials" in {
          val expected = randomCredentials()
          val actual = getsCredentials(lens.set(testApplication, expected))
          actual mustBe expected
        }
      }

      def applicationCredentialsSetterFunction(
        lens: Lens[Application, Seq[Credential]],
        setsCredentials: (Application, Seq[Credential]) => Application
      ): Unit = {
        "must set the credentials correctly" in {
          val expected = randomCredentials()
          val actual = lens.get(setsCredentials(testApplication, expected))
          actual mustBe expected
        }
      }

      def applicationAddCredentialFunction(
        lens: Lens[Application, Seq[Credential]],
        addsCredential: (Application, Credential) => Application
      ): Unit = {
        "must add the credential correctly" in {
          val credentials = randomCredentials()
          val newCredential = randomCredential()
          val expected = credentials :+ newCredential
          val application = lens.set(testApplication, credentials)

          val actual = lens.get(addsCredential(application, newCredential))
          actual mustBe expected
        }
      }

      def applicationRemoveCredentialFunction(
        lens: Lens[Application, Seq[Credential]],
        removesCredential: (Application, String) => Application
      ): Unit ={
        "must remove the correct credential" in {
          val credential1 = randomCredential()
          val credential2 = randomCredential()
          val application = lens.set(testApplication, Seq(credential1, credential2))

          val actual = lens.get(removesCredential(application, credential1.clientId))
          actual mustBe Seq(credential2)
        }
      }
  }

}
