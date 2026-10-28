package com.github.djaler.jvmheapdumpmcp.mat

import com.github.djaler.jvmheapdumpmcp.model.MatRenderOptions
import com.github.djaler.jvmheapdumpmcp.model.MatResultPart
import org.eclipse.mat.snapshot.ISnapshot
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs MAT's own queries and reports against the test heap dumps, as the MAT tools do. */
@Tag("integration")
class MatQueriesIntegrationTest {

    companion object {
        private const val SESSION_ID = "mat-queries-test"
        private lateinit var snapshot: ISnapshot
        private lateinit var dumpPath: Path
        private lateinit var baselinePath: Path

        /** Queries that cannot run from a plain command line on these dumps. */
        private val NOT_STANDALONE = setOf(
            "bundle_registry",      // needs an Eclipse/OSGi application dump
            "default_report",       // report lookups via OSGi bundles; covered by run_mat_report
            "comparison_report",
        )

        @JvmStatic
        @BeforeAll
        fun setUpAll() {
            dumpPath = resource("test-heap-dump.hprof")
            baselinePath = resource("test-heap-dump-leaked.hprof")
            snapshot = SnapshotManager.openSnapshot(dumpPath, SESSION_ID).snapshot
        }

        @JvmStatic
        @AfterAll
        fun tearDownAll() {
            SnapshotManager.closeSnapshot(SESSION_ID)
        }

        private fun resource(name: String): Path = Paths.get(
            checkNotNull(MatQueriesIntegrationTest::class.java.classLoader.getResource(name)) { "$name not found" }.toURI()
        )
    }

    private fun run(command: String, options: MatRenderOptions = MatRenderOptions(limit = 20)): List<MatResultPart> =
        MatQueries.render(snapshot, MatQueries.execute(snapshot, command, MatFacade.NoOpProgress), options)

    private fun address(className: String): String {
        val objectId = snapshot.getClassesByName(className, false).first().objectIds.first()
        return "0x" + java.lang.Long.toHexString(snapshot.mapIdToAddress(objectId))
    }

    @Test
    fun `every query without required arguments runs`() {
        val runnable = MatQueries.listQueries()
            .filter { q -> q.command !in NOT_STANDALONE && q.arguments.none { it.mandatory && it.defaultValue == null } }
        assertTrue(runnable.size >= 25, "Expected the argument-free queries, got ${runnable.map { it.command }}")
        for (query in runnable) {
            val parts = run(query.command)
            assertTrue(parts.isNotEmpty(), "${query.command} rendered nothing")
        }
    }

    @Test
    fun `queries with object arguments run`() {
        val thread = address("java.lang.Thread")
        for (command in listOf(
            "path2gc ${address("java.lang.String")}",
            "thread_details $thread",
            "thread_stack $thread",
            "group_by_value java.lang.String",
            "collection_fill_ratio java.util.HashMap",
            "hash_entries java.util.HashMap",
            "merge_shortest_paths java.lang.Thread",
            "show_retained_set java.lang.Thread",
            "immediate_dominators java.lang.String",
            "references_statistics java.lang.ref.WeakReference",
            "find_strings java.lang.String -pattern .*test-thread.*",
            "component_report java.lang.Thread",
        )) {
            val parts = run(command)
            assertTrue(parts.isNotEmpty(), "$command rendered nothing")
        }
    }

    @Test
    fun `comparison queries accept a baseline dump`() {
        val baseline = baselinePath.toString().replace('\\', '/')
        val delta = run("delta_histogram -baseline $baseline").single()
        assertTrue(delta.rows.isNotEmpty(), "delta_histogram should list class differences")
        assertTrue(run("simplecomparison -query histogram -baseline $baseline").isNotEmpty())
    }

    @Test
    fun `OQL string functions use MAT's name resolvers`() {
        val names = run("""oql "SELECT toString(t.name) FROM INSTANCEOF java.lang.Thread t"""", MatRenderOptions(limit = 100))
            .single().rows.map { it.values.single() }
        assertTrue("main" in names, "Thread names should resolve, got $names")
        assertTrue("test-thread-1" in names, "Thread names should resolve, got $names")
    }

    @Test
    fun `object results carry object ids`() {
        val part = run("""oql "SELECT * FROM java.lang.Thread"""").single()
        assertEquals("tree", part.resultType)
        assertTrue(part.rows.all { it.objectId != null }, "Object rows should have ids")
    }

    @Test
    fun `retained column, sort and filter are applied like in MAT`() {
        val part = run(
            "histogram",
            MatRenderOptions(limit = 5, retained = "APPROXIMATE", sortColumn = "Retained Heap", filters = mapOf("Class Name" to "java\\.lang\\..*")),
        ).single()
        assertTrue("Retained Heap" in part.columns, "Columns: ${part.columns}")
        assertTrue(part.rows.all { it.values[0].startsWith("java.lang.") }, "Filter not applied: ${part.rows}")
        val retained = part.rows.map { it.rawValues[part.columns.indexOf("Retained Heap")].toLong() }
        assertEquals(retained.sortedDescending(), retained, "Rows should be sorted by retained heap")
    }

    @Test
    fun `tree results expand to the requested depth`() {
        val part = run("thread_overview", MatRenderOptions(limit = 50, expandDepth = 1)).single()
        assertTrue(part.rows.any { it.depth == 1 && it.values[0].startsWith("at ") }, "Stack frames should be expanded")
    }

    @Test
    fun `leak suspects come with MAT's description`() {
        val suspects = MatFacade.getLeakSuspects(snapshot)
        assertTrue(suspects.isNotEmpty(), "The test dump has a known suspect (main thread)")
        assertTrue(suspects.first().retainedHeap > 0)
        assertTrue("java.lang.Thread" in suspects.first().description, suspects.first().description)
    }

    @Test
    fun `discarded unreachable objects are summarized`() {
        val summary = assertNotNull(MatFacade.getUnreachableSummary(snapshot))
        assertTrue(summary.objectCount > 0 && summary.shallowHeap > 0)
        assertTrue(run("unreachable_objects").single().rows.isNotEmpty())
    }

    @Test
    fun `keep_unreachable_objects keeps the discarded objects`() {
        val discarded = checkNotNull(MatFacade.getUnreachableSummary(snapshot))
        val kept = SnapshotManager.openSnapshot(dumpPath, "keep-unreachable-test", keepUnreachable = true)
        try {
            assertTrue(kept.filePath.parent.fileName.toString().endsWith("_keep_unreachable"), "${kept.filePath}")
            assertEquals(
                snapshot.snapshotInfo.numberOfObjects + discarded.objectCount,
                kept.snapshot.snapshotInfo.numberOfObjects.toLong(),
            )
            val text = MatQueries.render(
                kept.snapshot, MatQueries.execute(kept.snapshot, "unreachable_objects", MatFacade.NoOpProgress), MatRenderOptions()
            ).single().text
            assertTrue(text!!.contains("discarded no unreachable objects"), text)
        } finally {
            SnapshotManager.closeSnapshot("keep-unreachable-test")
        }
    }

    @Test
    fun `registered report queries point to run_mat_report`() {
        val e = assertFailsWith<IllegalArgumentException> { run("default_report org.eclipse.mat.api:overview") }
        assertTrue("run_mat_report" in e.message!!)
    }

    @Test
    fun `built-in and custom reports are written next to the dump`() {
        val suspects = MatReports.runReport(snapshot, "suspects", emptyMap())
        assertReport(suspects.files, "index.html")

        val overview = MatReports.runReport(snapshot, "overview", mapOf("format" to "csv"))
        assertTrue(overview.entries.any { it.endsWith(".csv") }, "CSV overview: ${overview.entries}")
        overview.files.forEach { File(it).delete() }

        val query = MatReports.runReport(snapshot, "query", mapOf("command" to "histogram", "format" to "csv"))
        assertReport(query.files, ".csv")

        val xml = Files.createTempFile("mat-report", ".xml")
        Files.writeString(
            xml,
            """<section name="Custom" xmlns="http://www.eclipse.org/mat/report.xsd"><param key="format" value="csv"/>""" +
                """<query name="threads"><command>oql "SELECT toString(t.name) AS name FROM java.lang.Thread t"</command></query></section>""",
        )
        try {
            assertReport(MatReports.runReport(snapshot, xml.toString(), emptyMap()).files, "threads")
        } finally {
            Files.delete(xml)
        }
    }

    private fun assertReport(files: List<String>, expectedEntry: String) {
        val zip = files.single { it.endsWith(".zip") }
        try {
            ZipFile(zip).use { z ->
                assertTrue(z.entries().asSequence().any { expectedEntry in it.name }, "$zip lacks $expectedEntry")
            }
        } finally {
            File(zip).delete()
        }
    }
}
