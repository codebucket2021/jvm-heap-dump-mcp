package com.github.djaler.jvmheapdumpmcp.mat

import com.github.djaler.jvmheapdumpmcp.model.MatReportInfo
import com.github.djaler.jvmheapdumpmcp.model.MatReportOutput
import org.eclipse.core.runtime.IConfigurationElement
import org.eclipse.core.runtime.RegistryFactory
import org.eclipse.mat.internal.snapshot.SnapshotQueryContext
import org.eclipse.mat.report.SectionSpec
import org.eclipse.mat.report.Spec
import org.eclipse.mat.report.SpecFactory
import org.eclipse.mat.report.TestSuite
import org.eclipse.mat.snapshot.ISnapshot
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * Runs MAT reports the way `ParseHeapDump.sh <dump> <report>` does: the built-in reports
 * (`org.eclipse.mat.api:suspects`, `:overview`, `:top_components`, `:query`, ...) or a report
 * definition XML file. Output is written next to the dump (`<dump>_<suffix>.zip`), like MAT does.
 *
 * MAT looks report definitions up through OSGi bundles, which do not exist in standalone mode,
 * so built-in definitions are read from the classpath and their `%key` labels and
 * `<template>` references are resolved here.
 */
object MatReports {

    private const val REPORT_EXTENSION_POINT = "org.eclipse.mat.report.report"

    fun listReports(): List<MatReportInfo> {
        MatBootstrap.initialize()
        // "__hidden__/" only keeps a report out of MAT's UI menus (e.g. the single-query report).
        return reportElements().map { (id, element) ->
            MatReportInfo(id, element.getAttribute("name").orEmpty().removePrefix("__hidden__/"), element.getAttribute("description"))
        }
    }

    /**
     * @param report a built-in report id (with or without the `org.eclipse.mat.api:` prefix) or the path of a report XML
     * @param options report parameters, as passed on MAT's command line (`format=csv`, `limit=100`, `command=...`)
     */
    fun runReport(snapshot: ISnapshot, report: String, options: Map<String, String>): MatReportOutput {
        MatBootstrap.initialize()
        val spec = loadSpec(report)
        spec.putAll(options)
        resolveTemplates(spec)

        val suite = TestSuite.Builder(spec).build(SnapshotQueryContext(snapshot))
        suite.execute(MatFacade.NoOpProgress)

        // MAT renders into a temporary directory first (deleted on exit); only the report files are output.
        val tmpDir = File(System.getProperty("java.io.tmpdir")).canonicalPath
        val outputs = suite.results.filter { it.isFile && !it.canonicalPath.startsWith(tmpDir) }
        val files = outputs.map { it.absolutePath }
        val entries = outputs.filter { it.name.endsWith(".zip") }.flatMap { zip ->
            ZipFile(zip).use { z -> z.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList() }
        }
        return MatReportOutput(report, files, entries)
    }

    private fun loadSpec(report: String): Spec {
        val file = File(report)
        if (file.isFile) return SpecFactory.instance().create(file)
        return loadBuiltIn(report)
            ?: throw IllegalArgumentException(
                "Unknown report '$report'. Available: ${listReports().joinToString { it.id }}, or the path of a report XML file"
            )
    }

    private fun loadBuiltIn(id: String): Spec? {
        val fullId = if (':' in id) id else "org.eclipse.mat.api:$id"
        val element = reportElements().firstOrNull { it.first == fullId }?.second ?: return null
        val bundle = element.contributor.name
        val path = element.getAttribute("file")
        val xml = MatReports::class.java.classLoader.getResourceAsStream(path)
            ?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: throw IllegalStateException("Report definition $path of $fullId is missing from the classpath")

        // Translate %key labels (section names, filename_suffix) as the bundle's localization would.
        val translated = TRANSLATABLE.replace(xml) { m ->
            m.groupValues[1] + MatBootstrap.translate(bundle, m.groupValues[2]) + m.groupValues[3]
        }
        val tmp = Files.createTempFile("mat-report-", ".xml")
        try {
            Files.writeString(tmp, translated)
            return SpecFactory.instance().create(tmp.toFile())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** `name="%key default"`, `value="%key"` and `>%key<` occurrences in a report definition. */
    private val TRANSLATABLE = Regex("""(="|>)(%[^"<]+)("|<)""")

    /** Merges `<template>` references (e.g. the overview section of the suspects report). */
    private fun resolveTemplates(spec: Spec) {
        spec.template?.let { template ->
            val other = loadBuiltIn(template)
                ?: throw IllegalArgumentException("Report template '$template' not found")
            resolveTemplates(other)
            spec.merge(other)
            spec.template = null
        }
        if (spec is SectionSpec) spec.children.forEach { resolveTemplates(it) }
    }

    private fun reportElements(): List<Pair<String, IConfigurationElement>> =
        RegistryFactory.getRegistry().getConfigurationElementsFor(REPORT_EXTENSION_POINT).map { element ->
            "${element.contributor.name}:${element.getAttribute("id")}" to element
        }
}
