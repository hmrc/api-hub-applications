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
import uk.gov.hmrc.apihubapplications.config.HipEnvironment
import uk.gov.hmrc.apihubapplications.models.Lens
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.*
import uk.gov.hmrc.apihubapplications.models.applications.ApplicationLensesSpec.*
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments

import java.time.LocalDateTime
import scala.util.Random

class ApplicationLensesSpec extends LensBehaviours {

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
    "getMasterCredential(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationMasterCredentialGetterFunction(FakeHipEnvironments.primaryEnvironment)
      }
    }

    "getMasterCredential(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationMasterCredentialGetterFunction(FakeHipEnvironments.secondaryEnvironment)
      }
    }

    "getCredentials(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationCredentialsGetterFunction(
          applicationCredentials,
          FakeHipEnvironments.primaryEnvironment
        )
      }
    }

    "getCredentials(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationCredentialsGetterFunction(
          applicationCredentials,
          FakeHipEnvironments.secondaryEnvironment
        )
      }
    }

    "setCredentials(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationCredentialsSetterFunction(
          applicationCredentials,
          FakeHipEnvironments.primaryEnvironment
        )
      }
    }

    "setCredentials(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationCredentialsSetterFunction(
          applicationCredentials,
          FakeHipEnvironments.secondaryEnvironment
        )
      }
    }

    "addCredential(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationAddCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.primaryEnvironment
        )
      }
    }

    "addCredential(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationAddCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.secondaryEnvironment
        )
      }
    }

    "removeCredential(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationRemoveCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.primaryEnvironment
        )
      }
    }

    "removeCredential(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationRemoveCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.secondaryEnvironment
        )
      }
    }

    "replaceCredential(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationReplaceCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.primaryEnvironment
        )
      }
    }

    "replaceCredential(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationReplaceCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.secondaryEnvironment
        )
      }
    }

    "updateCredential(FakeHipEnvironments.primaryEnvironment)" - {
      "must" - {
        behave like applicationUpdateCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.primaryEnvironment
        )
      }
    }

    "updateCredential(FakeHipEnvironments.secondaryEnvironment)" - {
      "must" - {
        behave like applicationUpdateCredentialFunction(
          applicationCredentials,
          FakeHipEnvironments.secondaryEnvironment
        )
      }
    }

    "updateCredential(FakeHipEnvironments.secondaryEnvironment)" - {
      "must correctly update a specific credential" in {
        val credential1 = Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)
        val credential2 = Credential("test-client-id-2", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)
        val credential3 = Credential("test-client-id-3", LocalDateTime.now(), None, None, FakeHipEnvironments.secondaryEnvironment.id)

        val credential2Updated = Credential(credential2.clientId, credential2.created, Some("test-secret"), Some("cret"), FakeHipEnvironments.secondaryEnvironment.id)

        val application = testApplication
          .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(credential1, credential2, credential3))

        val expected = testApplication
          .setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(credential1, credential2Updated, credential3))

        application.updateCredential(FakeHipEnvironments.secondaryEnvironment, credential2Updated.clientId, "test-secret") mustBe expected
      }

      "must throw IllegalArgumentException when the credential does not exist" in {
        an [IllegalArgumentException] mustBe thrownBy(
          testApplication.updateCredential(FakeHipEnvironments.secondaryEnvironment, "test-client-id", "test-secret")
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

  private def randomHipEnvironment(): HipEnvironment = {
    val rank = Random.nextInt(FakeHipEnvironments.environments.size) + 1
    FakeHipEnvironments.environments
      .filter(_.rank == rank)
      .head
  }

  private def randomCredentials(): Seq[Credential] =
    FakeHipEnvironments.environments
      .flatMap(randomCredentials)

  private def randomCredentials(hipEnvironment: HipEnvironment): Seq[Credential] =
    (0 to Random.nextInt(5))
      .map(_ => randomCredential(hipEnvironment))

  private def randomCredential(): Credential = {
      randomCredential(randomHipEnvironment())
  }

  private def randomCredential(hipEnvironment: HipEnvironment): Credential = {
    val clientSecret = s"test-client-secret${randomString()}"
    Credential(
      clientId = s"test-client-id${randomString()}",
      created = LocalDateTime.now(),
      clientSecret = Some(clientSecret),
      secretFragment = Some(clientSecret.takeRight(4)),
      environmentId = hipEnvironment.id
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

    def applicationCredentialsGetterFunction(
      lens: Lens[Application, Set[Credential]],
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must get the correct credentials" in {
        val credentials = randomCredentials()
        val expected = credentials.filter(_.environmentId == hipEnvironment.id)
        val application = lens.set(testApplication, credentials.toSet)
        val actual =  application.getCredentials(hipEnvironment)
        actual mustBe expected
      }
    }

    def applicationMasterCredentialGetterFunction(
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must get the correct master credential" in {
        val master = randomCredential(hipEnvironment).copy(created = LocalDateTime.now())
        val credential1 = randomCredential(hipEnvironment).copy(created = LocalDateTime.now().minusDays(1))
        val credential2 = randomCredential(hipEnvironment).copy(created = LocalDateTime.now().minusDays(2))

        val application = testApplication
          .setCredentials(hipEnvironment, Seq(credential1, master, credential2))

        application.getMasterCredential(hipEnvironment) mustBe Some(master)
      }
    }

    def applicationCredentialsSetterFunction(
      lens: Lens[Application, Set[Credential]],
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must set the credentials correctly" in {
        val credentials = randomCredentials().filterNot(_.environmentId == hipEnvironment.id)
        val newCredentials = randomCredentials(hipEnvironment)
        val expected = credentials ++ newCredentials
        val application = lens.set(testApplication, credentials.toSet)
        val actual = application.setCredentials(hipEnvironment, newCredentials).credentials
        actual must contain theSameElementsAs expected
      }
    }

    def applicationAddCredentialFunction(
      lens: Lens[Application, Set[Credential]],
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must add the credential correctly" in {
        val credentials = randomCredentials()
        val newCredential = randomCredential(hipEnvironment)
        val expected = credentials :+ newCredential
        val application = lens.set(testApplication, credentials.toSet)
        val actual = ApplicationLensOps(application).addCredential(hipEnvironment, newCredential)
        actual.credentials must contain theSameElementsAs expected
      }
    }

    def applicationRemoveCredentialFunction(
      lens: Lens[Application, Set[Credential]],
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must remove the correct credential" in {
        val credentials = randomCredentials().toSet
        val removeCredential = randomCredential(hipEnvironment)
        val application = lens.set(testApplication, credentials + removeCredential)

        val actual = ApplicationLensOps(application).removeCredential(hipEnvironment, removeCredential.clientId)
        actual.credentials must contain theSameElementsAs credentials
      }
    }

    def applicationReplaceCredentialFunction(
      lens: Lens[Application, Set[Credential]],
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must replace the correct credential" in {
        val credentials = randomCredentials().toSet
        val credential = randomCredential(hipEnvironment)

        val application = lens.set(testApplication, credentials + credential)

        val updatedCredential = credential.copy(
          created = credential.created.plusDays(1),
          clientSecret = Some("updated-secret")
        )

        val actual = ApplicationLensOps(application).replaceCredential(hipEnvironment, updatedCredential)

        actual.credentials must contain theSameElementsAs credentials + updatedCredential
      }

      "must throw IllegalArgumentException if the credential does not exist" in {
        val credential = randomCredential()
        val application = testApplication

        an[IllegalArgumentException] mustBe thrownBy (application.replaceCredential(hipEnvironment, credential))
      }
    }

    def applicationUpdateCredentialFunction(
      lens: Lens[Application, Set[Credential]],
      hipEnvironment: HipEnvironment
    ): Unit = {
      "must correctly update a specific credential" in {
        val credentials = randomCredentials().toSet
        val credential = randomCredential(hipEnvironment)

        val application = lens.set(testApplication, credentials + credential)
        val newSecret = "updated-test-secret-1234"

        val updatedCredential = credential.copy(
          clientSecret = Some(newSecret),
          secretFragment = Some(newSecret.takeRight(4))
        )

        val actual = application.updateCredential(hipEnvironment, credential.clientId, newSecret)
        actual.credentials must contain theSameElementsAs credentials + updatedCredential
      }

      "must throw IllegalArgumentException when the credential does not exist" in {
        an [IllegalArgumentException] mustBe thrownBy(
          testApplication.updateCredential(hipEnvironment, "test-client-id", "test-secret")
        )
      }
    }
  }

}
