package com.cashflow.app.backup

/**
 * The small settings stores of the app (auto-accept and per-series flags, notification settings, hints) as plain data:
 * store name to (key to value). Values are Boolean, Int, Long, String or Set<String>.
 */
data class SettingsSnapshot(val stores: Map<String, Map<String, Any>>)

object SettingsJson {
    fun encode(s: SettingsSnapshot): String = MiniJson.write(
        s.stores.mapValues { (_, entries) ->
            entries.mapValues { (_, v) ->
                when (v) {
                    is Boolean -> linkedMapOf("t" to "b", "v" to v)
                    is Int -> linkedMapOf("t" to "i", "v" to v.toLong())
                    is Long -> linkedMapOf("t" to "l", "v" to v)
                    is Set<*> -> linkedMapOf("t" to "s", "v" to v.map { it.toString() }.sorted())
                    else -> linkedMapOf("t" to "t", "v" to v.toString())
                }
            }
        },
    )

    fun decode(text: String): SettingsSnapshot? {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return null
        val stores = LinkedHashMap<String, Map<String, Any>>()
        for ((store, entries) in root) {
            val map = LinkedHashMap<String, Any>()
            for ((key, typed) in entries as? Map<*, *> ?: return null) {
                val t = typed as? Map<*, *> ?: return null
                val v = t["v"]
                map[key.toString()] = when (t["t"]) {
                    "b" -> v as? Boolean ?: return null
                    "i" -> (v as? Long)?.toInt() ?: return null
                    "l" -> v as? Long ?: return null
                    "s" -> (v as? List<*>)?.map { it.toString() }?.toSet() ?: return null
                    "t" -> v as? String ?: return null
                    else -> return null
                }
            }
            stores[store.toString()] = map
        }
        return SettingsSnapshot(stores)
    }
}
