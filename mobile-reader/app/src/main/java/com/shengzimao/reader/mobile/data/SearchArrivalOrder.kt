package com.shengzimao.reader.mobile.data

/** One instance per search revision; later responses cannot reorder cards already on screen. */
class SearchArrivalOrder<K> {
    private val order = LinkedHashMap<K, Int>()
    fun record(keys: Iterable<K>) { keys.forEach { if (it !in order) order[it] = order.size } }
    fun <T> sort(items: List<T>, key: (T) -> K): List<T> {
        record(items.map(key))
        return items.sortedBy { order[key(it)] }
    }
}
