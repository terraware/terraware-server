package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.pojos.DefaultVotersRow
import com.terraformation.backend.db.accelerator.tables.records.DefaultVotersRecord
import com.terraformation.backend.db.accelerator.tables.references.DEFAULT_VOTERS
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
  private val store: DefaultVoterStore by lazy { DefaultVoterStore(dslContext) }

  @BeforeEach
  fun setUp() {
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
      val user1 = insertUser()
      val user2 = insertUser()
      insertDefaultVoter(user1)
      insertDefaultVoter(user2)
      assertEquals(listOf(user1, user2), store.findAll())
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
      val userId = insertUser()
      assertFalse(store.exists(userId))
    }

    @Test
    fun `exists when user is not in table`() {
      val user1 = insertUser()
      val user2 = insertUser()
      insertDefaultVoter(user1)
      assertFalse(store.exists(user2))
    }

    @Test
    fun `exists when user is in table`() {
      val userId = insertUser()
      insertDefaultVoter(userId)
      assertTrue(store.exists(userId))
    }
  }

  @Nested
  inner class Insert {

    @Test
    fun `inserts new entry to empty`() {
      val userId = insertUser()
      store.insert(userId)

      assertEquals(listOf(DefaultVotersRow(userId)), defaultVotersDao.findAll())
    }

    @Test
    fun `inserts new entry to non-empty`() {
      val user1 = insertUser()
      val user2 = insertUser()
      insertDefaultVoter(user1)
      store.insert(user2)

      assertTableEquals(listOf(DefaultVotersRecord(user1), DefaultVotersRecord(user2)))
    }

    @Test
    fun `inserts duplicated results in no-op`() {
      val userId = insertUser()
      insertDefaultVoter(userId)
      store.insert(userId)
      assertEquals(listOf(DefaultVotersRow(userId)), defaultVotersDao.findAll())
    }

    @Test
    fun `inserts without permission throws exception`() {
      val userId = insertUser()
      every { user.canUpdateDefaultVoters() } returns false
      assertThrows<AccessDeniedException> { store.insert(userId) }
    }
  }

  @Nested
  inner class Delete {

    @Test
    fun `deletes only one`() {
      val userId = insertUser()

      insertDefaultVoter(userId)
      store.delete(userId)

      assertTableEmpty(DEFAULT_VOTERS)
    }

    @Test
    fun `deletes one from many`() {
      val user1 = insertUser()
      val user2 = insertUser()
      insertDefaultVoter(user1)
      insertDefaultVoter(user2)
      store.delete(user2)

      assertEquals(listOf(DefaultVotersRow(user1)), defaultVotersDao.findAll())
    }

    @Test
    fun `deletes no matching record results in no-op`() {
      val user1 = insertUser()
      val user2 = insertUser()
      insertDefaultVoter(user1)
      store.delete(user2)

      assertEquals(listOf(DefaultVotersRow(user1)), defaultVotersDao.findAll())
    }

    @Test
    fun `deletes without permission throws exception`() {
      val user1 = insertUser()
      insertDefaultVoter(user1)
      every { user.canUpdateDefaultVoters() } returns false
      assertThrows<AccessDeniedException> { store.delete(user1) }
    }
  }
}
