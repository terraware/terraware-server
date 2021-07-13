package com.terraformation.backend.customer.model

/**
 * Represents a user's role in an organization.
 *
 * In general, application code should not concern itself with specific roles and shouldn't
 * reference this enum. Instead, it should call the permission checking methods on [UserModel] which
 * has the logic to map roles to specific permissions.
 *
 * In the future, it is possible we will support user-defined roles, at which point there will no
 * longer be a fixed list of roles hardwired in the code.
 */
enum class Role(val id: Int, val displayName: String) {
  CONTRIBUTOR(1, "Contributor"),
  MANAGER(2, "Manager"),
  ADMIN(3, "Admin"),
  OWNER(4, "Owner");

  companion object {
    private val byId: Map<Int, Role> = values().associateBy { it.id }

    @JvmStatic fun of(id: Int): Role? = byId[id]

    @Suppress("unused")
    private fun dummyFunctionToImportSymbolsReferredToInComments(): UserModel? = null
  }
}
