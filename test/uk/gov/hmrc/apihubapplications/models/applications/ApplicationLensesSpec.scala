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

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import uk.gov.hmrc.apihubapplications.models.Lens
import uk.gov.hmrc.apihubapplications.models.application.{Application, Approved, Creator, Credential, Denied, Environment, Environments, Pending, Scope, ScopeStatus, TeamMember}
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses._
import uk.gov.hmrc.apihubapplications.models.applications.ApplicationLensesSpec.{LensBehaviours, randomEnvironment, randomEnvironments, randomScopes, randomString, randomTeamMembers, testApplication}

import scala.util.Random

class ApplicationLensesSpec extends AnyFreeSpec with Matchers with LensBehaviours {

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

  "environmentProd" - {
    "must" - {
      behave like environmentsToEnvironmentLens(
        environmentProd,
        _.prod
      )
    }
  }

  "applicationProd" - {
    "must" - {
      behave like applicationToEnvironmentLens(
        applicationProd,
        _.prod
      )
    }
  }

  "applicationProdScopes" - {
    "must" - {
      behave like applicationToScopesLens(
        applicationProdScopes,
        _.prod
      )
    }
  }

  "environmentPreProd" - {
    "must" - {
      behave like environmentsToEnvironmentLens(
        environmentPreProd,
        _.preProd
      )
    }
  }

  "applicationPreProd" - {
    "must" - {
      behave like applicationToEnvironmentLens(
        applicationPreProd,
        _.preProd
      )
    }
  }

  "applicationPreProdScopes" - {
    "must" - {
      behave like applicationToScopesLens(
        applicationPreProdScopes,
        _.preProd
      )
    }
  }

  "environmentTest" - {
    "must" - {
      behave like environmentsToEnvironmentLens(
        environmentTest,
        _.test
      )
    }
  }

  "applicationTest" - {
    "must" - {
      behave like applicationToEnvironmentLens(
        applicationTest,
        _.test
      )
    }
  }

  "applicationTestScopes" - {
    "must" - {
      behave like applicationToScopesLens(
        applicationTestScopes,
        _.test
      )
    }
  }

  "environmentDev" - {
    "must" - {
      behave like environmentsToEnvironmentLens(
        environmentDev,
        _.dev
      )
    }
  }

  "applicationDev" - {
    "must" - {
      behave like applicationToEnvironmentLens(
        applicationDev,
        _.dev
      )
    }
  }

  "applicationDevScopes" - {
    "must" - {
      behave like applicationToScopesLens(
        applicationDevScopes,
        _.dev
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

  "ApplicationLensOps" - {
    "getProdScopes" - {
      "must" - {
        behave like applicationScopesGetterFunction(
          applicationProdScopes,
          application => ApplicationLensOps(application).getProdScopes
        )
      }
    }

    "setProdScopes" - {
      "must" - {
        behave like applicationScopesSetterFunction(
          applicationProdScopes,
          (application, scopes) => ApplicationLensOps(application).setProdScopes(scopes)
        )
      }
    }

    "addProdScope" - {
      "must" - {
        behave like applicationAddScopeFunction(
          applicationProdScopes,
          (application, scope) => ApplicationLensOps(application).addProdScope(scope)
        )
      }
    }

    "hasProdPendingScope" - {
      "must return true when the application has a production pending scope" in {
        val application = testApplication.addProdScope(Scope("test-scope", Pending))
        application.hasProdPendingScope mustBe true
      }

      "must return false when the application does not have a production pending scope" in {
        val application = testApplication
          .addProdScope(Scope(randomString(), Approved))
          .addProdScope(Scope(randomString(), Denied))
          .addPreProdScope(Scope(randomString(), Pending))
          .addTestScope(Scope(randomString(), Pending))
          .addDevScope(Scope(randomString(), Pending))

        application.hasProdPendingScope mustBe false
      }
    }

    "getPreProdScopes" - {
      "must" - {
        behave like applicationScopesGetterFunction(
          applicationPreProdScopes,
          application => ApplicationLensOps(application).getPreProdScopes
        )
      }
    }

    "setPreProdScopes" - {
      "must" - {
        behave like applicationScopesSetterFunction(
          applicationPreProdScopes,
          (application, scopes) => ApplicationLensOps(application).setPreProdScopes(scopes)
        )
      }
    }

    "addPreProdScope" - {
      "must" - {
        behave like applicationAddScopeFunction(
          applicationPreProdScopes,
          (application, scope) => ApplicationLensOps(application).addPreProdScope(scope)
        )
      }
    }

    "getTestScopes" - {
      "must" - {
        behave like applicationScopesGetterFunction(
          applicationTestScopes,
          application => ApplicationLensOps(application).getTestScopes
        )
      }
    }

    "setTestScopes" - {
      "must" - {
        behave like applicationScopesSetterFunction(
          applicationTestScopes,
          (application, scopes) => ApplicationLensOps(application).setTestScopes(scopes)
        )
      }
    }

    "addTestScope" - {
      "must" - {
        behave like applicationAddScopeFunction(
          applicationTestScopes,
          (application, scope) => ApplicationLensOps(application).addTestScope(scope)
        )
      }
    }

    "getDevScopes" - {
      "must" - {
        behave like applicationScopesGetterFunction(
          applicationDevScopes,
          application => ApplicationLensOps(application).getDevScopes
        )
      }
    }

    "setDevScopes" - {
      "must" - {
        behave like applicationScopesSetterFunction(
          applicationDevScopes,
          (application, scopes) => ApplicationLensOps(application).setDevScopes(scopes)
        )
      }
    }

    "addDevScope" - {
      "must" - {
        behave like applicationAddScopeFunction(
          applicationDevScopes,
          (application, scope) => ApplicationLensOps(application).addDevScope(scope)
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

  }

}

object ApplicationLensesSpec {

  val testApplication: Application = Application(Some("test-id"), "test-name", Creator("test-email"))

  def randomEnvironments(): Environments = Environments(
    dev = randomEnvironment(),
    test = randomEnvironment(),
    preProd = randomEnvironment(),
    prod = randomEnvironment()
  )

  private def randomEnvironment(): Environment =
    Environment(
      scopes = randomScopes(),
      credentials = randomCredentials()
    )

  private def randomCredentials(): Seq[Credential] =
    (0 to Random.nextInt(5))
      .map(_ => randomCredential())

  private def randomCredential(): Credential =
    Credential(
      clientId = s"test-client-id${randomString()}",
      clientSecret = s"test-client-secret${randomString()}"
    )

  private def randomScopes(): Seq[Scope] =
    (0 to Random.nextInt(5))
      .map(_ => randomScope())

  private def randomScope(): Scope =
    Scope(
      name = s"test-scope${randomString()}",
      status = randomScopeStatus()
    )

  private def randomScopeStatus(): ScopeStatus =
    Random.nextInt(3) match {
      case 0 => Pending
      case 1 => Denied
      case _ => Approved
    }

  private def randomTeamMember(): TeamMember =
    TeamMember(email = randomString())

  private def randomTeamMembers(): Seq[TeamMember] =
    (0 to Random.nextInt(5))
      .map(_ => randomTeamMember())

  private def randomString(): String = Random.alphanumeric.take(Random.nextInt(10) + 1).mkString

  trait LensBehaviours {
    this: AnyFreeSpec with Matchers =>

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

  }

}
