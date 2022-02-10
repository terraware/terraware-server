package com.terraformation.backend

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk

fun mockUser(userId: UserId = UserId(2)): IndividualUser {
  val user: IndividualUser = mockk(relaxed = true)

  every { user.userId } returns userId
  every { user.email } returns "$userId@terraformation.com"
  every { user.authId } returns "$userId"
  every { user.firstName } returns "First"
  every { user.lastName } returns "Last"
  every { user.userType } returns UserType.Individual

  val funcSlot = CapturingSlot<() -> Any>()
  every { user.run(capture(funcSlot)) } answers
      {
        CurrentUserHolder.runAs(user, funcSlot.captured, emptyList())
      }

  return user
}
