package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.InternalTagIsSystemDefinedException
import com.terraformation.backend.db.InternalTagNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.InternalTagsDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationInternalTagsDao
import com.terraformation.backend.db.default_schema.tables.pojos.InternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationInternalTagsRow
import com.terraformation.backend.db.default_schema.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import jakarta.inject.Named
import java.time.Clock
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class InternalTagStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val internalTagsDao: InternalTagsDao,
    private val organizationInternalTagsDao: OrganizationInternalTagsDao,
) {
  fun fetchTagById(id: InternalTagId): InternalTagsRow {
    requirePermissions { manageInternalTags() }

    return internalTagsDao.fetchOneById(id) ?: throw InternalTagNotFoundException(id)
  }

  fun createTag(name: String, description: String?): InternalTagId {
    requirePermissions { manageInternalTags() }

    val row =
        InternalTagsRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            description = description,
            isSystem = false,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
        )

    internalTagsDao.insert(row)

    return row.id!!
  }

  fun deleteTag(tagId: InternalTagId) {
    requirePermissions { manageInternalTags() }

    val existing = internalTagsDao.fetchOneById(tagId) ?: throw InternalTagNotFoundException(tagId)

    if (existing.isSystem == true) {
      throw InternalTagIsSystemDefinedException(tagId)
    }

    internalTagsDao.deleteById(tagId)
  }

  fun updateTag(tagId: InternalTagId, name: String, description: String?) {
    requirePermissions { manageInternalTags() }

    val existing = internalTagsDao.fetchOneById(tagId) ?: throw InternalTagNotFoundException(tagId)
    if (existing.isSystem == true) {
      throw InternalTagIsSystemDefinedException(tagId)
    }

    val updated =
        existing.copy(
            description = description,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
        )

    internalTagsDao.update(updated)
  }

  fun findAllTags(): List<InternalTagsRow> {
    requirePermissions { readInternalTags() }

    return internalTagsDao.findAll().sortedBy { it.id }
  }

  fun fetchOrganizationsByTagId(tagId: InternalTagId): Collection<OrganizationsRow> {
    requirePermissions { readInternalTags() }

    return dslContext
        .select(ORGANIZATIONS.asterisk())
        .from(ORGANIZATION_INTERNAL_TAGS)
        .join(ORGANIZATIONS)
        .on(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
        .where(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(tagId))
        .orderBy(ORGANIZATIONS.ID)
        .fetchInto(OrganizationsRow::class.java)
  }

  fun fetchAllOrganizationTagIds(): Map<OrganizationId, Set<InternalTagId>> {
    requirePermissions { readInternalTags() }

    return with(ORGANIZATION_INTERNAL_TAGS) {
      dslContext
          .select(INTERNAL_TAG_ID, ORGANIZATION_ID)
          .from(ORGANIZATION_INTERNAL_TAGS)
          .orderBy(ORGANIZATION_ID, INTERNAL_TAG_ID)
          .fetchGroups(ORGANIZATION_ID.asNonNullable(), INTERNAL_TAG_ID.asNonNullable())
          .mapValues { it.value.toSet() }
    }
  }

  fun fetchAllOrganizationsWithTagIds(): Map<OrganizationModel, Set<InternalTagId>> {
    requirePermissions { readInternalTags() }

    return with(ORGANIZATION_INTERNAL_TAGS) {
      val internalTagsMultiset =
          DSL.multiset(
                  DSL.select(INTERNAL_TAG_ID.asNonNullable())
                      .from(ORGANIZATION_INTERNAL_TAGS)
                      .where(ORGANIZATION_ID.eq(ORGANIZATIONS.ID))
                      .orderBy(INTERNAL_TAG_ID)
              )
              .convertFrom { result -> result.map { it.value1() }.toSet() }

      dslContext
          .select(ORGANIZATIONS.asterisk(), internalTagsMultiset)
          .from(ORGANIZATIONS)
          .orderBy(ORGANIZATIONS.ID)
          .fetch { record ->
            OrganizationModel(record) to (record[internalTagsMultiset] ?: emptySet())
          }
          .toMap()
    }
  }

  fun fetchTagsByOrganization(organizationId: OrganizationId): Set<InternalTagId> {
    requirePermissions { readInternalTags() }

    return with(ORGANIZATION_INTERNAL_TAGS) {
      dslContext
          .select(INTERNAL_TAG_ID)
          .from(ORGANIZATION_INTERNAL_TAGS)
          .where(ORGANIZATION_ID.eq(organizationId))
          .orderBy(INTERNAL_TAG_ID)
          .fetchSet(INTERNAL_TAG_ID.asNonNullable())
    }
  }

  fun updateOrganizationTags(organizationId: OrganizationId, tagIds: Set<InternalTagId>) {
    requirePermissions { manageInternalTags() }

    val existingTags = fetchTagsByOrganization(organizationId)
    val tagsToInsert = tagIds - existingTags
    val tagsToDelete = existingTags - tagIds

    dslContext.transaction { _ ->
      organizationInternalTagsDao.insert(
          tagsToInsert.map { tagId ->
            OrganizationInternalTagsRow(
                organizationId = organizationId,
                internalTagId = tagId,
                createdBy = currentUser().userId,
                createdTime = clock.instant(),
            )
          }
      )

      if (tagsToDelete.isNotEmpty()) {
        dslContext
            .deleteFrom(ORGANIZATION_INTERNAL_TAGS)
            .where(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID.eq(organizationId))
            .and(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.`in`(tagsToDelete))
            .execute()
      }
    }
  }
}
