package com.terraformation.backend.search

/**
 * A sublist and its parent table (possibly an alias) as it appears in field paths in a search
 * query.
 */
data class ReferencedSublist(val parentTable: SearchTable, val sublist: SublistField)
