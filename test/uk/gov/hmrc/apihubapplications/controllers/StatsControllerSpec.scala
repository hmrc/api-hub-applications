package uk.gov.hmrc.apihubapplications.controllers

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.apihubapplications.controllers.actions.{FakeIdentifierAction, IdentifierAction}
import uk.gov.hmrc.apihubapplications.models.stats.ApisInProductionStatistic
import uk.gov.hmrc.apihubapplications.services.StatsService

import scala.concurrent.Future

class StatsControllerSpec
  extends AnyFreeSpec
    with Matchers
    with MockitoSugar
    with OptionValues {

  "apisInProduction" - {
    "must return Ok and the statistic" in {
      val fixture = buildFixture()
      val statistic = ApisInProductionStatistic(10, 2)

      when(fixture.statsService.apisInProduction()(any)).thenReturn(Future.successful(Right(statistic)))

      running(fixture.application) {
        val request = FakeRequest(routes.StatsController.apisInProduction())
        val result = route(fixture.application, request).value

        status(result) mustBe OK
        contentAsJson(result) mustBe Json.toJson(statistic)
      }
    }
  }

  private case class Fixture(application: Application, statsService: StatsService)

  private def buildFixture(): Fixture = {
    val statsService = mock[StatsService]

    val application = new GuiceApplicationBuilder()
      .overrides(
        bind[ControllerComponents].toInstance(stubControllerComponents()),
        bind[StatsService].toInstance(statsService),
        bind[IdentifierAction].to(classOf[FakeIdentifierAction])
      )
      .build()

    Fixture(application, statsService)
  }

}
