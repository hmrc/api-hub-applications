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

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.apihubapplications.models.application._
import uk.gov.hmrc.apihubapplications.repositories.ApplicationsRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UsersService @Inject()(
    applicationsRepository: ApplicationsRepository,
)(implicit ec: ExecutionContext) {

  def findAll(): Future[Seq[UserContactDetails]] = {
    applicationsRepository
      .findAll(includeDeleted = true)
      .map(getUniqueEmails)
  }

  private def getUniqueEmails(applications: Seq[Application]): Seq[UserContactDetails] = {
    applications
      .flatMap(_.teamMembers)
      .map(_.email)
      .flatMap(normaliseEmailAddress(_))
      .distinct
      .sortBy(_.toLowerCase)
      .map(UserContactDetails(_))
  }

  private def normaliseEmailAddress(rawEmail: String): Option[String] = {
    // The mailbox part of an email address can contain '@' symbols, but the domain part cannot, so split on the last '@'
    val lastIndexOfAt = rawEmail.lastIndexOf('@')
    if (lastIndexOfAt == -1) return None

    val mailbox = rawEmail.substring(0, lastIndexOfAt)
    val domain = rawEmail.substring(lastIndexOfAt + 1)

    if (mailbox.isEmpty || domain.isEmpty) None
    // The mailbox part of an email is case-sensitive, but the domain part is not
    else Some(s"$mailbox@${domain.toLowerCase}")
  }

}
