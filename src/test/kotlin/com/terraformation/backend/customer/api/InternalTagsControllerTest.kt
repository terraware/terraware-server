package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ControllerIntegrationTest
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.default_schema.GlobalRole
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

class InternalTagsControllerTest : ControllerIntegrationTest() {
  @Nested
  inner class ListAllInternalTags {
    @Test
    fun `returns all internal tags`() {
      insertUserGlobalRole(role = GlobalRole.SuperAdmin)
      val newTagId = insertInternalTag("Random Tag")

      mockMvc
          .get("/api/v1/internalTags")
          .andExpectJson(
              """
                {
                  "tags": [
                    {
                      "id": ${InternalTagIds.Reporter},
                      "isSystem": true,
                      "name": "Reporter"
                    },
                    {
                      "id": ${InternalTagIds.Internal},
                      "isSystem": true,
                      "name": "Internal"
                    },
                    {
                      "id": ${InternalTagIds.Testing},
                      "isSystem": true,
                      "name": "Testing"
                    },
                    {
                      "id": $newTagId,
                      "isSystem": false,
                      "name": "Random Tag"
                    }
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent()
          )
    }

    @Test
    fun `returns error if user is not a super-admin`() {
      mockMvc.get("/api/v1/internalTags").andExpect { status { isForbidden() } }
    }
  }

  @Nested
  inner class ListOrganizationInternalTags {
    @Test
    fun `returns internal tag IDs for requested organization`() {
      insertUserGlobalRole(role = GlobalRole.SuperAdmin)
      val organizationId = insertOrganization()
      insertOrganizationInternalTag(tagId = InternalTagIds.Internal)
      val newTagId = insertInternalTag()
      insertOrganizationInternalTag()

      insertOrganization()
      insertOrganizationInternalTag(tagId = InternalTagIds.Reporter)

      mockMvc
          .get("/api/v1/internalTags/organizations/$organizationId")
          .andExpectJson(
              """
                {
                  "tagIds": [
                    ${InternalTagIds.Internal},
                    $newTagId
                  ],
                  "status": "ok"
                }
              """
                  .trimIndent()
          )
    }

    @Test
    fun `returns error if user is not a super-admin`() {
      val organizationId = insertOrganization()
      mockMvc.get("/api/v1/internalTags/organizations/$organizationId").andExpect {
        status { isForbidden() }
      }
    }
  }

  @Nested
  inner class UpdateOrganizationInternalTags {
    @Test
    fun `updates organization internal tags`() {
      insertUserGlobalRole(role = GlobalRole.SuperAdmin)
      val organizationId = insertOrganization()
      insertOrganizationInternalTag(tagId = InternalTagIds.Internal)
      val otherOrganizationId = insertOrganization()
      insertOrganizationInternalTag(tagId = InternalTagIds.Testing)
      val newTagId = insertInternalTag()

      val payload =
          """
            {
              "tagIds": [
                ${InternalTagIds.Reporter},
                $newTagId
              ]
            }
          """
              .trimIndent()

      mockMvc
          .put("/api/v1/internalTags/organizations/$organizationId") { content = payload }
          .andExpect { status { isOk() } }

      assertSetEquals(
          setOf(
              organizationId to InternalTagIds.Reporter,
              organizationId to newTagId,
              otherOrganizationId to InternalTagIds.Testing,
          ),
          organizationInternalTagsDao
              .findAll()
              .map { it.organizationId to it.internalTagId }
              .toSet(),
          "Organization internal tags after update",
      )
    }

    @Test
    fun `returns error if user is not a super-admin`() {
      val organizationId = insertOrganization()
      mockMvc
          .put("/api/v1/internalTags/organizations/$organizationId") {
            content = """{ "tagIds": [] }"""
          }
          .andExpect { status { isForbidden() } }
    }
  }
}
