package com.terraformation.backend

import com.terraformation.backend.customer.model.IndividualUser

/**
 * Indicates that a test should be run with the current user set to a real instance of
 * [IndividualUser] rather than a test double.
 */
interface RunsAsIndividualUser : RunsAsUser {
  // This overrides the "val" in RunsAsUser with a "var" so that the setup method in DatabaseTest
  // can populate it after inserting the user into the database.
  override var user: IndividualUser
}
