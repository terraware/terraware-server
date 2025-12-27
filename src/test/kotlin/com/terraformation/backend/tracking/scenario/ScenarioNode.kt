package com.terraformation.backend.tracking.scenario

interface ScenarioNode {
  /**
   * Called before the node's init function is invoked. Can be used to insert any database objects
   * that are prerequisites of child objects that would be inserted in the init function.
   */
  fun prepare(): Unit = Unit

  /**
   * Called after the node's init function is invoked. The [finish] methods, if any, of child
   * objects created in the init function will have already been called.
   */
  fun finish(): Unit = Unit
}
