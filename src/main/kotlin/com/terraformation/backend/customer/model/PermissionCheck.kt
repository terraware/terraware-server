package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Role
import jakarta.servlet.http.HttpServlet
import java.util.stream.Collectors

/**
 * Record of a permission check that has been performed in the course of handling a request or job.
 * Concrete subclasses include the details of the check, which can vary.
 *
 * This is used to sanity-check that we don't have any "inversions" of permissions, where an
 * operation is guarded by a less-strict permission but tries to do something that requires a
 * more-strict one.
 *
 * Inversions of permissions have been a recurring source of bugs, especially in event handlers; the
 * goal of this code is to be able to quickly trace and fix them.
 */
abstract class PermissionCheck {
  private var callStack: List<StackWalker.StackFrame>? = null

  /**
   * Returns true if the permission being checked is implied by [guard]. Typically this means this
   * check is either checking the same thing as [guard] or that [guard] is stricter than this check.
   */
  abstract fun isImpliedBy(guard: PermissionCheck): Boolean

  /**
   * Returns true if this permission check is checking the same thing as [other] but with more
   * restrictive permissions.
   */
  abstract fun isStricterThan(other: PermissionCheck): Boolean

  /**
   * Populates the [callStack] property with a list of relevant stack frames for this permission
   * check.
   */
  fun populateCallStack() {
    callStack =
        StackWalker.getInstance().walk { frameStream ->
          frameStream
              .takeWhile { shouldContinueWalkingStack(it) }
              .filter { shouldIncludeStackFrame(it) }
              .collect(Collectors.toList())
        }
  }

  /**
   * Returns true if the stack walk in [populateCallStack] has not yet reached an entry point in the
   * application code. The first time this returns false for a given stack walk, the remaining stack
   * frames are all framework code, irrelevant for purposes of finding permission inversions, and it
   * would be a waste of CPU time to continue walking.
   */
  private fun shouldContinueWalkingStack(frame: StackWalker.StackFrame): Boolean {
    return frame.className != HttpServlet::class.java.name &&
        frame.className != "org.junit.platform.commons.util.ReflectionUtils"
  }

  /**
   * Returns true if this stack frame is relevant to include in the recorded stack trace for a
   * permission check. Excludes framework code and some of the methods in the internal
   * implementation of the permission system.
   */
  private fun shouldIncludeStackFrame(frame: StackWalker.StackFrame): Boolean {
    val className = frame.className
    val methodName = frame.methodName

    return when {
      !className.startsWith("com.terraformation") -> false
      className == IndividualUser::class.java.name ->
          !methodName.startsWith("recordPermissionCheck")
      className == PermissionCheck::class.java.name -> false
      className == PermissionRequirements::class.java.name -> !methodName.contains($$"$lambda$")
      // Kotlin wrapper method that populates default argument values; it will always be immediately
      // followed by a stack frame for the actual method.
      methodName.endsWith($$"$default") -> false
      else -> true
    }
  }

  /**
   * Returns true if this permission check is guarded by an earlier one. "Guarded" means that this
   * check is happening in the same function that did the earlier check, or in a function that's
   * called (possibly indirectly) by the one that did the earlier check.
   */
  fun isGuardedBy(earlierCheck: PermissionCheck): Boolean {
    val earlierStack = earlierCheck.callStack ?: return false
    val currentStack = callStack ?: return false
    val earlierWithoutPermissionChecking = removePermissionCheckFrames(earlierStack).reversed()
    val laterWithoutPermissionChecking = removePermissionCheckFrames(currentStack).reversed()

    val bothStacks = earlierWithoutPermissionChecking.zip(laterWithoutPermissionChecking)

    return bothStacks.all { (earlierFrame, laterFrame) ->
      earlierFrame.className == laterFrame.className &&
          earlierFrame.methodName == laterFrame.methodName
    }
  }

  fun prettyPrintStack(): String {
    return callStack?.joinToString("\n    ", prefix = "    ") ?: "N/A"
  }

  /**
   * Returns a copy of a permission stack trace without any of the permission-checking methods. In
   * other words, this is the stack trace of the code that invoked the permission check.
   */
  private fun removePermissionCheckFrames(
      stack: List<StackWalker.StackFrame>
  ): List<StackWalker.StackFrame> {
    val lastPermissionCheckIndex =
        stack.indexOfLast { frame ->
          frame.className.endsWith("PermissionRequirements") ||
              frame.className.endsWith("RequirePermissionsKt") ||
              frame.className.endsWith("IndividualUser")
        }

    return stack.drop(lastPermissionCheckIndex + 1)
  }
}

/**
 * Record of a check that the user has a particular role in the organization that owns a target
 * object.
 */
data class RolePermissionCheck(val role: Role, val targetId: Any) : PermissionCheck() {
  override fun isImpliedBy(guard: PermissionCheck): Boolean {
    return guard.isStricterThan(this) ||
        guard is RolePermissionCheck && guard.role == role && guard.targetId == targetId
  }

  override fun isStricterThan(other: PermissionCheck): Boolean {
    return other is RolePermissionCheck &&
        other.targetId == targetId &&
        role.level > other.role.level
  }

  /**
   * Returns a string representation that includes the class of the target ID. Our ID wrapper
   * classes' string representations are just the bare numeric values, but when tracking down
   * permission inversions, we want to know what kind of ID we're dealing with.
   */
  override fun toString(): String {
    val idType = targetId.javaClass.simpleName
    return "${javaClass.simpleName}(role=$role, targetId=$idType($targetId))"
  }

  private val Role.level
    get() =
        when (this) {
          Role.Contributor -> 1
          Role.Manager -> 2
          Role.Admin -> 3
          Role.Owner -> 4
          Role.TerraformationContact -> 5
        }
}

/** Record of a check that the user has a particular global role or a higher-privileged one. */
data class GlobalRolePermissionCheck(val globalRole: GlobalRole) : PermissionCheck() {
  override fun isImpliedBy(guard: PermissionCheck): Boolean {
    return guard.isStricterThan(this) ||
        guard is GlobalRolePermissionCheck && guard.globalRole == globalRole
  }

  override fun isStricterThan(other: PermissionCheck): Boolean {
    return other is GlobalRolePermissionCheck && globalRole.level > other.globalRole.level
  }

  private val GlobalRole.level
    get() =
        when (this) {
          GlobalRole.ReadOnly -> 1
          GlobalRole.TFExpert -> 2
          GlobalRole.AcceleratorAdmin -> 3
          GlobalRole.SuperAdmin -> 4
        }
}
