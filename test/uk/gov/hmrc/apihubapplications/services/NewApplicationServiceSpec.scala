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

package uk.gov.hmrc.apihubapplications.services

import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.{Credential, NewScope, Pending, Primary, Scope, Secondary}
import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationNotFoundException, IdmsException}
import uk.gov.hmrc.apihubapplications.models.idms.{ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository
import uk.gov.hmrc.apihubapplications.testhelpers.ApiHubMockImplicits._
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationGenerator2
import uk.gov.hmrc.apihubapplications.testhelpers.ApplicationTestLenses._
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global

class NewApplicationServiceSpec
  extends AnyFreeSpec
    with Matchers
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks
    with ApplicationGenerator2
    with OptionValues {

  import NewApplicationServiceSpec._

  "findAll" - {
    "must return all applications from the repository" in {
      forAll(applicationsGenerator) {
        applications =>
          val fixture = buildFixture()

          fixture.repository.mockFindAll(applications)

          whenReady(fixture.service.findAll()) {
            actual =>
              actual mustBe applications
          }
      }
    }

    "must return all applications from the repository for named team member" in {
      forAll(applicationsGenerator, emailGenerator) {
        (applications, email) =>
          val fixture = buildFixture()

          fixture.repository.mockFilter(email, applications)

          whenReady(fixture.service.filter(email)) {
            actual =>
              actual mustBe applications
          }
      }
    }
  }

  "findById" - {
    "must return the application when it exists" in {
      forAll(applicationGenerator, approvedScopeGenerator, approvedScopeGenerator, secretGenerator) {
        (application, primaryScopes, secondaryScopes, secondarySecret) =>
          val fixture = buildFixture()

          fixture.repository.mockFindById(application.id.value, Right(application))

          fixture.idmsConnector.mockFetchClientScopes(
            Primary,
            application.primaryCredential.clientId,
            Right(primaryScopes.map(scope => ClientScope(scope.name)))
          )

          fixture.idmsConnector.mockFetchClient(
            Secondary,
            application.secondaryCredential.clientId,
            Right(ClientResponse(application.secondaryCredential.clientId, secondarySecret))
          )

          fixture.idmsConnector.mockFetchClientScopes(
            Secondary,
            application.secondaryCredential.clientId,
            Right(secondaryScopes.map(scope => ClientScope(scope.name)))
          )

          val expected = application
            .setPrimaryScopes(primaryScopes ++ application.getPrimaryScopes)
            .setSecondaryScopes(secondaryScopes)
            .setSecondaryCredentials(
              application.getSecondaryCredentials.map(
                credential =>
                  Credential(
                    credential.clientId,
                    Some(secondarySecret),
                    Some(secondarySecret.takeRight(4))
                  )
              )
            )

          whenReady(fixture.service.findById(application.id.value)) {
            actual =>
              actual mustBe Right(expected)
          }
      }
    }

    "must return ApplicationNotFoundException when the application does not exist" in {
      forAll(applicationGenerator) {
        application =>
          val fixture = buildFixture()

          val expected = ApplicationNotFoundException.forApplication(application)

          fixture.repository.mockFindById(
            application.id.value,
            Left(expected)
          )

          whenReady(fixture.service.findById(application.id.value)) {
            actual =>
              actual mustBe Left(expected)
          }
      }
    }

    "must return IdmsException when that is returned from the IDMS connector" in {
      forAll(applicationGenerator, approvedScopeGenerator, secretGenerator) {
        (application, secondaryScopes, secondarySecret)  =>
          val fixture = buildFixture()

          fixture.repository.mockFindById(application.id.value, Right(application))

          fixture.idmsConnector.mockFetchClientScopes(
            Primary,
            application.primaryCredential.clientId,
            Left(IdmsException.clientNotFound(application.primaryCredential.clientId))
          )

          fixture.idmsConnector.mockFetchClient(
            Secondary,
            application.secondaryCredential.clientId,
            Right(ClientResponse(application.secondaryCredential.clientId, secondarySecret))
          )

          fixture.idmsConnector.mockFetchClientScopes(
            Secondary,
            application.secondaryCredential.clientId,
            Right(secondaryScopes.map(scope => ClientScope(scope.name)))
          )

          whenReady(fixture.service.findById(application.id.value)) {
            actual =>
              actual mustBe Left(IdmsException.clientNotFound(application.primaryCredential.clientId))
          }
      }

    }
  }

  "addScopes" - {
    "must add new primary scope to Application and not update idms" in {
      forAll(applicationGenerator, nonEmptyTextGenerator) {
        (application, scopeName) =>
          val fixture = buildFixture()
          val newScope = NewScope(scopeName, Seq(Primary))
          val expected = application
            .addPrimaryScope(Scope(scopeName, Pending))
            .setLastUpdated(LocalDateTime.now(clock))

          fixture.repository.mockFindById(application.id.value, Right(application))

          fixture.repository.mockUpdate(expected, Right(()))

          whenReady(fixture.service.addScope(application.id.value, newScope)) {
            actual =>
              actual mustBe Right(())
              verifyZeroInteractions(fixture.idmsConnector.addClientScope(any(), any(), any())(any()))
          }
      }
    }
  }

}

object NewApplicationServiceSpec extends MockitoSugar {

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  case class Fixture(service: ApplicationsService, repository: ApplicationsRepository, idmsConnector: IdmsConnector)

  def buildFixture(): Fixture = {
    val repository = mock[ApplicationsRepository]
    val idmsConnector = mock[IdmsConnector]
    Fixture(new ApplicationsService(repository, clock, idmsConnector), repository, idmsConnector)
  }

}
