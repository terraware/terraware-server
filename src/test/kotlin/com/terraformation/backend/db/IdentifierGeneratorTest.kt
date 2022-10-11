package com.terraformation.backend.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class IdentifierGeneratorTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock: Clock = mockk()

  private val generator: IdentifierGenerator by lazy { IdentifierGenerator(clock, dslContext) }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    insertUser()
    insertOrganization()
  }

  @Test
  fun `identifiers are allocated per organization`() {
    val otherOrganizationId = OrganizationId(2)
    insertOrganization(otherOrganizationId)

    val org1Identifier = generator.generateIdentifier(organizationId)
    val org2Identifier = generator.generateIdentifier(otherOrganizationId)

    assertEquals(org1Identifier, org2Identifier)
  }
}
