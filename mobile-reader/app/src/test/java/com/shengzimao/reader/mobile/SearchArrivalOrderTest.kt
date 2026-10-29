package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.SearchArrivalOrder
import org.junit.Assert.*
import org.junit.Test

class SearchArrivalOrderTest {
    @Test fun lateHighScoreResultsDoNotMoveEarlierCards() {
        val order = SearchArrivalOrder<String>()
        assertEquals(listOf("A", "B"), order.sort(listOf("A", "B")) { it })
        assertEquals(listOf("A", "B", "C"), order.sort(listOf("C", "B", "A")) { it })
    }
    @Test fun filteringAndReturningToAllKeepsOrder() {
        val order = SearchArrivalOrder<String>()
        order.record(listOf("A", "B", "C"))
        assertEquals(listOf("B"), order.sort(listOf("B")) { it })
        assertEquals(listOf("A", "B", "C", "D"), order.sort(listOf("D", "C", "B", "A")) { it })
    }
    @Test fun newSearchDoesNotReusePreviousOrder() {
        val first = SearchArrivalOrder<String>(); first.record(listOf("A", "B"))
        assertEquals(listOf("B", "A"), SearchArrivalOrder<String>().sort(listOf("B", "A")) { it })
    }
}
