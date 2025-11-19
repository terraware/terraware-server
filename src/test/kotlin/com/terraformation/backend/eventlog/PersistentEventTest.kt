package com.terraformation.backend.eventlog

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.eventlog.db.EventUpgradeUtils
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

class PersistentEventTest {
  @ParameterizedTest
  @MethodSource("getEventClasses")
  fun `event classes have version number suffixes`(kClass: KClass<*>) {
    val suffix = kClass.simpleName?.substringAfterLast('V')
    assertNotNull(suffix, "No suffix found")

    val suffixValue = suffix.toIntOrNull()
    assertNotNull(suffixValue, "Suffix doesn't have a numeric version")
    assertTrue(suffixValue >= 0, "Negative version numbers not allowed")
  }

  @ParameterizedTest
  @MethodSource("getEventClasses")
  fun `event classes have properties`(kClass: KClass<*>) {
    assertNotEquals(0, kClass.memberProperties.size, "Number of properties")
  }

  @ParameterizedTest
  @MethodSource("getUpgradableEventClasses")
  fun `upgradable event upgradeToNext methods declare concrete return types`(kClass: KClass<*>) {
    val returnType = getUpgradeReturnType(kClass)

    assertFalse(returnType.isAbstract, "$returnType is abstract; must be concrete")
  }

  // This is important because we strip off the version suffix to do a wildcard search for events
  // and if an event name changed or it moved to a different package, the older versions wouldn't
  // be matched by the search condition.
  @ParameterizedTest
  @MethodSource("getUpgradableEventClasses")
  fun `upgradable events return classes with the same names as the originals except for the version suffix`(
      kClass: KClass<*>
  ) {
    val returnType = getUpgradeReturnType(kClass)

    val originalClassWithoutVersionSuffix = kClass.java.name.substringBeforeLast('V')
    val upgradedClassWithoutVersionSuffix = returnType.java.name.substringBeforeLast('V')

    assertEquals(
        originalClassWithoutVersionSuffix,
        upgradedClassWithoutVersionSuffix,
        "$kClass upgrades to $returnType",
    )
  }

  @ParameterizedTest
  @MethodSource("getUpgradableEventClasses")
  fun `event upgrade paths end with non-upgradable events`(kClass: KClass<*>) {
    val seenClasses = mutableSetOf<KClass<*>>()
    var currentClass: KClass<*> = kClass

    while (currentClass.isSubclassOf(UpgradableEvent::class)) {
      assertFalse(currentClass in seenClasses, "Upgrade loop detected: $seenClasses")
      seenClasses.add(currentClass)
      currentClass = getUpgradeReturnType(currentClass)
    }

    assertTrue(
        currentClass.isSubclassOf(PersistentEvent::class),
        "$currentClass does not implement PersistentEvent",
    )
  }

  @ParameterizedTest
  @MethodSource("getEventClasses")
  fun `no new non-nullable properties have been added`(kClass: KClass<*>) {
    val propertiesFromManifest =
        propertyManifest.subSet(kClass.java.name + '/', kClass.java.name + ('/' + 1))
    val newlyAddedProperties = getRequiredProperties(kClass) - propertiesFromManifest

    if (newlyAddedProperties.isNotEmpty()) {
      val prettyMissing = newlyAddedProperties.joinToString("\n", "\n\n", "\n\n")
      val message =
          if (propertiesFromManifest.isEmpty()) {
            "Event class needs to be added to eventProperties.txt"
          } else {
            "New non-nullable properties have been added; you probably need to create a new event version"
          }

      assertEquals("", prettyMissing, message)
    }
  }

  private fun getRequiredProperties(
      kClass: KClass<*>,
      prefix: String = "${kClass.java.name}/",
  ): Set<String> {
    return kClass.primaryConstructor!!
        .parameters
        .filterNot { it.type.isMarkedNullable || it.isOptional }
        .flatMapTo(TreeSet()) { property ->
          val propertyClass = property.type.jvmErasure
          if (shouldEnumerateProperties(propertyClass)) {
            getRequiredProperties(propertyClass, "$prefix${property.name}.")
          } else {
            setOf("${prefix}${property.name}: ${propertyClass.simpleName}")
          }
        }
  }

  private fun getUpgradeReturnType(kClass: KClass<*>): KClass<*> {
    return kClass.memberFunctions
        .first { func ->
          func.name == "toNextVersion" &&
              func.parameters.size == 3 && // First parameter is the receiver
              func.parameters[1].type.jvmErasure == EventLogId::class &&
              func.parameters[2].type.jvmErasure == EventUpgradeUtils::class
        }
        .returnType
        .jvmErasure
  }

  companion object {
    @JvmStatic
    val eventClasses: List<KClass<out PersistentEvent>> by lazy {
      val scanner = ClassPathScanningCandidateComponentProvider(false)
      scanner.addIncludeFilter(AssignableTypeFilter(PersistentEvent::class.java))

      scanner
          .findCandidateComponents("com.terraformation.backend")
          .asSequence()
          .mapNotNull { it.beanClassName }
          .map {
            @Suppress("UNCHECKED_CAST")
            Class.forName(it) as Class<out PersistentEvent>
          }
          .map { it.kotlin }
          .filterNot { it.hasAnnotation<SkipPersistentEventTest>() }
          .toList()
    }

    @JvmStatic
    val upgradableEventClasses: List<KClass<out PersistentEvent>> by lazy {
      eventClasses.filter { it.isSubclassOf(UpgradableEvent::class) }
    }

    @Suppress("JAVA_CLASS_ON_COMPANION")
    val propertyManifest: TreeSet<String> by lazy {
      javaClass.getResourceAsStream("/eventlog/eventProperties.txt").use { stream ->
        TreeSet(stream.bufferedReader().readLines().filter { it.startsWith("com") })
      }
    }

    private val shouldEnumeratePropertiesMap = ConcurrentHashMap<KClass<*>, Boolean>()

    private fun shouldEnumerateProperties(kClass: KClass<*>): Boolean {
      return shouldEnumeratePropertiesMap.computeIfAbsent(kClass) {
        when {
          // Non-application classes (includes, e.g., Int and String and Geometry)
          !kClass.java.name.startsWith("com.terraformation") -> false
          // Enums (serialized as strings or integers even if they have properties)
          kClass.isSubclassOf(Enum::class) -> false
          // Single-value wrapper classes
          kClass.memberProperties.any { it.getter.hasAnnotation<JsonValue>() } -> false
          else -> true
        }
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // Events used by the tests. We don't use real application events because we don't want to start
  // failing if new event versions are introduced in the application code.

  data class ChildObject(val field: String)

  /** Verifies that fields on child objects are included in the event properties list. */
  @Suppress("unused") class TestChildFieldEventV1(val child: ChildObject) : PersistentEvent

  /**
   * Verifies that properties not included in the primary constructor don't need to be included in
   * the event properties list.
   */
  @Suppress("unused")
  class TestNonPrimaryConstructorPropertyEventV1(val x: Int) : PersistentEvent {
    lateinit var y: String
  }

  /**
   * Verifies that properties with default values don't need to be included in the event properties
   * list.
   */
  @Suppress("unused") class TestDefaultValuedPropertyEventV1(val x: Int = 0) : PersistentEvent

  /** Happy-path upgradable event. */
  @Suppress("unused")
  class TestUpgradableEventV1(val x: Int) : UpgradableEvent {
    override fun toNextVersion(eventLogId: EventLogId, eventUpgradeUtils: EventUpgradeUtils) =
        TestUpgradableEventV2(x)
  }

  class TestUpgradableEventV2(val x: Int) : PersistentEvent

  // Uncomment to verify the "must have properties" and "must have version numbers" tests
  //
  // class TestUpgradableEventWithoutProperties : PersistentEvent

  // Uncomment to verify the "must return concrete types" test
  //
  // class TestUpgradableEventWithAbstractReturnValueV1(val x: Int) : UpgradableEvent {
  //   override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils): UpgradableEvent = this
  // }

  // Uncomment both of these to verify the "paths end with non-upgradable events" test
  //
  // class TestUpgradableEventWithCycleV1(val x: Int) : UpgradableEvent {
  //   override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) =
  //       TestUpgradableEventWithCycleV2(x)
  // }
  //
  // class TestUpgradableEventWithCycleV2(val x: Int) : UpgradableEvent {
  //   override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) =
  //       TestUpgradableEventWithCycleV1(x)
  // }

  // Uncomment to verify the "upgrade to class with same name as original" test
  // class TestUpgradableEventWithDifferentNameThanOriginalV1(val x: Int) : UpgradableEvent {
  //   override fun toNextVersion(eventUpgradeUtils: EventUpgradeUtils) = TestUpgradableEventV2(x)
  // }
}
