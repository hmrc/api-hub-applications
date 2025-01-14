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
import uk.gov.hmrc.apihubapplications.config.HipEnvironments
import uk.gov.hmrc.apihubapplications.models.application.*
import uk.gov.hmrc.apihubapplications.models.application.ApplicationLenses.ApplicationLensOps

import java.time.LocalDateTime

case class DbApplication(
  id: Option[String],
  name: String,
  created: LocalDateTime,
  createdBy: Creator,
  lastUpdated: LocalDateTime,
  teamId: Option[String],
  teamMembers: Seq[TeamMember],
  apis: Option[Seq[DbApi]],
  deleted: Option[Deleted],
  credentials: Set[DbCredential]
) {

  def toModel(hipEnvironments: HipEnvironments): Application = {
    Application(
      id = id,
      name = name,
      created = created,
      createdBy = createdBy,
      lastUpdated = lastUpdated,
      teamId = teamId,
      teamMembers = teamMembers,
      issues = Seq.empty,
      apis = apis match {
        case Some(apis) => apis.map(_.toModel)
        case None => Seq.empty
      },
      deleted = deleted,
      teamName = None,
      credentials = this.credentials.map(_.toModel(this))
    )
  }

}

object DbApplication {

  def apply(application: Application): DbApplication = {
    DbApplication(
      id = application.id,
      name = application.name,
      created = application.created,
      createdBy = application.createdBy,
      lastUpdated = application.lastUpdated,
      teamId = application.teamId,
      teamMembers = application.teamId.map(_ => Seq.empty).getOrElse(application.teamMembers),
      apis = application.apis match {
        case apis if apis.nonEmpty => Some(apis.map(DbApi.apply))
        case _ => None
      },
      deleted = application.deleted,
      credentials = application.credentials.map(DbCredential(_))
    )
  }

  implicit val formatDbApplication: Format[DbApplication] = Json.format[DbApplication]

}
