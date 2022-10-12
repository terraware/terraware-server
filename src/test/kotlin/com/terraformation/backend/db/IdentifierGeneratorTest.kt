package com.terraformation.backend.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
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

  @Test
  fun `generateIdentifier honors time zone`() {
    every { clock.instant() } returns
        ZonedDateTime.of(1999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant()

    val identifierInUtc = generator.generateIdentifier(organizationId, ZoneOffset.UTC)
    val identifierInLaterZone = generator.generateIdentifier(organizationId, ZoneOffset.ofHours(1))

    assertEquals(
        "19991231", identifierInUtc.substring(0..7), "Date part of identifier in earlier time zone")
    assertEquals(
        "20000101",
        identifierInLaterZone.substring(0..7),
        "Date part of identifier in later time zone")
  }

  @Test
  fun `switching time zones does not cause identifiers to run backwards`() {
    every { clock.instant() } returns
        ZonedDateTime.of(1999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC).toInstant()

    val identifierInLaterZone = generator.generateIdentifier(organizationId, ZoneOffset.ofHours(1))
    val identifierInUtc = generator.generateIdentifier(organizationId, ZoneOffset.UTC)

    assertEquals(
        "20000101",
        identifierInLaterZone.substring(0..7),
        "Date part of identifier in later time zone")
    assertEquals(
        "20000101",
        identifierInUtc.substring(0..7),
        "Date part of identifier in earlier time zone after later one has already been generated")
  }
}
