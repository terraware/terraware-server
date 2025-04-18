package com.terraformation.backend

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.default_schema.UserType
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import java.time.ZoneId

fun mockUser(): IndividualUser {
  val timeZone = ZoneId.of("Pacific/Honolulu")
  val user: IndividualUser = mockk(relaxed = true)

  every { user.email } answers { "${user.userId}@terraformation.com" }
  every { user.authId } answers { "${user.userId}" }
  every { user.firstName } returns "First"
  every { user.lastName } returns "Last"
  every { user.fullName } returns "First Last"
  every { user.timeZone } returns timeZone
  every { user.userType } returns UserType.Individual

  val funcSlot = CapturingSlot<() -> Any>()
  every { user.run(capture(funcSlot)) } answers
      {
        CurrentUserHolder.runAs(user, funcSlot.captured, emptyList())
      }

  every { user.recordPermissionChecks(capture(funcSlot)) } answers
      {
        CurrentUserHolder.runAs(user, funcSlot.captured, emptyList())
      }

  return user
}
