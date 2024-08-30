package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ApplicationRecipientsStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()

  private val store: ApplicationRecipientsStore by lazy {
    ApplicationRecipientsStore(applicationRecipientsDao, clock, dslContext)
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns all application recipients`() {
      every { user.canReadApplicationRecipients() } returns true
      val user1 = insertUser()
      val user2 = insertUser()
      insertUser()

      insertApplicationRecipient(user1)
      insertApplicationRecipient(user2)

      assertEquals(setOf(user1, user2), store.fetch())
    }

    @Test
    fun `throws exception if no permission`() {
      assertThrows<AccessDeniedException> { store.fetch() }
    }
  }

  @Nested
  inner class Contains {
    @Test
    fun `returns true if user is a recipient`() {
      every { user.canReadApplicationRecipients() } returns true
      val user1 = insertUser()
      val user2 = insertUser()

      insertApplicationRecipient(user1)

      assertTrue(store.contains(user1))
      assertFalse(store.contains(user2))
    }

    @Test
    fun `throws exception if no permission`() {
      val user1 = insertUser()
      insertApplicationRecipient(user1)

      assertThrows<AccessDeniedException> { store.fetch() }
    }
  }

  @Nested
  inner class Add {
    @Test
    fun `adds user that did not exist`() {
      every { user.canManageApplicationRecipients() } returns true

      val controlUser = insertUser()
      val existingRecipientUserId = insertUser()
      val newRecipientUserId = insertUser()

      insertApplicationRecipient(controlUser)
      insertApplicationRecipient(existingRecipientUserId)

      assertEquals(
          setOf(controlUser, existingRecipientUserId),
          applicationRecipientsDao.findAll().map { it.userId }.toSet(),
          "Set of notification recipients before add")

      store.add(existingRecipientUserId)
      store.add(newRecipientUserId)

      assertEquals(
          setOf(controlUser, existingRecipientUserId, newRecipientUserId),
          applicationRecipientsDao.findAll().map { it.userId }.toSet(),
          "Set of notification recipients after add")
    }

    @Test
    fun `throws exception if no permission`() {
      val userId = insertUser()

      assertThrows<AccessDeniedException> { store.add(userId) }
    }
  }

  @Nested
  inner class Remove {
    @Test
    fun `adds user that did not exist`() {
      every { user.canManageApplicationRecipients() } returns true

      val controlUser = insertUser()
      val existingRecipientUserId = insertUser()
      val nonRecipientUserId = insertUser()
      insertUser()

      insertApplicationRecipient(controlUser)
      insertApplicationRecipient(existingRecipientUserId)

      assertEquals(
          setOf(controlUser, existingRecipientUserId),
          applicationRecipientsDao.findAll().map { it.userId }.toSet(),
          "Set of notification recipients before remove")

      store.remove(existingRecipientUserId)
      store.remove(nonRecipientUserId)

      assertEquals(
          setOf(controlUser),
          applicationRecipientsDao.findAll().map { it.userId }.toSet(),
          "Set of notification recipients after remove")
    }

    @Test
    fun `throws exception if no permission`() {
      val userId = insertUser()

      assertThrows<AccessDeniedException> { store.remove(userId) }
    }
  }
}
