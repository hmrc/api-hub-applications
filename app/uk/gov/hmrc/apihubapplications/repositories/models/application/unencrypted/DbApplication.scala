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

package uk.gov.hmrc.apihubapplications.repositories.models.application.unencrypted

import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.apihubapplications.models.application.{Api, Application, Creator, TeamMember}

import java.time.LocalDateTime

case class DbApplication(
  id: Option[String],
  name: String,
  created: LocalDateTime,
  createdBy: Creator,
  lastUpdated: LocalDateTime,
  teamMembers: Seq[TeamMember],
  environments: DbEnvironments,
  apis: Option[Seq[Api]]
) {

  def toModel: Application =
    Application(
      id = id,
      name = name,
      created = created,
      createdBy = createdBy,
      lastUpdated = lastUpdated,
      teamMembers = teamMembers,
      environments = environments.toModel(this),
      issues = Seq.empty,
      apis = apis.getOrElse(Seq.empty)
    )

}

object DbApplication {

  def apply(application: Application): DbApplication =
    DbApplication(
      id = application.id,
      name = application.name,
      created = application.created,
      createdBy = application.createdBy,
      lastUpdated = application.lastUpdated,
      teamMembers = application.teamMembers,
      environments = DbEnvironments(application.environments),
      apis = Some(application.apis)
    )

  implicit val formatDbApplication: Format[DbApplication] = Json.format[DbApplication]

}
