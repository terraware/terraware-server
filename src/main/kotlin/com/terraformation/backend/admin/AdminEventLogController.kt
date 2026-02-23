package com.terraformation.backend.admin

import com.fasterxml.jackson.annotation.JsonIgnore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventLogStore
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
class AdminEventLogController(
    private val eventLogStore: EventLogStore,
    private val objectMapper: ObjectMapper,
    private val userStore: UserStore,
) {
  private val dateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

  @GetMapping("/eventLog")
  fun getEventLogHome(
      @RequestParam selectedClasses: List<String>?,
      @RequestParam idField: String?,
      @RequestParam idValue: Long?,
      model: Model,
  ): String {
    model.addAttribute("allEventClasses", allEventClassesJson)

    if (selectedClasses?.isNotEmpty() == true && idField != null && idValue != null) {
      addSearchResults(idField, idValue, selectedClasses, model)
    }

    return "/admin/eventLog"
  }

  private fun addSearchResults(
      idField: String,
      idValue: Long,
      selectedClasses: List<String>,
      model: Model,
  ) {
    model.addAttribute("idField", idField)
    model.addAttribute("idValue", idValue)
    model.addAttribute("selectedClasses", selectedClasses)

    val selectedKClasses = selectedClasses.map { eventKClassesByQualifiedName[it]!! }
    val events =
        eventLogStore.fetchByIntegerField(
            idField,
            idValue,
            selectedKClasses,
        )

    val usersById =
        userStore.fetchManyById(events.map { it.createdBy }.distinct()).associateBy { it.userId }

    val propertyColumns = listAllProperties(selectedKClasses)

    val allColumns =
        listOf(
            TableColumn("ID"),
            TableColumn("Time (UTC)"),
            TableColumn("User"),
            TableColumn("Class"),
        ) + propertyColumns

    model.addAttribute("tableColumns", allColumns)

    val tableRows =
        events.map { eventLogEntry ->
          val user = usersById[eventLogEntry.createdBy]!!
          val userTooltip = "${user.fullName}\n${user.email}"
          val event = eventLogEntry.event
          val eventClass = event.javaClass

          val fixedCells =
              listOf(
                  TableCell("${eventLogEntry.id}"),
                  TableCell(dateTimeFormatter.format(eventLogEntry.createdTime)),
                  TableCell("${eventLogEntry.createdBy}", userTooltip),
                  TableCell(eventClass.simpleName.substringBeforeLast('V'), eventClass.name),
              )

          val propertyCells =
              propertyColumns.map { column -> TableCell(getPropertyValue(event, column.name)) }

          fixedCells + propertyCells
        }

    model.addAttribute("tableRows", tableRows)
  }

  /**
   * Metadata about the current versions of all the event classes in the code base. This is all the
   * classes that implement [PersistentEvent] but don't implement [UpgradableEvent].
   */
  private val allEventClasses: List<EventClassInfo> by lazy {
    val scanner = ClassPathScanningCandidateComponentProvider(false)
    scanner.addExcludeFilter(AssignableTypeFilter(UpgradableEvent::class.java))
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
        .map { kClass ->
          val idProperties = kClass.memberProperties.map { it.name }.filter { it.endsWith("Id") }
          val displayName = kClass.simpleName!!.substringBeforeLast('V')
          EventClassInfo(
              displayName = displayName,
              idProperties = idProperties,
              kClass = kClass,
              qualifiedName = kClass.qualifiedName!!,
          )
        }
        .sortedBy { it.displayName }
        .toList()
  }

  /**
   * Metadata about event classes in JSON form, suitable for including in a Freemarker template as
   * inline JavaScript. This is [allEventClasses] minus the [KClass] references.
   */
  private val allEventClassesJson: JsonNode by lazy { objectMapper.valueToTree(allEventClasses) }

  private val eventKClassesByQualifiedName: Map<String, KClass<out PersistentEvent>> by lazy {
    allEventClasses.associate { it.qualifiedName to it.kClass }
  }

  /**
   * Returns a list of table columns for all the properties of a collection of event classes. The
   * list is sorted by the number of classes that have the property in question; properties that are
   * common to all the events (e.g., `organizationId` if the events are all organization-related)
   * come first, whereas properties that are unique to a single event class come last. Properties
   * that occur in the same number of classes are sorted alphabetically.
   *
   * The idea is that we want as many property values as possible to show up toward the left side of
   * the table where the user won't have to scroll to see them. The long tail of one-off properties
   * can extend off the right side of the screen.
   */
  private fun listAllProperties(
      selectedClasses: List<KClass<out PersistentEvent>>
  ): List<TableColumn> {
    val propertyCountMap = mutableMapOf<String, Int>()

    for (eventClass in selectedClasses) {
      val properties = eventClass.memberProperties
      for (property in properties) {
        propertyCountMap[property.name] = propertyCountMap.getOrDefault(property.name, 0) + 1
      }
    }

    return propertyCountMap.entries
        .map { (name, count) -> TableColumn(name, eventClassCount = count) }
        .sortedWith(compareByDescending<TableColumn> { it.eventClassCount }.thenBy { it.name })
  }

  /**
   * Returns the value of a property on an event as a string, or an empty string if the property
   * doesn't exist on the event or is null.
   */
  private fun getPropertyValue(event: PersistentEvent, propertyName: String): String {
    return try {
      event::class
          .memberProperties
          .find { it.name == propertyName }
          ?.getter
          ?.call(event)
          ?.toString() ?: ""
    } catch (_: Exception) {
      ""
    }
  }

  data class TableColumn(
      val name: String,
      val eventClassCount: Int = 0,
  )

  data class TableCell(
      val value: String,
      val tooltip: String? = null,
  )

  data class EventClassInfo(
      val displayName: String,
      val idProperties: List<String>,
      @get:JsonIgnore val kClass: KClass<out PersistentEvent>,
      val qualifiedName: String,
  )
}
