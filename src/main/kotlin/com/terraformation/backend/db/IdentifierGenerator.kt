package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.IDENTIFIER_SEQUENCES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.seedbank.db.AccessionStore
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class IdentifierGenerator(
    private val clock: Clock,
    private val dslContext: DSLContext,
) {
  private val log = perClassLogger()

  /**
   * Returns the next unused user-facing identifier. This is used in places where we need a unique
   * value to identify a resource but it's not acceptable to display the underlying integer ID from
   * the database, e.g., accession numbers.
   *
   * Identifiers are of the form YYYYMMDDXXX where XXX is a numeric suffix of three or more digits
   * that starts at 000 for the first identifier created on a particular date. The desired behavior
   * is for the suffix to represent the order in which entries were added to the system, so ideally
   * we want to avoid gaps or out-of-order values, though it's fine for that to be best-effort.
   *
   * The implementation uses a database sequence. The sequence's values follow the same pattern as
   * the identifiers, but the suffix is always 10 digits; it is rendered as a 3-or-more-digit value
   * by this method.
   *
   * If the date part of the sequence value doesn't match the current date, this method resets the
   * sequence to the zero suffix for the current date.
   *
   * Note that there is a bit of a race condition if multiple terraware-server instances happen to
   * allocate their first identifier of a given day at the same time; they might both reset the
   * sequence. To guard against that, [AccessionStore.create] will retry a few times if it gets a
   * unique constraint violation on the accession number.
   */
  fun generateIdentifier(organizationId: OrganizationId): String {
    val sequenceValue =
        with(IDENTIFIER_SEQUENCES) {
          dslContext
              .insertInto(IDENTIFIER_SEQUENCES)
              .set(ORGANIZATION_ID, organizationId)
              .set(NEXT_VALUE, 100000)
              .onDuplicateKeyUpdate()
              .set(NEXT_VALUE, NEXT_VALUE.plus(1))
              .returning(NEXT_VALUE)
              .fetchOne(NEXT_VALUE)!!
        }

    return "$sequenceValue"
  }
}
