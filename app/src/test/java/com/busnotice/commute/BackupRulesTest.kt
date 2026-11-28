package com.busnotice.commute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 系统备份规则：只备份白名单内的设置与学习结果，原始定位与运行状态永不备份 */
class BackupRulesTest {
    private fun xml(name: String): Element {
        val f = listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name")).first { it.exists() }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
    }

    /** 某节点下的 include 规则：domain/path */
    private fun includes(parent: Element): Set<String> {
        val out = mutableSetOf<String>()
        val nodes = parent.getElementsByTagName("include")
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out += e.getAttribute("domain") + "/" + e.getAttribute("path")
        }
        return out
    }

    private fun section(root: Element, tag: String) = root.getElementsByTagName(tag).item(0) as Element

    private val neverBackedUp = listOf(
        Places.SAMPLE_PREFS, "run_state", "live_snapshot", "metro_cells", "bus_sample",
        "transit_signal", "transit_a11y", "health", "diag", "geo", "ran_day", "depart_advice", "metro_route",
    )

    @Test fun cloudAndTransferUseTheSameWhitelist() {
        val root = xml("data_extraction_rules.xml")
        val cloud = includes(section(root, "cloud-backup"))
        val transfer = includes(section(root, "device-transfer"))
        assertTrue(cloud.isNotEmpty())
        assertEquals(cloud, transfer)
        assertEquals(cloud, includes(xml("backup_rules.xml")))
    }

    @Test fun onlySharedPreferencesAreBackedUp() {
        val cloud = includes(section(xml("data_extraction_rules.xml"), "cloud-backup"))
        // 不含任何文件目录：traces/ 与 metro_fp.json 不备份
        assertTrue(cloud.all { it.startsWith("sharedpref/") })
    }

    @Test fun rawLocationAndRuntimeStateAreNeverBackedUp() {
        val cloud = includes(section(xml("data_extraction_rules.xml"), "cloud-backup"))
        neverBackedUp.forEach { assertFalse(it, cloud.contains("sharedpref/$it.xml")) }
    }

    @Test fun tripsSettingsAndLearnedResultsAreBackedUp() {
        val cloud = includes(section(xml("data_extraction_rules.xml"), "cloud-backup"))
        listOf("trips", "transit", "places", "learn", "learn_ride2", "learn_bus_speed", "learn_metro", "learn_route", "walk_learn", SystemBackup.PREFS)
            .forEach { assertTrue(it, cloud.contains("sharedpref/$it.xml")) }
    }
}
