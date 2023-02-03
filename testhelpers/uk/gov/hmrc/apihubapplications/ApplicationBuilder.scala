package uk.gov.hmrc.apihubapplications

import com.fasterxml.jackson.databind.JsonNode
import play.libs.Json
import uk.gov.hmrc.apihubapplications.models.application._

import java.time.LocalDateTime



case class ApplicationBuilder (name: String = "app-it-test",
                               created: LocalDateTime = LocalDateTime.now(),
                               createdBy: Creator = Creator("app-builder-it-tests@hmrc.gov.uk"),
                               lastUpdated: LocalDateTime = LocalDateTime.now(),
                               teamMembers: Seq[TeamMember] = Seq.empty,
                               environments: Environments = Environments(Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty))
                              ) {

  def build: Application = Application(None, name, created, createdBy, lastUpdated, teamMembers, environments)
}

object ApplicationBuilder {

  implicit class ApplicationTestExtensions(app: Application) {
    def toJson: JsonNode = Json.parse(
      s"""
         |{
         |  "id" : "${app.id.getOrElse("")}",
         |  "name" : "${app.name}",
         |  "created" : "${app.created}",
         |  "createdBy" : {
         |    "email" : "${app.createdBy.email}"
         |  },
         |  "lastUpdated" : "${app.lastUpdated}",
         |  "teamMembers" : [
         |
         |  ],
         |  "environments" : {
         |    "dev" : {
         |      "scopes" : [
         |
         |      ],
         |      "credentials" : [
         |
         |      ]
         |    },
         |    "test" : {
         |      "scopes" : [
         |
         |      ],
         |      "credentials" : [
         |
         |      ]
         |    },
         |    "preProd" : {
         |      "scopes" : [
         |
         |      ],
         |      "credentials" : [
         |
         |      ]
         |    },
         |    "prod" : {
         |      "scopes" : [
         |
         |      ],
         |      "credentials" : [
         |
         |      ]
         |    }
         |  }
         |}
         |""".stripMargin)
  }


}
