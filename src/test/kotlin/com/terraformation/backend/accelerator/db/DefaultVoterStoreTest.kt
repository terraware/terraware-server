package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.DefaultVoterChangedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.pojos.DefaultVotersRow
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class DefaultVoterStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val eventPublisher = TestEventPublisher()
  private val store: DefaultVoterStore by lazy { DefaultVoterStore(dslContext, eventPublisher) }

  @BeforeEach
  fun setUp() {
    insertUser()

    every { user.canReadDefaultVoters() } returns true
    every { user.canUpdateDefaultVoters() } returns true
  }

  @Nested
  inner class FindAll {
    @Test
    fun `fetches all when empty`() {
      assertEquals(emptyList<UserId>(), store.findAll())
    }

    @Test
    fun `fetches all with users added`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      insertDefaultVoter(user200)
      assertEquals(listOf(user100, user200), store.findAll())
    }

    @Test
    fun `fetches without permission throws exception`() {
      every { user.canReadDefaultVoters() } returns false
      assertThrows<AccessDeniedException> { store.findAll() }
    }
  }

  @Nested
  inner class Exists {
    @Test
    fun `exists when empty`() {
      val user100 = insertUser(100)
      assertFalse(store.exists(user100))
    }

    @Test
    fun `exists when user is not in table`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      assertFalse(store.exists(user200))
    }

    @Test
    fun `exists when user is in table`() {
      val user100 = insertUser(100)
      insertDefaultVoter(user100)
      assertTrue(store.exists(user100))
    }
  }

  @Nested
  inner class Insert {

    @Test
    fun `inserts new entry to empty`() {
      val userId = insertUser(100)
      store.insert(userId)

      assertEquals(listOf(DefaultVotersRow(userId)), defaultVotersDao.findAll())
    }

    @Test
    fun `inserts new entry to non-empty`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      store.insert(user200)

      assertEquals(
          listOf(DefaultVotersRow(user100), DefaultVotersRow(user200)), defaultVotersDao.findAll())
    }

    @Test
    fun `inserts duplicated results in no-op`() {
      val user100 = insertUser(100)
      insertDefaultVoter(user100)
      store.insert(user100)
      assertEquals(listOf(DefaultVotersRow(user100)), defaultVotersDao.findAll())
    }

    @Test
    fun `inserts without permission throws exception`() {
      val user100 = insertUser(100)
      every { user.canUpdateDefaultVoters() } returns false
      assertThrows<AccessDeniedException> { store.insert(user100) }
    }

    @Test
    fun `inserts triggers event`() {
      val user100 = insertUser(100)
      store.insert(user100, true)

      eventPublisher.assertEventPublished(DefaultVoterChangedEvent(user100))
    }
  }

  @Nested
  inner class Delete {

    @Test
    fun `deletes only one`() {
      val userId = insertUser(100)

      insertDefaultVoter(userId)
      store.delete(userId)

      assertEquals(emptyList<DefaultVotersRow>(), defaultVotersDao.findAll())
    }

    @Test
    fun `deletes one from many`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      insertDefaultVoter(user200)
      store.delete(user200)

      assertEquals(listOf(DefaultVotersRow(user100)), defaultVotersDao.findAll())
    }

    @Test
    fun `deletes no matching record results in no-op`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      store.delete(user200)

      assertEquals(listOf(DefaultVotersRow(user100)), defaultVotersDao.findAll())
    }

    @Test
    fun `deletes without permission throws exception`() {
      val user100 = insertUser(100)
      insertDefaultVoter(user100)
      every { user.canUpdateDefaultVoters() } returns false
      assertThrows<AccessDeniedException> { store.delete(user100) }
    }

    @Test
    fun `deletes triggers event`() {
      val user100 = insertUser(100)
      insertDefaultVoter(user100)
      store.delete(user100, true)

      eventPublisher.assertEventPublished(DefaultVoterChangedEvent(user100))
    }
  }
}
