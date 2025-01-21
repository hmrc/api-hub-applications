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

package uk.gov.hmrc.apihubapplications.services.helpers

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{times, verify, verifyNoMoreInteractions, when}
import org.scalatest.EitherValues
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.apihubapplications.connectors.IdmsConnector
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException
import uk.gov.hmrc.apihubapplications.models.exception.IdmsException.CallError
import uk.gov.hmrc.apihubapplications.models.idms.{Client, ClientResponse, ClientScope}
import uk.gov.hmrc.apihubapplications.testhelpers.FakeHipEnvironments
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class ApplicationEnricherSpec extends AsyncFreeSpec
  with Matchers
  with MockitoSugar
  with EitherValues {

  import ApplicationEnricherSpec._

  private implicit val hc: HeaderCarrier = HeaderCarrier()
  private val clock: Clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())

  "process" - {
    "must correctly combine successful enrichers" in {
      val credential1 = Credential("test-client-id-1", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)
      val credential2 = Credential("test-client-id-2", LocalDateTime.now(), None, None, FakeHipEnvironments.primaryEnvironment.id)

      ApplicationEnrichers.process(
        testApplication,
        Seq(
          successfulEnricher(_.addCredential(FakeHipEnvironments.primaryEnvironment, credential1)),
          successfulEnricher(_.addCredential(FakeHipEnvironments.primaryEnvironment, credential2))
        )
      ).map {
        actual =>
          actual mustBe Right(testApplication.setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(credential1, credential2)))
      }
    }

    "must return an exception if any enricher returns an exception" in {
      ApplicationEnrichers.process(
        testApplication,
        Seq(
          successfulEnricher(identity),
          unsuccessfulEnricher()
        )
      ).map {
        actual =>
          actual mustBe Left(testException)
      }
    }

    "must handle an empty sequence" in {
      ApplicationEnrichers.process(
        testApplication,
        Seq.empty
      ).map {
        actual =>
          actual mustBe Right(testApplication)
      }
    }
  }

  "processAll" - {
    "must successfully process multiple applications" in {
      ApplicationEnrichers.processAll(
        Seq(testApplication, testApplication2),
        (application, _) =>
          successfulEnricher(
            _ => application.addTeamMember(s"team-member-${application.id}")
          )
        ,
        mock[IdmsConnector]
      ).map(
        actual =>
          actual mustBe Right(
            Seq(
              testApplication.addTeamMember(s"team-member-${testApplication.id}"),
              testApplication2.addTeamMember(s"team-member-${testApplication2.id}")
            )
          )
      )
    }

    "must return an exception if the enricher returns an exception for any application" in {
      val idmsException = IdmsException.clientNotFound("test-client-id")

      ApplicationEnrichers.processAll(
        Seq(testApplication, testApplication2),
        (application, _) =>
          if (application == testApplication) {
            Future.successful(Left(idmsException))
          }
          else {
            successfulEnricher(identity)
          }
        ,
        mock[IdmsConnector]
      ).map(
        actual =>
          actual mustBe Left(idmsException)
      )
    }

    "must handle an empty sequence of applications" in {
      ApplicationEnrichers.processAll(
        Seq.empty,
        (_, _) => successfulEnricher(identity),
        mock[IdmsConnector]
      ).map(
        actual =>
          actual mustBe Right(Seq.empty)
      )
    }
  }

  "credentialCreatingApplicationEnricher" - {
    "must create a credential in the hip environments and enrich the application with it" in {
      val expected = Seq(
        testApplication.setCredentials(FakeHipEnvironments.primaryEnvironment, Seq(testClientResponse1.asNewCredential(clock, FakeHipEnvironments.primaryEnvironment))),
        testApplication.setCredentials(FakeHipEnvironments.secondaryEnvironment, Seq(testClientResponse2.asNewCredential(clock, FakeHipEnvironments.secondaryEnvironment))),
      )

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.primaryEnvironment), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse1)))
      when(idmsConnector.createClient(eqTo(FakeHipEnvironments.secondaryEnvironment), eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Right(testClientResponse2)))

      val results = Future.sequence(
        FakeHipEnvironments.environments.map(
          ApplicationEnrichers.credentialCreatingApplicationEnricher(_, testApplication, idmsConnector, clock)
        )).map(_.map {
            case Right(enricher) => enricher.enrich(testApplication)
            case Left(e) => fail("Unexpected Left response", e)
          })

      results.map(
        _ mustBe expected
      )
    }

    "must return IdmsException if any call to IDMS fails" in {
      val expected = IdmsException("test-message", CallError)

      val idmsConnector = mock[IdmsConnector]
      when(idmsConnector.createClient(any, eqTo(Client(testApplication)))(any()))
        .thenReturn(Future.successful(Left(expected)))

      ApplicationEnrichers.credentialCreatingApplicationEnricher(FakeHipEnvironments.environments.head, testApplication, idmsConnector, clock).map {
        case Right(_) => fail("Unexpected Right response")
        case Left(e) => e mustBe expected
      }
    }
  }
  
}

object ApplicationEnricherSpec {

  val testApplication: Application = Application(Some("test-id-1"), "test-description", Creator("test-email"), Seq.empty)
  val testApplication2: Application = Application(Some("test-id-2"), "test-description-2", Creator("test-email-2"), Seq.empty)
  val testClientId1: String = "test-client-id-1"
  val testClientId2: String = "test-client-id-2"
  val testClientResponse1: ClientResponse = ClientResponse(testClientId1, "test-secret-1")
  val testClientResponse2: ClientResponse = ClientResponse(testClientId2, "test-secret-2")
  val testScopeName1: String = "test-scope-name-1"
  val testScopeName2: String = "test-scope-name-2"
  val testScopeName3: String = "test-scope-name-3"
  val testClientScope1: ClientScope = ClientScope(testScopeName1)
  val testClientScope2: ClientScope = ClientScope(testScopeName2)
  val testException: IdmsException = IdmsException("test-exception", CallError)

  def successfulEnricher(mutator: Application => Application): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.successful(
      Right(
        (application: Application) => mutator.apply(application)
      )
    )
  }

  def unsuccessfulEnricher(): Future[Either[IdmsException, ApplicationEnricher]] = {
    Future.successful(Left(testException))
  }

}
