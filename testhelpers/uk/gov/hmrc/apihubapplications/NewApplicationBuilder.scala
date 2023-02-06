package uk.gov.hmrc.apihubapplications

import play.api.libs.json.{Json, JsValue}
import uk.gov.hmrc.apihubapplications.models.application._



case class NewApplicationBuilder (name: String = "new-app-it-test",
                                  createdBy: Creator = Creator("new-app-builder-it-tests@hmrc.gov.uk")) {

  def build: NewApplication = NewApplication(name, createdBy)
}

object NewApplicationBuilder {

  implicit class NewApplicationTestExtensions(app: NewApplication) {
    def toJson: JsValue = Json.parse(
      s"""
         |{
         |  "name" : "${app.name}",
         |  "createdBy" : {
         |    "email" : "${app.createdBy.email}"
         |  }
         |}
         |""".stripMargin)
  }


}
