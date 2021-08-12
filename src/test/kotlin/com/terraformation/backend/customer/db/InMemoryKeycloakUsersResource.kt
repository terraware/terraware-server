package com.terraformation.backend.customer.db

import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.URI
import java.util.UUID
import javax.ws.rs.core.Response
import org.keycloak.admin.client.resource.UserResource
import org.keycloak.admin.client.resource.UsersResource
import org.keycloak.representations.idm.UserRepresentation

/**
 * Test double to replace the Keycloak admin client's user management class.
 *
 * This is a real class rather than a simple call to [mockk] because in order to test how the
 * calling code interacts with Keycloak, we need to maintain a bit of state. There's the risk that
 * this dummy code won't match the actual behavior of Keycloak, so we still need to run real
 * end-to-end tests in a staging environment, but Keycloak is too heavyweight to spin up for unit
 * tests.
 *
 * This only implements the methods we actually call in the application code; the rest are delegated
 * to a MockK object which will throw exceptions if they're called.
 */
class InMemoryKeycloakUsersResource(private val stub: UsersResource = mockk()) :
    UsersResource by stub {
  /** Set this to true to cause methods to throw IOException. */
  var simulateRequestFailures: Boolean = false

  private val users = mutableListOf<UserResource>()

  override fun create(user: UserRepresentation): Response {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    if (users.map { it.toRepresentation() }.any {
      it.email == user.email || it.username == user.username || it.id == user.id
    }) {
      return Response.status(Response.Status.CONFLICT).entity("Dupe!").build()
    }

    if (user.id == null) {
      user.id = UUID.randomUUID().toString()
    }

    val resource: UserResource = mockk()

    every { resource.toRepresentation() } returns user

    users.add(resource)

    return Response.created(URI("http://dummy")).build()
  }

  override fun get(id: String): UserResource? {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    return users.firstOrNull { it.toRepresentation().id == id }
  }

  override fun search(username: String?, exact: Boolean?): List<UserRepresentation> {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    if (!exact!!) {
      throw IllegalArgumentException("Code should not be doing non-exact search by username")
    }

    return users.map { it.toRepresentation() }.filter { it.username == username!! }
  }
}
