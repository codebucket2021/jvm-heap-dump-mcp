package com.github.djaler.jvmheapdumpmcp.mat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatQueriesTest {

    @Test
    fun `all MAT queries are registered from MAT's plugin descriptors`() {
        val commands = MatQueries.listQueries().map { it.command }.toSet()
        assertTrue(commands.size >= 60, "Expected MAT's full query set, got ${commands.size}: $commands")
        for (command in listOf("oql", "histogram", "dominator_tree", "path2gc", "leakhunter", "thread_overview",
            "unreachable_objects", "system_properties", "group_by_value", "collection_fill_ratio", "delta_histogram")) {
            assertTrue(command in commands, "Missing MAT query '$command'")
        }
    }

    @Test
    fun `query help lists user arguments but not context-injected ones`() {
        val path2gc = checkNotNull(MatQueries.describeQuery("path2gc"))
        assertEquals("Path To GC Roots", path2gc.name)
        assertTrue(path2gc.usage.startsWith("path2gc <object>"), path2gc.usage)

        val unreachable = checkNotNull(MatQueries.describeQuery("unreachable_objects"))
        assertTrue(unreachable.arguments.isEmpty(), "The histogram is supplied by MAT: ${unreachable.arguments}")

        val delta = checkNotNull(MatQueries.describeQuery("delta_histogram"))
        assertTrue(delta.arguments.any { it.flag == "baseline" }, "Comparison queries take -baseline: ${delta.arguments}")
    }

    @Test
    fun `built-in MAT reports are registered`() {
        val reports = MatReports.listReports().map { it.id }
        for (id in listOf("suspects", "overview", "top_components", "query")) {
            assertTrue("org.eclipse.mat.api:$id" in reports, "Missing report $id in $reports")
        }
    }

    @Test
    fun `non-ASCII literals are escaped the way MAT renders strings`() {
        assertEquals(
            """SELECT * FROM X s WHERE toString(s) LIKE ".*\\u8017\\u65f6.*" OR toString(s) = "\u8017"""",
            MatQueries.escapeNonAsciiLiterals("""SELECT * FROM X s WHERE toString(s) LIKE ".*耗时.*" OR toString(s) = "耗"""")
        )
        val ascii = """SELECT * FROM X s WHERE toString(s) LIKE ".*abc.*""""
        assertEquals(ascii, MatQueries.escapeNonAsciiLiterals(ascii))
    }

    @Test
    fun `escaped non-ASCII text is restored for display`() {
        assertEquals("共耗时3121464毫秒", MatQueries.unescapeUnicode("\\u5171\\u8017\\u65f63121464\\u6beb\\u79d2"))
        assertEquals("plain", MatQueries.unescapeUnicode("plain"))
    }

    @Test
    fun `HTML from MAT queries is converted to plain text`() {
        assertEquals("Line 1\n- a\n- b\nx &lt; y", MatQueries.htmlToText("Line 1<br><ul><li>a</li><li>b</li></ul>x &amp;lt; y"))
    }
}
