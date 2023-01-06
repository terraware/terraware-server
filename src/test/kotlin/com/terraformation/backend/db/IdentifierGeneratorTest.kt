package com.terraformation.backend.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class IdentifierGeneratorTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()

  private val generator: IdentifierGenerator by lazy { IdentifierGenerator(clock, dslContext) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
  }

  @Test
  fun `identifiers are allocated per organization and per type`() {
    clock.instant = Instant.parse("2022-01-01T00:00:00Z")

    val otherOrganizationId = OrganizationId(2)
    insertOrganization(otherOrganizationId)

    val org1AccessionIdentifier1 =
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION)
    val org1AccessionIdentifier2 =
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION)
    val org1BatchIdentifier = generator.generateIdentifier(organizationId, IdentifierType.BATCH)
    val org2AccessionIdentifier =
        generator.generateIdentifier(otherOrganizationId, IdentifierType.ACCESSION)

    assertEquals(
        mapOf(
            "Org 1 accession 1" to "22-1-001",
            "Org 1 accession 2" to "22-1-002",
            "Org 1 batch" to "22-2-001",
            "Org 2 accession" to "22-1-001"),
        mapOf(
            "Org 1 accession 1" to org1AccessionIdentifier1,
            "Org 1 accession 2" to org1AccessionIdentifier2,
            "Org 1 batch" to org1BatchIdentifier,
            "Org 2 accession" to org2AccessionIdentifier))
  }

  @Test
  fun `generateIdentifier honors time zone`() {
    clock.instant = Instant.parse("2019-12-31T23:59:59Z")

    val identifierInUtc =
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, ZoneOffset.UTC)
    val identifierInLaterZone =
        generator.generateIdentifier(
            organizationId, IdentifierType.ACCESSION, ZoneOffset.ofHours(1))

    assertEquals("19-1-001", identifierInUtc, "Identifier in earlier time zone")
    assertEquals("20-1-001", identifierInLaterZone, "Identifier in later time zone")
  }

  @Test
  fun `generateIdentifier restarts suffixes at 001 when the year changes`() {
    clock.instant = Instant.parse("2022-01-01T00:00:00Z")

    generator.generateIdentifier(organizationId, IdentifierType.ACCESSION)

    clock.instant = Instant.parse("2023-05-06T00:00:00Z")

    val nextYearIdentifier = generator.generateIdentifier(organizationId, IdentifierType.ACCESSION)

    assertEquals("23-1-001", nextYearIdentifier)
  }

  @Test
  fun `generateIdentifier picks up where it left off after a century`() {
    clock.instant = Instant.parse("2022-01-01T00:00:00Z")
    generator.generateIdentifier(organizationId, IdentifierType.ACCESSION)

    clock.instant = Instant.parse("2122-01-01T00:00:00Z")
    val identifier = generator.generateIdentifier(organizationId, IdentifierType.ACCESSION)

    assertEquals("22-1-002", identifier)
  }
}
