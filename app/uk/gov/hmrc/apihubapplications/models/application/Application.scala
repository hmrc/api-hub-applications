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
  teamId: Option[String],
  teamMembers: Seq[TeamMember],
  issues: Seq[String] = Seq.empty,
  apis: Seq[Api] = Seq.empty,
  deleted: Option[Deleted],
  teamName: Option[String],
  credentials: Set[Credential]
)

object Application {

  def apply(id: Option[String], name: String, createdBy: Creator, teamId: String): Application = {
    Application(id, name, createdBy, Some(teamId), Seq.empty, Clock.systemDefaultZone())
  }

  def apply(id: Option[String], name: String, createdBy: Creator, teamMembers: Seq[TeamMember]): Application = {
    Application(id, name, createdBy, teamMembers, Clock.systemDefaultZone())
  }

  def apply(id: Option[String], name: String, createdBy: Creator, teamId: Option[String], teamMembers: Seq[TeamMember]): Application = {
    Application(id, name, createdBy, teamId, teamMembers, Clock.systemDefaultZone())
  }

  def apply(id: Option[String], name: String, createdBy: Creator, teamMembers: Seq[TeamMember], clock: Clock): Application = {
    val now = LocalDateTime.now(clock)
    Application(id, name, now, createdBy, now, None, teamMembers, apis = Seq.empty, deleted = None, teamName = None, credentials = Set.empty)
  }

  def apply(id: Option[String], name: String, createdBy: Creator, teamId: Option[String], teamMembers: Seq[TeamMember], clock: Clock): Application = {
    val now = LocalDateTime.now(clock)
    Application(id, name, now, createdBy, now, teamId, teamMembers, apis = Seq.empty, deleted = None, teamName = None, credentials = Set.empty)
  }

  def apply(newApplication: NewApplication): Application = {
    apply(newApplication, Clock.systemDefaultZone())
  }

  def apply(newApplication: NewApplication, clock: Clock): Application = {
    apply(None, newApplication.name, newApplication.createdBy, newApplication.teamId, newApplication.teamMembers, clock)
  }

  def apply(id: Option[String], name: String, createdBy: Creator, now: LocalDateTime, teamId: String, credentials: Set[Credential]): Application = {
    Application(id, name, now, createdBy, now, Some(teamId), Seq.empty, apis = Seq.empty, deleted = None, teamName = None, credentials = credentials)
  }

  def apply(id: Option[String], name: String, createdBy: Creator, now: LocalDateTime, teamMembers: Seq[TeamMember], credentials: Set[Credential]): Application = {
    Application(id, name, now, createdBy, now, None, teamMembers, apis = Seq.empty, deleted = None, teamName = None, credentials = credentials)
  }

  def apply(id: Option[String], name: String, createdBy: Creator, now: LocalDateTime, teamMembers: Seq[TeamMember], credentials: Set[Credential], deleted: Option[Deleted]): Application = {
    Application(id, name, now, createdBy, now, None, teamMembers, apis = Seq.empty, deleted = deleted, teamName = None, credentials = credentials)
  }

  def apply(id: Option[String], name: String, created: LocalDateTime, createdBy: Creator, lastUpdated: LocalDateTime, teamMembers: Seq[TeamMember], credentials: Set[Credential]): Application = {
    Application(id, name, created, createdBy, lastUpdated, None, teamMembers, apis = Seq.empty, deleted = None, teamName = None, credentials = credentials)
  }

  implicit val applicationFormat: Format[Application] = Json.format[Application]

}
