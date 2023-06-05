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

import play.api.libs.json.{Format, Json}

import java.time.{Clock, LocalDateTime}

case class Application (
  id: Option[String],
  name: String,
  created: LocalDateTime,
  createdBy: Creator,
  lastUpdated: LocalDateTime,
  teamMembers: Seq[TeamMember],
  environments: Environments,
  issues: Seq[String] = Seq.empty
)

object Application {

  def apply(id: Option[String], name: String, createdBy: Creator, teamMembers: Seq[TeamMember]): Application = {
    Application(id, name, createdBy, teamMembers, Clock.systemDefaultZone())
  }

  def apply(id: Option[String], name: String, createdBy: Creator, teamMembers: Seq[TeamMember], clock: Clock): Application = {
    val now = LocalDateTime.now(clock)
    Application(id, name, now, createdBy, now, teamMembers, Environments())
  }

  def apply(newApplication: NewApplication): Application = {
    apply(None, newApplication.name, newApplication.createdBy, newApplication.teamMembers)
  }

  def apply(newApplication: NewApplication, clock: Clock): Application = {
    apply(None, newApplication.name, newApplication.createdBy, newApplication.teamMembers, clock)
  }

  implicit val applicationFormat: Format[Application] = Json.format[Application]

}
