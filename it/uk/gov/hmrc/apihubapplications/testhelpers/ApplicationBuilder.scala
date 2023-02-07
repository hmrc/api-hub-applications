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

package uk.gov.hmrc.apihubapplications.testhelpers

import com.fasterxml.jackson.databind.JsonNode
import play.libs.Json
import uk.gov.hmrc.apihubapplications.models.application._

import java.time.LocalDateTime

object ApplicationBuilder {

  def apply(id: Option[String] = None,
            name: String = "app-it-test",
            created: LocalDateTime = LocalDateTime.parse("2023-02-06T15:50:36.629"),
            createdBy: Creator = Creator("app-builder-it-tests@hmrc.gov.uk"),
            lastUpdated: LocalDateTime = LocalDateTime.parse("2023-02-06T15:50:36.629"),
            teamMembers: Seq[TeamMember] = Seq.empty,
            environments: Environments = Environments(Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty), Environment(Seq.empty, Seq.empty))
           ): Application = Application(id, name, created, createdBy, lastUpdated, teamMembers, environments)


}
