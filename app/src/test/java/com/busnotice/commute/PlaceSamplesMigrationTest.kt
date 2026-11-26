package com.busnotice.commute

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原始定位采样从学习结果文件搬到单独文件：不丢数据、可重复执行 */
class PlaceSamplesMigrationTest {
    /** 内存里的 SharedPreferences；failCommit 模拟写盘失败 */
    private class FakePrefs(var failCommit: Boolean = false) : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = mutableMapOf<String, Any?>(); val removes = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { removes += map.keys }
            override fun commit(): Boolean {
                if (failCommit) return false
                removes.forEach { map.remove(it) }; map.putAll(puts); return true
            }
            override fun apply() { commit() }
        }
    }

    private val raw = "[[1.0,2.0,20.0,1]]"

    @Test fun movesSamplesAndKeepsLearnedResults() {
        val src = FakePrefs().apply { map["samples"] = raw; map["clusters"] = "[]"; map["manual_HOME"] = "1.0,2.0" }
        val dst = FakePrefs()
        assertTrue(Places.moveKey(src, dst, "samples"))
        assertEquals(raw, dst.map["samples"])
        assertFalse(src.contains("samples"))
        assertEquals("[]", src.map["clusters"])
        assertEquals("1.0,2.0", src.map["manual_HOME"])
    }

    @Test fun nothingToMove() {
        val src = FakePrefs(); val dst = FakePrefs()
        assertTrue(Places.moveKey(src, dst, "samples"))
        assertNull(dst.map["samples"])
    }

    @Test fun failedWriteKeepsOldData() {
        val src = FakePrefs().apply { map["samples"] = raw }
        val dst = FakePrefs(failCommit = true)
        assertFalse(Places.moveKey(src, dst, "samples"))
        assertEquals(raw, src.map["samples"])
        // 下次再搬
        dst.failCommit = false
        assertTrue(Places.moveKey(src, dst, "samples"))
        assertEquals(raw, dst.map["samples"])
        assertFalse(src.contains("samples"))
    }

    @Test fun existingDestinationWins() {
        val newer = "[[1.0,2.0,20.0,1],[1.0,2.0,20.0,2]]"
        val src = FakePrefs().apply { map["samples"] = raw }
        val dst = FakePrefs().apply { map["samples"] = newer }
        assertTrue(Places.moveKey(src, dst, "samples"))
        assertEquals(newer, dst.map["samples"])
        assertFalse(src.contains("samples"))
    }
}
