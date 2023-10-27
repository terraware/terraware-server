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
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 1)
    val org1AccessionIdentifier2 =
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 1)
    val org1BatchIdentifier = generator.generateIdentifier(organizationId, IdentifierType.BATCH, 1)
    val org2AccessionIdentifier =
        generator.generateIdentifier(otherOrganizationId, IdentifierType.ACCESSION, 1)

    assertEquals(
        mapOf(
            "Org 1 accession 1" to "22-1-1-001",
            "Org 1 accession 2" to "22-1-1-002",
            "Org 1 batch" to "22-2-1-001",
            "Org 2 accession" to "22-1-1-001"),
        mapOf(
            "Org 1 accession 1" to org1AccessionIdentifier1,
            "Org 1 accession 2" to org1AccessionIdentifier2,
            "Org 1 batch" to org1BatchIdentifier,
            "Org 2 accession" to org2AccessionIdentifier))
  }

  @Test
  fun `identifier numbers are shared across facilities`() {
    clock.instant = Instant.parse("2022-01-01T00:00:00Z")

    val nursery1BatchIdentifier1 =
        generator.generateIdentifier(organizationId, IdentifierType.BATCH, 1)
    val nursery1BatchIdentifier2 =
        generator.generateIdentifier(organizationId, IdentifierType.BATCH, 1)
    val nursery2BatchIdentifier1 =
        generator.generateIdentifier(organizationId, IdentifierType.BATCH, 2)

    assertEquals(
        mapOf(
            "Nursery 1 batch 1" to "22-2-1-001",
            "Nursery 1 batch 2" to "22-2-1-002",
            "Nursery 2 batch 1" to "22-2-2-003"),
        mapOf(
            "Nursery 1 batch 1" to nursery1BatchIdentifier1,
            "Nursery 1 batch 2" to nursery1BatchIdentifier2,
            "Nursery 2 batch 1" to nursery2BatchIdentifier1))
  }

  @Test
  fun `generateIdentifier honors time zone`() {
    clock.instant = Instant.parse("2019-12-31T23:59:59Z")

    val identifierInUtc =
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 2, ZoneOffset.UTC)
    val identifierInLaterZone =
        generator.generateIdentifier(
            organizationId, IdentifierType.ACCESSION, 3, ZoneOffset.ofHours(1))

    assertEquals("19-1-2-001", identifierInUtc, "Identifier in earlier time zone")
    assertEquals("20-1-3-001", identifierInLaterZone, "Identifier in later time zone")
  }

  @Test
  fun `generateIdentifier restarts suffixes at 001 when the year changes`() {
    clock.instant = Instant.parse("2022-01-01T00:00:00Z")

    generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 1)

    clock.instant = Instant.parse("2023-05-06T00:00:00Z")

    val nextYearIdentifier =
        generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 8)

    assertEquals("23-1-8-001", nextYearIdentifier)
  }

  @Test
  fun `generateIdentifier picks up where it left off after a century`() {
    clock.instant = Instant.parse("2022-01-01T00:00:00Z")
    generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 1)

    clock.instant = Instant.parse("2122-01-01T00:00:00Z")
    val identifier = generator.generateIdentifier(organizationId, IdentifierType.ACCESSION, 1)

    assertEquals("22-1-1-002", identifier)
  }

  @Test
  fun `replaceFacilityNumber retains other parts of identifier`() {
    assertEquals("23-1-78-123456789", generator.replaceFacilityNumber("23-1-55-123456789", 78))
  }

  @Test
  fun `replaceFacilityNumber returns null if identifier has wrong format`() {
    assertNull(generator.replaceFacilityNumber("xyz", 1))
  }
}
