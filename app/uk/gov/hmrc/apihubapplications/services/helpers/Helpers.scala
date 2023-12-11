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

import uk.gov.hmrc.apihubapplications.models.exception.{ApplicationsException, IdmsException}

object Helpers {

  def useFirstException[T](sequence: Seq[Either[IdmsException, T]]): Either[IdmsException, Seq[T]] = {
    sequence.foldLeft[Either[IdmsException, Seq[T]]](Right(Seq.empty))(
      (b, a) => (b, a) match {
        case (Left(e), _) => Left(e)
        case (_, Left(e)) => Left(e)
        case (Right(ts), Right(t)) => Right(ts :+ t)
      }
    )
  }

  def useFirstApplicationsException[T](sequence: Seq[Either[ApplicationsException, T]]): Either[ApplicationsException, Seq[T]] = {
    sequence.foldLeft[Either[ApplicationsException, Seq[T]]](Right(Seq.empty))(
      (b, a) => (b, a) match {
        case (Left(e), _) => Left(e)
        case (_, Left(e)) => Left(e)
        case (Right(ts), Right(t)) => Right(ts :+ t)
      }
    )
  }

}
