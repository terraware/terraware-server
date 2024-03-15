package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.tables.pojos.DefaultVotersRow
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
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
    insertUser()

    every { user.canReadDefaultVoters() } returns true
    every { user.canUpdateDefaultVoters() } returns true
  }

  @Nested
  inner class Fetch {
    @Test
    fun `fetches all when empty`() {
      assertEquals(emptyList<UserId>(), store.fetch())
    }

    @Test
    fun `fetches user that is not added`() {
      val userId = insertUser(100)
      assertEquals(emptyList<UserId>(), store.fetch(userId))
    }

    @Test
    fun `fetches all with users added`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      insertDefaultVoter(user200)
      assertEquals(listOf(user100, user200), store.fetch())
    }

    @Test
    fun `fetches one with users added`() {
      val user100 = insertUser(100)
      val user200 = insertUser(200)
      insertDefaultVoter(user100)
      insertDefaultVoter(user200)
      assertEquals(listOf(user100), store.fetch(user100))
    }

    @Test
    fun `fetches without permission throws exception`() {
      every { user.canReadDefaultVoters() } returns false
      assertThrows<AccessDeniedException> { store.fetch() }
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
  }
}
