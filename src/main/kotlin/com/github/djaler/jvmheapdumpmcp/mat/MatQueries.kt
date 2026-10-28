package com.github.djaler.jvmheapdumpmcp.mat

import com.github.djaler.jvmheapdumpmcp.model.MatQueryArgument
import com.github.djaler.jvmheapdumpmcp.model.MatQueryInfo
import com.github.djaler.jvmheapdumpmcp.model.MatRenderOptions
import com.github.djaler.jvmheapdumpmcp.model.MatResultPart
import com.github.djaler.jvmheapdumpmcp.model.MatResultRow
import org.eclipse.mat.internal.snapshot.SnapshotQueryContext
import org.eclipse.mat.query.Bytes
import org.eclipse.mat.query.Column
import org.eclipse.mat.query.IQueryContext
import org.eclipse.mat.query.IResult
import org.eclipse.mat.query.IResultPie
import org.eclipse.mat.query.IResultTable
import org.eclipse.mat.query.IResultTree
import org.eclipse.mat.query.IStructuredResult
import org.eclipse.mat.query.annotations.Argument
import org.eclipse.mat.query.refined.Filter
import org.eclipse.mat.query.refined.RefinedResultBuilder
import org.eclipse.mat.query.refined.RefinedStructuredResult
import org.eclipse.mat.query.refined.RefinedTable
import org.eclipse.mat.query.refined.RefinedTree
import org.eclipse.mat.query.registry.ArgumentDescriptor
import org.eclipse.mat.query.registry.QueryDescriptor
import org.eclipse.mat.query.registry.QueryRegistry
import org.eclipse.mat.query.results.CompositeResult
import org.eclipse.mat.query.results.TextResult
import org.eclipse.mat.report.QuerySpec
import org.eclipse.mat.report.SectionSpec
import org.eclipse.mat.report.Spec
import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.snapshot.SnapshotInfo
import org.eclipse.mat.snapshot.UnreachableObjectsHistogram
import org.eclipse.mat.snapshot.query.SnapshotQuery
import org.eclipse.mat.util.IProgressListener
import java.text.DecimalFormat

/**
 * Runs any query registered with MAT (the commands accepted by `eclipsec -command=` and the MAT
 * query browser) and renders the result the way MAT does: tables and trees go through MAT's
 * [RefinedResultBuilder], which applies the query's default sort order, column formatting,
 * filters and the optional retained-size column.
 */
object MatQueries {

    /**
     * Argument types MAT's query context supplies itself (see SnapshotQueryContext.available);
     * they never appear on a command line. A snapshot with SECONDARY_SNAPSHOT advice is different:
     * it is the `-baseline` dump of comparison queries, given as a file path.
     */
    private val INJECTED_ARGUMENT_TYPES = setOf(
        ISnapshot::class.java,
        SnapshotInfo::class.java,
        UnreachableObjectsHistogram::class.java,
        IQueryContext::class.java,
        IProgressListener::class.java,
    )

    fun listQueries(filter: String? = null): List<MatQueryInfo> {
        MatBootstrap.initialize()
        val regex = filter?.let { Regex(it, RegexOption.IGNORE_CASE) }
        return QueryRegistry.instance().queries
            .map { it.toInfo() }
            .filter { q -> regex == null || listOf(q.command, q.name, q.category.orEmpty()).any { regex.containsMatchIn(it) } }
            .sortedWith(compareBy({ it.category.orEmpty() }, { it.command }))
    }

    fun describeQuery(command: String): MatQueryInfo? {
        MatBootstrap.initialize()
        return QueryRegistry.instance().getQuery(command.trim().substringBefore(' '))?.toInfo()
    }

    /** Executes [commandLine] (e.g. `histogram`, `path2gc 0x12345678 -excludes ""`, `oql "SELECT ..."`). */
    fun execute(snapshot: ISnapshot, commandLine: String, listener: IProgressListener): IResult? {
        // These look reports up through OSGi bundles, which standalone MAT does not have.
        val command = commandLine.trim().substringBefore(' ')
        require(command !in REGISTERED_REPORT_QUERIES) {
            "'$command' runs a registered MAT report; use run_mat_report with report=<report id> " +
                "(for comparison reports add options 'baseline=<path of the other dump>')"
        }
        // MAT only records the histogram for objects it discarded, i.e. not with keep_unreachable_objects.
        if (command == "unreachable_objects" &&
            snapshot.snapshotInfo.getProperty(UnreachableObjectsHistogram::class.java.name) == null
        ) {
            return TextResult(
                "MAT discarded no unreachable objects while parsing this dump (for example because it was opened " +
                    "with keep_unreachable_objects=true, so they are part of the snapshot). Use histogram or OQL instead."
            )
        }
        return SnapshotQuery.parse(commandLine, snapshot).execute(listener)
    }

    private val REGISTERED_REPORT_QUERIES = setOf("default_report", "comparison_report")

    /**
     * MAT's toString()/display values escape non-ASCII characters as `\uXXXX`, so a literal like
     * `"共耗时"` can never match. Rewrites non-ASCII characters in `LIKE "..."` patterns (regex: an
     * escaped backslash) and `= "..."` literals to that escaped form.
     */
    fun escapeNonAsciiLiterals(oql: String): String = OQL_STRING_COMPARISON.replace(oql) { m ->
        val (operator, space, literal) = m.destructured
        if (literal.all { it.code < 128 }) return@replace m.value
        val backslash = if (operator.equals("LIKE", ignoreCase = true)) "\\\\" else "\\"
        val escaped = literal.map { c -> if (c.code < 128) c.toString() else backslash + "u%04x".format(c.code) }
        "$operator$space\"${escaped.joinToString("")}\""
    }

    private val OQL_STRING_COMPARISON = Regex("""(?i)(LIKE|=)(\s*)"([^"]*)"""")

    /** Reverses MAT's `\uXXXX` escaping for display, so non-ASCII text (e.g. Chinese) is readable. */
    fun unescapeUnicode(text: String): String =
        if ("\\u" !in text) text else UNICODE_ESCAPE.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }

    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

    fun render(snapshot: ISnapshot, result: IResult?, options: MatRenderOptions, title: String? = null): List<MatResultPart> {
        val context = SnapshotQueryContext(snapshot)
        return renderResult(snapshot, context, result, options, title)
    }

    private fun renderResult(
        snapshot: ISnapshot,
        context: IQueryContext,
        result: IResult?,
        options: MatRenderOptions,
        title: String?,
    ): List<MatResultPart> = when (result) {
        null -> listOf(MatResultPart(title, "empty", text = "The query returned no result."))
        is SectionSpec -> renderSection(snapshot, context, result, options)
        is QuerySpec -> renderQuerySpec(snapshot, context, result, options)
        is CompositeResult -> result.resultEntries.flatMap { entry ->
            renderResult(snapshot, context, entry.result, options, entry.name ?: title)
        }
        is IResultTable, is IResultTree -> listOf(renderStructured(context, result as IStructuredResult, options, title))
        is IResultPie -> listOf(renderPie(result, options, title))
        is TextResult -> listOf(
            MatResultPart(
                title,
                if (result.isHtml) "html" else "text",
                text = unescapeUnicode(if (result.isHtml) htmlToText(result.text) else result.text),
            )
        )
        else -> listOf(MatResultPart(title, result.javaClass.simpleName, text = result.toString()))
    }

    private fun renderSection(
        snapshot: ISnapshot,
        context: IQueryContext,
        section: SectionSpec,
        options: MatRenderOptions,
    ): List<MatResultPart> = section.children.flatMap { child ->
        when (child) {
            is SectionSpec -> renderSection(snapshot, context, child, options)
            is QuerySpec -> renderQuerySpec(snapshot, context, child, options)
            else -> renderResult(snapshot, context, child as? IResult, options, child.name)
        }
    }

    /** Report parts either carry their result or the command that produces it. */
    private fun renderQuerySpec(
        snapshot: ISnapshot,
        context: IQueryContext,
        spec: QuerySpec,
        options: MatRenderOptions,
    ): List<MatResultPart> {
        val result = spec.result ?: spec.command?.let { execute(snapshot, it, MatFacade.NoOpProgress) }
        return renderResult(snapshot, context, result, options, spec.name)
    }

    private fun renderStructured(
        context: IQueryContext,
        result: IStructuredResult,
        options: MatRenderOptions,
        title: String?,
    ): MatResultPart {
        val refined = refine(context, result, options)
        val columns = refined.columns.map { it.label }
        val rows = mutableListOf<MatResultRow>()
        return when (refined) {
            is RefinedTable -> {
                for (row in refined.rows.take(options.limit)) {
                    rows += row(refined, row, columns.size, 0)
                }
                MatResultPart(title, "table", columns, rows, refined.rowCount)
            }
            is RefinedTree -> {
                val roots = refined.elements
                addTreeRows(refined, roots, 0, options, columns.size, rows)
                MatResultPart(title, "tree", columns, rows, roots.size)
            }
            else -> MatResultPart(title, refined.javaClass.simpleName, text = refined.toString())
        }
    }

    private fun addTreeRows(
        tree: RefinedTree,
        elements: List<*>,
        depth: Int,
        options: MatRenderOptions,
        columnCount: Int,
        rows: MutableList<MatResultRow>,
    ) {
        for (element in elements) {
            if (rows.size >= options.limit) return
            rows += row(tree, element, columnCount, depth)
            if (depth < options.expandDepth && tree.hasChildren(element)) {
                addTreeRows(tree, tree.getChildren(element), depth + 1, options, columnCount, rows)
            }
        }
    }

    private fun row(result: RefinedStructuredResult, row: Any?, columnCount: Int, depth: Int): MatResultRow {
        val converters = result.columns.map { it.getData(Filter.ValueConverter::class.java) as? Filter.ValueConverter }
        val formatted = (0 until columnCount).map { c -> unescapeUnicode(result.getFormattedColumnValue(row, c) ?: "") }
        val raw = (0 until columnCount).map { c ->
            unescapeUnicode(rawValue(result.getColumnValue(row, c), converters.getOrNull(c)))
        }
        val objectId = result.getContext(row)?.objectId?.takeIf { it >= 0 }
        return MatResultRow(formatted, raw, objectId, depth)
    }

    /**
     * Unformatted value, as MAT's CSV export writes it (OutputterBase.getStringValue): plain numbers,
     * with the column's value converter applied (e.g. approximate retained sizes are stored negated).
     */
    private fun rawValue(value: Any?, converter: Filter.ValueConverter?): String {
        val number: Any = when (value) {
            null -> return ""
            is Bytes -> value.value
            is Number -> value
            else -> return value.toString()
        }
        val converted = if (converter != null) converter.convert((number as Number).toDouble()) else number
        return when (converted) {
            is Long, is Int -> DecimalFormat("0").format(converted)
            is Double, is Float -> DecimalFormat("0.#####").format(converted)
            else -> converted.toString()
        }
    }

    /** Same refinement steps as MAT's report renderer (QueryPart): retained column, sort, filters. */
    private fun refine(context: IQueryContext, result: IStructuredResult, options: MatRenderOptions): RefinedStructuredResult {
        val builder = RefinedResultBuilder(context, result)
        builder.setInlineRetainedSizeCalculation(true)

        options.retained?.let { code ->
            val operation = context.contextDerivedData.derivedColumns
                .flatMap { it.operations.toList() }
                .firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown retained size mode '$code' (use APPROXIMATE or PRECISE)")
            builder.addDefaultContextDerivedColumn(operation)
        }
        options.sortColumn?.let { column ->
            val direction = if (options.sortAscending) Column.SortDirection.ASC else Column.SortDirection.DESC
            builder.setSortOrder(columnIndex(builder, column), direction)
        }
        for ((column, criteria) in options.filters) {
            builder.setFilter(columnIndex(builder, column), criteria)
        }
        return builder.build()
    }

    /** Accepts a column label (case-insensitive) or `#n`, like the `sort_column` report parameter. */
    private fun columnIndex(builder: RefinedResultBuilder, column: String): Int {
        if (column.startsWith("#")) return column.substring(1).toInt()
        val labels = builder.columns.map { it.label }
        val index = labels.indexOfFirst { it.equals(column, ignoreCase = true) }
        require(index >= 0) { "Unknown column '$column'. Columns: ${labels.joinToString()}" }
        return index
    }

    private fun renderPie(pie: IResultPie, options: MatRenderOptions, title: String?): MatResultPart {
        val slices = pie.slices
        val rows = slices.take(options.limit).map { slice ->
            MatResultRow(
                values = listOf(slice.label ?: "", "%,.0f".format(slice.value), htmlToText(slice.description ?: "")),
                rawValues = listOf(slice.label ?: "", slice.value.toLong().toString(), slice.description ?: ""),
                objectId = slice.context?.objectId?.takeIf { it >= 0 },
            )
        }
        return MatResultPart(title, "pie", listOf("Label", "Value", "Description"), rows, slices.size)
    }

    /**
     * Queries with several menu entries (e.g. path2gc: "with all references", "exclude weak
     * references", ...) are registered under the category `<order>|<menu label>` and keep the name
     * of their last entry; show the menu label instead.
     */
    private fun QueryDescriptor.toInfo(): MatQueryInfo {
        val menu = category?.let { MENU_CATEGORY.matchEntire(it)?.groupValues?.get(1) }
        return MatQueryInfo(
            command = identifier,
            name = menu ?: name,
            category = menu ?: category,
            description = shortDescription,
            usage = usage(),
            arguments = arguments.filterNot { it.isInjected() }.map { it.toInfo() },
        )
    }

    private val MENU_CATEGORY = Regex("""\d+\|(.+)""")

    /**
     * Command-line usage built from the arguments. (MAT's own getUsage caches whatever it computes
     * first, and without a snapshot context it caches just the identifier.)
     */
    private fun QueryDescriptor.usage(): String = buildString {
        append(identifier)
        for (arg in arguments.filterNot { it.isInjected() }) {
            val token = if (arg.flag.isNullOrEmpty()) "<${arg.name}>" else "-${arg.flag} <${arg.name}>"
            append(' ').append(if (arg.isMandatory) token else "[$token]")
        }
    }

    private fun ArgumentDescriptor.isInjected() =
        type in INJECTED_ARGUMENT_TYPES && !isSecondarySnapshot()

    private fun ArgumentDescriptor.isSecondarySnapshot() = advice == Argument.Advice.SECONDARY_SNAPSHOT

    private fun ArgumentDescriptor.toInfo() = MatQueryArgument(
        name = name,
        flag = flag?.takeIf { it.isNotEmpty() },
        type = if (isSecondarySnapshot()) "snapshot (path of another heap dump)" else type.simpleName,
        mandatory = isMandatory,
        multiple = isMultiple || isArray || isList,
        defaultValue = defaultValue?.let { v -> if (v is Array<*>) v.joinToString() else v.toString() },
        help = help,
    )

    /** Plain-text rendering of the small HTML fragments MAT queries produce (e.g. leak suspect descriptions). */
    fun htmlToText(html: String): String = html
        .replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h\\d>|</tr>|</div>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "- ")
        .replace(Regex("(?i)</t[dh]>"), " | ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
