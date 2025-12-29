package com.terraformation.backend.tracking.scenario

/**
 * Superclass for DSL nodes that have a list of child nodes of a particular type. Implements the
 * common behavior for child nodes (prepare, process the initializer lambda, finish).
 */
abstract class NodeWithChildren<T : ScenarioNode> : ScenarioNode {
  val children = mutableListOf<T>()

  /**
   * Initializes a child node and adds it to a list of children. This only needs to be called
   * directly when a node has multiple lists of children; for a simple single-child-list node, call
   * [initChild] instead.
   */
  fun <C : ScenarioNode> initAndAppend(
      child: C,
      list: MutableList<in C>,
      init: C.() -> Unit,
  ): C {
    child.prepare()
    child.init()
    child.finish()
    list.add(child)
    return child
  }

  fun <U : T> initChild(child: U, init: U.() -> Unit) = initAndAppend(child, children, init)
}
