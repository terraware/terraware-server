package com.terraformation.backend.seedbank.search

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestResultsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

internal class SearchServiceNestedFieldsTest : SearchServiceTest() {
  private val bagsNumberField = rootPrefix.resolve("bags.number")
  private val facilityNameField = rootPrefix.resolve("facility.name")
  private val seedsTestedField = rootPrefix.resolve("viabilityTests.seedsTested")
  private val seedsGerminatedField =
      rootPrefix.resolve("viabilityTests.viabilityTestResults.seedsGerminated")
  private val testTypeField = rootPrefix.resolve("viabilityTests.type")

  private val bagsTable = tables.bags
  private val viabilityTestResultsTable = tables.viabilityTestResults

  private lateinit var testId: ViabilityTestId

  @BeforeEach
  fun insertNestedData() {
    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "1"))
    bagsDao.insert(BagsRow(accessionId = accessionId1, bagNumber = "5"))
    bagsDao.insert(BagsRow(accessionId = accessionId2, bagNumber = "2"))
    bagsDao.insert(BagsRow(accessionId = accessionId2, bagNumber = "6"))

    val viabilityTestResultsRow =
        ViabilityTestsRow(
            accessionId = accessionId1,
            seedsSown = 15,
            testType = ViabilityTestType.Lab,
            totalPercentGerminated = 100,
            totalSeedsGerminated = 15,
        )

    viabilityTestsDao.insert(viabilityTestResultsRow)

    testId = viabilityTestResultsRow.id!!

    viabilityTestResultsDao.insert(
        ViabilityTestResultsRow(
            testId = testId,
            recordingDate = LocalDate.EPOCH,
            seedsGerminated = 5,
        )
    )
    viabilityTestResultsDao.insert(
        ViabilityTestResultsRow(
            testId = testId,
            recordingDate = LocalDate.EPOCH.plusDays(1),
            seedsGerminated = 10,
        )
    )
  }

  @Test
  fun `does not return empty top-level results when only selecting sublist fields`() {
    // Use searchService rather than accessionSearchService; we don't want to include the
    // accession ID/number fields because those would cause the results for accession 1001 to
    // no longer be empty.
    val result =
        searchService.search(
            rootPrefix,
            listOf(seedsTestedField),
            mapOf(rootPrefix to NoConditionNode()),
        )

    val expected =
        SearchResults(listOf(mapOf("viabilityTests" to listOf(mapOf("seedsTested" to "15")))))

    assertEquals(expected, result)
  }

  @Test
  fun `can get to accession sublists starting from organization`() {
    val orgPrefix = SearchFieldPrefix(tables.organizations)
    val fullyQualifiedField = orgPrefix.resolve("facilities.accessions.bags.number")

    val result =
        searchService.search(
            orgPrefix,
            listOf(fullyQualifiedField),
            mapOf(orgPrefix to FieldNode(fullyQualifiedField, listOf("5"))),
            listOf(SearchSortField(fullyQualifiedField)),
        )

    // Bags from both accessions appear here even though we're filtering on bag number because the
    // filter criteria determine what top-level (root prefix) results are returned, and we always
    // return the full set of data for top-level results that match the criteria.
    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "facilities" to
                        listOf(
                            mapOf(
                                "accessions" to
                                    listOf(
                                        mapOf(
                                            "bags" to
                                                listOf(
                                                    mapOf("number" to "1"),
                                                    mapOf("number" to "5"),
                                                )
                                        ),
                                        mapOf(
                                            "bags" to
                                                listOf(
                                                    mapOf("number" to "2"),
                                                    mapOf("number" to "6"),
                                                )
                                        ),
                                    )
                            )
                        )
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can select aliases for flattened sublist fields from within nested sublists`() {
    val prefix = SearchFieldPrefix(tables.facilities)
    val field = prefix.resolve("accessions.bagNumber")

    val result =
        searchService.search(
            prefix,
            listOf(field),
            mapOf(prefix to NoConditionNode()),
            listOf(SearchSortField(field)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessions" to
                        listOf(
                            mapOf("bagNumber" to "1"),
                            mapOf("bagNumber" to "2"),
                            mapOf("bagNumber" to "5"),
                            mapOf("bagNumber" to "6"),
                        )
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort by aliases for flattened sublist fields from within nested sublists`() {
    val prefix = SearchFieldPrefix(tables.facilities)
    val idField = prefix.resolve("accessions.id")
    val aliasField = prefix.resolve("accessions.bagNumber")

    val result =
        searchService.search(
            prefix,
            listOf(idField),
            mapOf(prefix to NoConditionNode()),
            listOf(SearchSortField(aliasField)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessions" to
                        listOf(
                            // 4 entries here because we're bringing a flattened sublist into the
                            // picture, even though it's only on a sort field. Unclear if this is
                            // the desirable result or not, but it's at least a predictable one.
                            mapOf("id" to "$accessionId1"),
                            mapOf("id" to "$accessionId2"),
                            mapOf("id" to "$accessionId1"),
                            mapOf("id" to "$accessionId2"),
                        )
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can get to organization from accession sublist`() {
    val viabilityTestResultsPrefix = SearchFieldPrefix(tables.viabilityTestResults)
    val rootSeedsGerminatedField = viabilityTestResultsPrefix.resolve("seedsGerminated")
    val orgNameField =
        viabilityTestResultsPrefix.resolve("viabilityTest.accession.facility.organization.name")
    val orgName = "Organization 1"

    val result =
        searchService.search(
            viabilityTestResultsPrefix,
            listOf(orgNameField, rootSeedsGerminatedField),
            mapOf(viabilityTestResultsPrefix to FieldNode(orgNameField, listOf(orgName))),
        )

    val sublistValues =
        mapOf("accession" to mapOf("facility" to mapOf("organization" to mapOf("name" to orgName))))

    val expected =
        SearchResults(
            listOf(
                mapOf("viabilityTest" to sublistValues, "seedsGerminated" to "5"),
                mapOf("viabilityTest" to sublistValues, "seedsGerminated" to "10"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can get to flattened organization from accession sublist`() {
    val viabilityTestResultsPrefix = SearchFieldPrefix(tables.viabilityTestResults)
    val rootSeedsGerminatedField = viabilityTestResultsPrefix.resolve("seedsGerminated")
    val flattenedFieldName = "viabilityTest_accession_facility_organization_name"
    val orgNameField = viabilityTestResultsPrefix.resolve(flattenedFieldName)
    val orgName = "Organization 1"

    val result =
        searchService.search(
            viabilityTestResultsPrefix,
            listOf(orgNameField, rootSeedsGerminatedField),
            mapOf(viabilityTestResultsPrefix to FieldNode(orgNameField, listOf(orgName))),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(flattenedFieldName to orgName, "seedsGerminated" to "5"),
                mapOf(flattenedFieldName to orgName, "seedsGerminated" to "10"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can filter across multiple single-value sublists`() {
    val viabilityTestResultsPrefix = SearchFieldPrefix(tables.viabilityTestResults)
    val orgNameField =
        viabilityTestResultsPrefix.resolve("viabilityTest.accession.facility.organization.name")

    val result =
        searchService.search(
            viabilityTestResultsPrefix,
            listOf(orgNameField),
            mapOf(
                viabilityTestResultsPrefix to
                    FieldNode(orgNameField, listOf("Non-matching organization name"))
            ),
        )

    val expected = SearchResults(emptyList())

    assertEquals(expected, result)
  }

  @Test
  fun `can filter on nested field`() {
    val fields = listOf(bagsNumberField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        searchAccessions(
            facilityId,
            fields,
            criteria = FieldNode(bagsNumberField, listOf("1")),
            sortOrder = sortOrder,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                    "accessionNumber" to "XYZ",
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort ascending on nested field`() {
    val fields = listOf(bagsNumberField)

    val result =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = listOf(SearchSortField(bagsNumberField)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                    "accessionNumber" to "XYZ",
                ),
                mapOf(
                    "id" to "$accessionId2",
                    "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6")),
                    "accessionNumber" to "ABCDEFG",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort on grandchild field`() {
    val fields = listOf(seedsGerminatedField)

    val result =
        searchAccessions(
            facilityId,
            fields,
            FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range),
            listOf(SearchSortField(seedsGerminatedField, SearchDirection.Descending)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("seedsGerminated" to "10"),
                                        mapOf("seedsGerminated" to "5"),
                                    )
                            )
                        ),
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort on grandchild field that is not in list of query fields`() {
    val fields = listOf(accessionNumberField)

    val result =
        searchAccessions(
            facilityId,
            fields,
            NoConditionNode(),
            listOf(SearchSortField(seedsGerminatedField, SearchDirection.Descending)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can specify duplicate sort fields`() {
    val fields = listOf(seedsGerminatedField)

    val result =
        searchAccessions(
            facilityId,
            fields,
            FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range),
            listOf(
                SearchSortField(seedsGerminatedField, SearchDirection.Descending),
                SearchSortField(seedsGerminatedField, SearchDirection.Ascending),
            ),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("seedsGerminated" to "10"),
                                        mapOf("seedsGerminated" to "5"),
                                    )
                            )
                        ),
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort descending on nested field`() {
    val fields = listOf(bagsNumberField)

    val result =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = listOf(SearchSortField(bagsNumberField, SearchDirection.Descending)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "bags" to listOf(mapOf("number" to "6"), mapOf("number" to "2")),
                    "accessionNumber" to "ABCDEFG",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "bags" to listOf(mapOf("number" to "5"), mapOf("number" to "1")),
                    "accessionNumber" to "XYZ",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort on nested enum field`() {
    val fields = listOf(seedsTestedField, testTypeField)

    viabilityTestsDao.insert(
        ViabilityTestsRow(
            accessionId = accessionId1,
            testType = ViabilityTestType.Nursery,
            seedsSown = 1,
        )
    )

    val result =
        searchAccessions(
            facilityId,
            fields,
            criteria = NoConditionNode(),
            sortOrder = listOf(SearchSortField(testTypeField)),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf("seedsTested" to "15", "type" to "Lab"),
                            mapOf("seedsTested" to "1", "type" to "Nursery"),
                        ),
                ),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `returns nested results for multiple fields`() {
    val fields = listOf(bagsNumberField, seedsGerminatedField, seedsTestedField)

    val result = searchAccessions(facilityId, fields, criteria = NoConditionNode())

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                    "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6")),
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "seedsTested" to "15",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("seedsGerminated" to "5"),
                                        mapOf("seedsGerminated" to "10"),
                                    ),
                            ),
                        ),
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `returns nested results for grandchild field when child field not queried`() {
    val fields = listOf(seedsGerminatedField)
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("seedsGerminated" to "5"),
                                        mapOf("seedsGerminated" to "10"),
                                    )
                            )
                        ),
                ),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can specify a flattened sublist as a child of a nested sublist`() {
    val field = rootPrefix.resolve("viabilityTests.viabilityTestResults_seedsGerminated")
    val fields = listOf(field)
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        searchAccessions(facilityId, fields, criteria = NoConditionNode(), sortOrder = sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf("viabilityTestResults_seedsGerminated" to "5"),
                            mapOf("viabilityTestResults_seedsGerminated" to "10"),
                        ),
                ),
                mapOf(
                    "id" to "$accessionId2",
                    "accessionNumber" to "ABCDEFG",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `cannot specify a flattened sublist with nested children`() {
    assertThrows<IllegalArgumentException> {
      rootPrefix.resolve("viabilityTests_viabilityTestResults.seedsGerminated")
    }
  }

  @Test
  fun `can sort on nested field that is not in list of query fields`() {
    val sortOrder = listOf(SearchSortField(bagsNumberField, SearchDirection.Descending))

    val result = searchAccessions(facilityId, emptyList(), NoConditionNode(), sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort on scalar field that is after nested field in field list`() {
    // We want the surrounding fields to sort in the opposite order of the nested field so we
    // can detect if the ORDER BY clause is using the wrong field.
    accessionsDao.update(
        accessionsDao
            .fetchOneById(accessionId1)!!
            .copy(number = "B", receivedDate = LocalDate.of(2020, 1, 2))
    )
    accessionsDao.update(
        accessionsDao
            .fetchOneById(accessionId2)!!
            .copy(number = "A", receivedDate = LocalDate.of(2020, 1, 1))
    )

    val selectFields = listOf(accessionNumberField, bagsNumberField, receivedDateField)
    val sortOrder =
        listOf(
            SearchSortField(receivedDateField, SearchDirection.Descending),
            SearchSortField(bagsNumberField, SearchDirection.Descending),
            SearchSortField(accessionNumberField),
        )

    val result =
        searchService.search(
            rootPrefix,
            selectFields,
            mapOf(rootPrefix to NoConditionNode()),
            sortOrder,
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "accessionNumber" to "B",
                    "bags" to
                        listOf(
                            mapOf("number" to "5"),
                            mapOf("number" to "1"),
                        ),
                    "receivedDate" to "2020-01-02",
                ),
                mapOf(
                    "accessionNumber" to "A",
                    "bags" to
                        listOf(
                            mapOf("number" to "6"),
                            mapOf("number" to "2"),
                        ),
                    "receivedDate" to "2020-01-01",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can sort on flattened field that is not in list of query fields`() {
    val sortOrder = listOf(SearchSortField(bagNumberFlattenedField, SearchDirection.Descending))

    val result = searchAccessions(facilityId, emptyList(), NoConditionNode(), sortOrder)

    val expected =
        SearchResults(
            listOf(
                // Bag 6
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
                // Bag 5
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
                // Bag 2
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
                // Bag 1
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `returns nested result for each row when querying non-nested child field`() {
    val fields = listOf(viabilityTestResultsSeedsGerminatedField, seedsGerminatedField)
    val sortOrder = fields.map { SearchSortField(it) }
    val criteria = FieldNode(seedsGerminatedField, listOf("1", "100"), SearchFilterType.Range)

    val result = searchAccessions(facilityId, fields, criteria, sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("seedsGerminated" to "5"),
                                        mapOf("seedsGerminated" to "10"),
                                    )
                            )
                        ),
                    "viabilityTests_viabilityTestResults_seedsGerminated" to "5",
                ),
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("seedsGerminated" to "5"),
                                        mapOf("seedsGerminated" to "10"),
                                    )
                            )
                        ),
                    "viabilityTests_viabilityTestResults_seedsGerminated" to "10",
                ),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `returns single-value sublists as maps rather than lists`() {
    val fields = listOf(facilityNameField)
    val sortOrder = listOf(SearchSortField(facilityNameField))
    val criteria = FieldNode(accessionNumberField, listOf("XYZ"))

    val result = searchAccessions(facilityId, fields, criteria, sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "facility" to mapOf("name" to "Facility 1"),
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can navigate up and down table hierarchy`() {
    val seedsTestedViaResults =
        rootPrefix.resolve("viabilityTests.viabilityTestResults.viabilityTest.seedsTested")
    val fields = listOf(seedsTestedViaResults)
    val sortOrder = listOf(SearchSortField(seedsTestedViaResults))
    val criteria = FieldNode(accessionNumberField, listOf("XYZ"))

    val result = searchAccessions(facilityId, fields, criteria, sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        // There are two test results. We want to make sure we
                                        // can navigate back up from them even if we don't select
                                        // any fields from them.
                                        mapOf("viabilityTest" to mapOf("seedsTested" to "15")),
                                        mapOf("viabilityTest" to mapOf("seedsTested" to "15")),
                                    )
                            )
                        ),
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can reference same field using two different paths`() {
    val seedsTestedViaResults =
        rootPrefix.resolve("viabilityTests.viabilityTestResults.viabilityTest.seedsTested")
    val fields = listOf(seedsTestedField, seedsTestedViaResults)
    val sortOrder = listOf(SearchSortField(seedsTestedViaResults))
    val criteria =
        AndNode(
            listOf(
                FieldNode(seedsTestedViaResults, listOf("15")),
                FieldNode(seedsTestedField, listOf("15")),
            )
        )

    val result = searchAccessions(facilityId, fields, criteria, sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "$accessionId1",
                    "accessionNumber" to "XYZ",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "seedsTested" to "15",
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf("viabilityTest" to mapOf("seedsTested" to "15")),
                                        mapOf("viabilityTest" to mapOf("seedsTested" to "15")),
                                    ),
                            )
                        ),
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can include a computed field and its underlying raw field in a nested sublist`() {
    val prefix = SearchFieldPrefix(tables.organizations)
    val activeField = prefix.resolve("facilities.accessions.active")
    val stateField = prefix.resolve("facilities.accessions.state")

    val result =
        searchService.search(
            prefix,
            listOf(stateField, activeField),
            mapOf(prefix to NoConditionNode()),
        )

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "facilities" to
                        listOf(
                            mapOf(
                                "accessions" to
                                    listOf(
                                        mapOf("active" to "Active", "state" to "Processing"),
                                        mapOf("active" to "Active", "state" to "In Storage"),
                                    )
                            )
                        )
                )
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `can search all the fields`() {
    val prefix = SearchFieldPrefix(tables.organizations)
    val organizationFieldNames = tables.organizations.getAllFieldNames()

    // getAllFieldNames() doesn't visit single-value sublists since they are usually parent
    // entities and we want to avoid infinite recursion. But the "user" sublist under
    // the organization users tables is a special case: a single-value sublist that
    // refers to a child, not a parent. Include it explicitly.
    val usersFieldNames = tables.users.getAllFieldNames("members.user.")

    val fields =
        (organizationFieldNames + usersFieldNames)
            .filter { !it.contains("monitoringPlots.observationPlots") }
            .sorted()
            .map { prefix.resolve(it) }

    // We're querying a mix of nested fields and the old-style fields that put nested values
    // at the top level and return a separate top-level query result for each combination of rows
    // in each child table.
    //
    // The end result is a large Cartesian product which would be hundreds of lines long if it
    // were represented explicitly as a series of nested lists and maps in Kotlin code, or if it
    // were loaded from a JSON file.
    //
    // So instead, we compute the product by iterating over the nested fields in the same order
    // as the non-nested fields appear in the `sortOrder` list, copying values from the nested
    // fields to their non-nested equivalents.

    fun Map<String, Any>.getListValue(name: String): List<Map<String, Any>>? {
      @Suppress("UNCHECKED_CAST")
      return get(name) as List<Map<String, Any>>?
    }

    fun MutableMap<String, Any>.putIfNotNull(key: String, value: Any?) {
      if (value != null) {
        put(key, value)
      }
    }

    val orgTimeZone = "Asia/Tokyo"
    val facilityTimeZone = "Africa/Nairobi"
    val userTimeZone = "America/Santiago"

    organizationsDao.update(
        organizationsDao.fetchOneById(organizationId)!!.copy(timeZone = ZoneId.of(orgTimeZone))
    )
    facilitiesDao.update(
        facilitiesDao.fetchOneById(facilityId)!!.copy(timeZone = ZoneId.of(facilityTimeZone))
    )
    usersDao.update(usersDao.fetchOneById(user.userId)!!.copy(timeZone = ZoneId.of(userTimeZone)))

    val cutTestRow =
        ViabilityTestsRow(
            accessionId = accessionId1,
            seedsCompromised = 1,
            seedsEmpty = 2,
            seedsFilled = 3,
            seedsSown = 10,
            testType = ViabilityTestType.Cut,
        )
    viabilityTestsDao.insert(cutTestRow)

    val email = usersDao.fetchOneById(user.userId)!!.email!!

    val expectedAccessions =
        listOf(
                mapOf(
                    "accessionNumber" to "ABCDEFG",
                    "active" to "Active",
                    "bags" to listOf(mapOf("number" to "2"), mapOf("number" to "6")),
                    "id" to "$accessionId2",
                    "plantsCollectedFrom" to "2",
                    "source" to "Web",
                    "speciesName" to "Other Dogwood",
                    "state" to "Processing",
                ),
                mapOf(
                    "accessionNumber" to "XYZ",
                    "active" to "Active",
                    "ageMonths" to "15",
                    "ageYears" to "1",
                    "collectedDate" to "2019-03-02",
                    "collectionSiteCity" to "city",
                    "collectionSiteCountryCode" to "UG",
                    "collectionSiteCountrySubdivision" to "subdivision",
                    "collectionSiteLandowner" to "landowner",
                    "collectionSiteName" to "siteName",
                    "collectionSiteNotes" to "siteNotes",
                    "collectionSource" to "Reintroduced",
                    "collectors" to
                        listOf(
                            mapOf("name" to "collector 1", "position" to "0"),
                            mapOf("name" to "collector 2", "position" to "1"),
                            mapOf("name" to "collector 3", "position" to "2"),
                        ),
                    "bags" to listOf(mapOf("number" to "1"), mapOf("number" to "5")),
                    "id" to "$accessionId1",
                    "plantId" to "plantId",
                    "plantsCollectedFrom" to "1",
                    "source" to "Seed Collector App",
                    "speciesName" to "Kousa Dogwood",
                    "state" to "In Storage",
                    "totalWithdrawnCount" to "6",
                    "totalWithdrawnWeightGrams" to "5,000",
                    "totalWithdrawnWeightKilograms" to "5",
                    "totalWithdrawnWeightMilligrams" to "5,000,000",
                    "totalWithdrawnWeightOunces" to "176.37",
                    "totalWithdrawnWeightPounds" to "11.0231",
                    "totalWithdrawnWeightQuantity" to "5",
                    "totalWithdrawnWeightUnits" to "Kilograms",
                    "viabilityTests" to
                        listOf(
                            mapOf(
                                "viabilityTestResults" to
                                    listOf(
                                        mapOf(
                                            "recordingDate" to "1970-01-01",
                                            "seedsGerminated" to "5",
                                        ),
                                        mapOf(
                                            "recordingDate" to "1970-01-02",
                                            "seedsGerminated" to "10",
                                        ),
                                    ),
                                "id" to "$testId",
                                "seedsTested" to "15",
                                "type" to "Lab",
                                "viabilityPercent" to "100",
                            ),
                            mapOf(
                                "id" to "${cutTestRow.id}",
                                "seedsCompromised" to "1",
                                "seedsEmpty" to "2",
                                "seedsFilled" to "3",
                                "seedsTested" to "10",
                                "type" to "Cut",
                            ),
                        ),
                ),
            )
            .flatMap { base ->
              base.getListValue("bags")?.map { bag ->
                val perBagNumber = base.toMutableMap()
                perBagNumber.putIfNotNull("bagNumber", bag["number"])
                perBagNumber
              } ?: listOf(base)
            }

    val expectedSpecies =
        listOf(
            mapOf(
                "checkedTime" to checkedTimeString,
                "commonName" to "Common 1",
                "createdTime" to createdTimeString,
                "id" to "$speciesId1",
                "modifiedTime" to modifiedTimeString,
                "rare" to "false",
                "scientificName" to "Kousa Dogwood",
            ),
            mapOf(
                "commonName" to "Common 2",
                "conservationCategory" to "EN",
                "createdTime" to createdTimeString,
                "id" to "$speciesId2",
                "modifiedTime" to modifiedTimeString,
                "rare" to "true",
                "scientificName" to "Other Dogwood",
                "seedStorageBehavior" to "Orthodox",
            ),
        )

    val expectedFacilities =
        listOf(
            mapOf(
                "accessions" to expectedAccessions,
                "connectionState" to "Not Connected",
                "createdTime" to "1970-01-01T00:00:00Z",
                "description" to "Description 1",
                "facilityNumber" to "1",
                "id" to "$facilityId",
                "name" to "Facility 1",
                "timeZone" to facilityTimeZone,
                "type" to "Seed Bank",
            )
        )

    val expectedUser =
        mapOf(
            "createdTime" to "1970-01-01T00:00:00Z",
            "email" to email,
            "firstName" to "First",
            "id" to "${user.userId}",
            "lastName" to "Last",
            "organizationMemberships" to
                listOf(
                    mapOf(
                        "createdTime" to "1970-01-01T00:00:00Z",
                        "roleName" to "Manager",
                    )
                ),
            "timeZone" to userTimeZone,
        )

    val expectedOrganizationUsers =
        listOf(
            mapOf(
                "createdTime" to "1970-01-01T00:00:00Z",
                "roleName" to "Manager",
                "user" to expectedUser,
            )
        )

    val expected =
        listOf(
            mapOf(
                "createdTime" to "1970-01-01T00:00:00Z",
                "facilities" to expectedFacilities,
                "id" to "$organizationId",
                "name" to "Organization 1",
                "members" to expectedOrganizationUsers,
                "species" to expectedSpecies,
                "timeZone" to orgTimeZone,
            )
        )

    val result = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()))

    assertNull(result.cursor)
    assertJsonEquals(expected, result.results)
  }

  @Test
  fun `flattening results separates values with line breaks`() {
    val fields =
        listOf(
            rootPrefix.resolve("id"),
            rootPrefix.resolve("collectors.name"),
            rootPrefix.resolve("collectors.position"),
        )
    val sortFields = fields.map { SearchSortField(it) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "collectors.name" to "collector 1\r\ncollector 2\r\ncollector 3",
                    "collectors.position" to "0\r\n1\r\n2",
                    "id" to "$accessionId1",
                ),
                mapOf("id" to "$accessionId2"),
            )
        )

    val result =
        searchService
            .search(rootPrefix, fields, mapOf(rootPrefix to NoConditionNode()), sortFields)
            .flattenForCsv()

    assertEquals(expected, result)
  }

  @Test
  fun `all fields are valid sort keys`() {
    val prefix = SearchFieldPrefix(tables.organizations)

    val expected = listOf(mapOf("id" to "$organizationId"))
    val searchFields = listOf(prefix.resolve("id"))

    prefix.searchTable.getAllFieldNames().forEach { fieldName ->
      val field = prefix.resolve(fieldName)
      val sortFields = listOf(SearchSortField(field))
      assertDoesNotThrow("Sort by $fieldName") {
        val result =
            searchService.search(
                prefix,
                searchFields,
                mapOf(prefix to NoConditionNode()),
                sortFields,
            )
        assertEquals(expected, result.results, "Sort by $fieldName")
      }
    }
  }

  @Test
  fun `can search a child table with a parent table field`() {
    val prefix = SearchFieldPrefix(root = bagsTable)
    val bagNumberField = prefix.resolve("number")
    val accessionNumberField = prefix.resolve("accession.accessionNumber")
    val fields = listOf(bagNumberField, accessionNumberField)
    val criteria = FieldNode(bagNumberField, listOf("5"))

    val expected =
        SearchResults(
            listOf(mapOf("number" to "5", "accession" to mapOf("accessionNumber" to "XYZ")))
        )

    val result = searchService.search(prefix, fields, mapOf(prefix to criteria))

    assertEquals(expected, result)
  }

  @Test
  fun `can search a child table without a parent`() {
    val prefix = SearchFieldPrefix(root = bagsTable)
    val bagNumberField = prefix.resolve("number")
    val fields = listOf(bagNumberField)
    val criteria = FieldNode(bagNumberField, listOf("5"))

    val expected = SearchResults(listOf(mapOf("number" to "5")))

    val result = searchService.search(prefix, fields, mapOf(prefix to criteria))

    assertEquals(expected, result)
  }

  @Test
  fun `searching a child table only returns results the user has permission to see`() {
    val prefix = SearchFieldPrefix(root = bagsTable)
    val bagNumberField = prefix.resolve("number")
    val fields = listOf(bagNumberField)
    val criteria = NoConditionNode()
    val order = listOf(SearchSortField(bagNumberField))

    // A facility in an org the user isn't in
    insertOrganization()
    val otherFacilityId = insertFacility()

    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(facilityId = otherFacilityId)
    )

    val expected = SearchResults(listOf(mapOf("number" to "2"), mapOf("number" to "6")))

    val result = searchService.search(prefix, fields, mapOf(prefix to criteria), order)

    assertEquals(expected, result)
  }

  @Test
  fun `permission check can join multiple parent tables`() {
    val prefix = SearchFieldPrefix(root = viabilityTestResultsTable)
    val seedsGerminatedField = prefix.resolve("seedsGerminated")
    val fields = listOf(seedsGerminatedField)
    val criteria = NoConditionNode()
    val order = listOf(SearchSortField(seedsGerminatedField))

    // A facility in an org the user isn't in
    insertOrganization()
    val otherFacilityId = insertFacility()

    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(facilityId = otherFacilityId)
    )

    val viabilityTestResultsRow =
        ViabilityTestsRow(
            accessionId = accessionId2,
            testType = ViabilityTestType.Lab,
            seedsSown = 50,
        )

    viabilityTestsDao.insert(viabilityTestResultsRow)
    viabilityTestResultsDao.insert(
        ViabilityTestResultsRow(
            testId = viabilityTestResultsRow.id!!,
            recordingDate = LocalDate.EPOCH,
            seedsGerminated = 8,
        )
    )

    val expected = SearchResults(listOf(mapOf("seedsGerminated" to "8")))

    val result = searchService.search(prefix, fields, mapOf(prefix to criteria), order)

    assertEquals(expected, result)
  }
}
