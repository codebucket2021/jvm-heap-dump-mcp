package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.MatQueries
import com.github.djaler.jvmheapdumpmcp.mat.MatReports
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import com.github.djaler.jvmheapdumpmcp.model.MatQueryInfo
import com.github.djaler.jvmheapdumpmcp.model.MatRenderOptions
import com.github.djaler.jvmheapdumpmcp.model.MatResultPart
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val MAX_TEXT_CHARS = 50_000

private val RENDER_PROPS = arrayOf(
    "expand_depth" to intProp("For tree results (dominator_tree, path2gc, thread_overview, ...): how many levels below the roots to expand", default = 0),
    "retained" to enumProp(
        "Add MAT's retained heap column to table/tree results (like 'Calculate retained size' in the MAT UI)",
        listOf("APPROXIMATE", "PRECISE"),
    ),
    "sort_by" to stringProp("Column label (or #index) to sort by; defaults to the query's own sort order"),
    "ascending" to boolProp("Sort ascending instead of descending", default = false),
    "filter" to stringProp(
        "MAT column filters, one per line as 'Column=criteria', e.g. 'Class Name=java\\.util\\..*' or 'Objects=>1000'"
    ),
)

fun Server.registerMatTools() {
    registerTool(
        buildTool(
            name = "list_mat_queries",
            description = """
                Lists every query available in Eclipse MAT (the same commands as the MAT query browser and
                `ParseHeapDump -command=`), grouped by category, with their command-line usage.
                Pass `command` to get the full help and arguments of one query before running it with run_mat_query.
            """.trimIndent(),
            schema = toolSchema(
                "filter" to stringProp("Optional regex matched against command, name and category"),
                "command" to stringProp("Show detailed help for this command, e.g. 'path2gc'"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val command = args.getString("command")
            if (command != null) {
                val query = MatQueries.describeQuery(command)
                    ?: return@runCatching errorResult("Unknown MAT query '$command'. Use list_mat_queries to see all commands.")
                return@runCatching textResult(formatQueryHelp(query))
            }

            val queries = MatQueries.listQueries(args.getString("filter"))
            textResult(buildString {
                appendLine("## MAT Queries (${queries.size})")
                for ((category, group) in queries.groupBy { it.category ?: "Other" }) {
                    appendLine()
                    appendLine("### $category")
                    appendLine("| Command | Name | Usage |")
                    appendLine("|---------|------|-------|")
                    for (q in group) {
                        appendLine("| `${q.command}` | ${q.name.cell()} | `${q.usage.cell()}` |")
                    }
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "run_mat_query",
            description = """
                Runs any Eclipse MAT query by its command line — the same syntax as MAT's command line
                (`ParseHeapDump <dump> "-command=..." org.eclipse.mat.api:query`) and the MAT query browser.
                Results are refined like in MAT (default sort, formatting); tree results can be expanded.
                Object arguments accept addresses (0x...), class names/patterns or OQL, as in MAT.
                Examples: `unreachable_objects`, `system_properties`, `top_consumers_html`,
                `path2gc 0xc0001234 -excludes java.lang.ref.Reference:referent`,
                `group_by_value java.lang.String`, `collection_fill_ratio -objects java.util.HashMap`,
                `finalizer_overview`, `duplicate_classes`, `big_drops_in_dominator_tree`, `leakhunter`.
                Use list_mat_queries to discover all commands and their arguments.
                Large results: use export_mat_query to write all rows to CSV instead.
                For OQL that matches non-ASCII text (e.g. Chinese) prefer execute_oql, which escapes it the way MAT stores it.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "command" to stringProp("MAT command line, e.g. 'histogram' or 'path2gc 0xc0001234'"),
                "limit" to intProp("Maximum rows per result part", default = 100),
                *RENDER_PROPS,
                required = listOf("id", "command"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val command = args.getString("command")
                ?: return@runCatching errorResult("Missing required parameter: command")
            val options = renderOptions(args, args.getIntOrDefault("limit", 100))

            val session = SnapshotManager.getSession(id)
            val result = MatQueries.execute(session.snapshot, command, MatFacade.NoOpProgress)
            val parts = MatQueries.render(session.snapshot, result, options)

            textResult(buildString {
                appendLine("## MAT Query `$command` — session `$id`")
                appendParts(parts, options.limit)
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "export_mat_query",
            description = """
                Runs a MAT query (any command accepted by run_mat_query, including `oql "SELECT ..."`) and writes
                ALL result rows to CSV, without putting them into the conversation. Use it for large results
                (e.g. every instance of a class) and aggregate the file with a script.
                Columns: depth (tree results), objectId, then the result columns with raw (unformatted) values.
                A result with several parts (e.g. composite results) is written to one file per part.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "command" to stringProp("MAT command line, e.g. 'oql \"SELECT ...\"' or 'histogram'"),
                "path" to stringProp("Output CSV path; defaults to <dump dir>/<dump name>_<command>.csv"),
                *RENDER_PROPS,
                required = listOf("id", "command"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val command = args.getString("command")
                ?: return@runCatching errorResult("Missing required parameter: command")
            val options = renderOptions(args, Int.MAX_VALUE)

            val session = SnapshotManager.getSession(id)
            val result = MatQueries.execute(session.snapshot, command, MatFacade.NoOpProgress)
            val parts = MatQueries.render(session.snapshot, result, options)
            val path = args.getString("path")?.let { Paths.get(it) } ?: defaultExportPath(session.filePath, command)

            val written = writeCsv(parts, path)
            textResult(buildString {
                appendLine("## Exported `$command` — session `$id`")
                appendLine()
                appendLine("| File | Rows | Columns |")
                appendLine("|------|------|---------|")
                for ((file, part) in written) {
                    appendLine("| $file | ${"%,d".format(part.rows.size)} | ${part.columns.joinToString().cell()} |")
                }
                parts.filter { it.text != null }.forEach { part ->
                    appendLine()
                    appendLine("Text part '${part.title ?: part.resultType}' (not exported):")
                    appendLine(part.text!!.take(2_000))
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "run_mat_report",
            description = """
                Generates a standard Eclipse MAT report, exactly like `ParseHeapDump <dump> <report>`:
                `suspects` (Leak Suspects), `overview` (System Overview), `top_components`,
                `query` (one command, pass `command`), or the path of your own report definition XML
                (batch of OQL queries etc.). The report is written next to the dump as a zip
                (HTML by default; format=csv/txt gives files that are easy to post-process).
                Returns the output files and the entries inside the zip.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "report" to stringProp("suspects | overview | top_components | query | <full report id> | <path to report XML>"),
                "format" to enumProp("Output format; defaults to the report definition's format (HTML for built-in reports)", listOf("html", "csv", "txt")),
                "command" to stringProp("Command for the 'query' report"),
                "limit" to intProp("Row limit for table results in the report"),
                "options" to stringProp("Further report parameters, one 'key=value' per line (e.g. 'unzip=true')"),
                required = listOf("id", "report"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val report = args.getString("report")
                ?: return@runCatching errorResult("Missing required parameter: report")

            // Like MAT's command line, only explicit options override the report definition's params.
            val options = linkedMapOf<String, String>()
            args.getString("format")?.let { options["format"] = it }
            args.getString("command")?.let { options["command"] = it }
            args.getInt("limit")?.let { options["limit"] = it.toString() }
            options.putAll(keyValueLines(args.getString("options"), "options"))

            val session = SnapshotManager.getSession(id)
            val output = MatReports.runReport(session.snapshot, report, options)

            textResult(buildString {
                appendLine("## MAT Report `$report` — session `$id`")
                appendLine()
                appendLine("**Output:**")
                output.files.forEach { appendLine("- $it") }
                if (output.entries.isNotEmpty()) {
                    appendLine()
                    appendLine("**Entries (${output.entries.size}):**")
                    output.entries.take(200).forEach { appendLine("- $it") }
                }
            })
        }.getOrElse { errorResult(it) }
    }
}

private fun renderOptions(args: JsonObject?, limit: Int) = MatRenderOptions(
    limit = limit,
    expandDepth = args.getIntOrDefault("expand_depth", 0),
    retained = args.getString("retained"),
    sortColumn = args.getString("sort_by"),
    sortAscending = args.getBooleanOrDefault("ascending", false),
    filters = keyValueLines(args.getString("filter"), "filter"),
)

private fun keyValueLines(text: String?, parameter: String): Map<String, String> =
    text.orEmpty().lines().filter { it.isNotBlank() }.associate { line ->
        val p = line.indexOf('=')
        require(p > 0) { "Invalid $parameter line '$line', expected 'key=value'" }
        line.substring(0, p).trim() to line.substring(p + 1)
    }

fun StringBuilder.appendParts(parts: List<MatResultPart>, limit: Int) {
    for (part in parts) {
        appendLine()
        part.title?.let { appendLine("### $it") }
        val text = part.text
        if (text != null) {
            appendLine(if (text.length > MAX_TEXT_CHARS) text.take(MAX_TEXT_CHARS) + "\n…(truncated)" else text)
            continue
        }
        appendLine("*${part.resultType}: ${"%,d".format(part.totalRows)} ${if (part.resultType == "tree") "top-level rows" else "rows"}*")
        appendLine()
        appendLine("| ${part.columns.joinToString(" | ") { it.cell() }} | Object ID |")
        appendLine("|${"---|".repeat(part.columns.size + 1)}")
        for (row in part.rows) {
            val values = row.values.mapIndexed { i, v -> if (i == 0 && row.depth > 0) "${"· ".repeat(row.depth)}$v" else v }
            appendLine("| ${values.joinToString(" | ") { it.cell() }} | ${row.objectId ?: ""} |")
        }
        if (part.rows.size >= limit) {
            appendLine()
            appendLine("*Showing ${part.rows.size} rows. Increase `limit` or use export_mat_query for all rows.*")
        }
    }
}

private fun formatQueryHelp(q: MatQueryInfo) = buildString {
    appendLine("## `${q.command}` — ${q.name}")
    q.category?.let { appendLine("**Category:** $it") }
    appendLine("**Usage:** `${q.usage}`")
    q.description?.let {
        appendLine()
        appendLine(MatQueries.htmlToText(it))
    }
    if (q.arguments.isNotEmpty()) {
        appendLine()
        appendLine("| Argument | Flag | Type | Required | Multiple | Default | Help |")
        appendLine("|----------|------|------|----------|----------|---------|------|")
        for (a in q.arguments) {
            appendLine(
                "| ${a.name} | ${a.flag?.let { "-$it" } ?: "(positional)"} | ${a.type} | ${a.mandatory} | ${a.multiple} | " +
                    "${a.defaultValue.orEmpty().cell()} | ${MatQueries.htmlToText(a.help.orEmpty()).cell()} |"
            )
        }
    }
}

private fun String.cell() = replace("|", "\\|").replace("\n", " ")

private fun defaultExportPath(dump: Path, command: String): Path {
    val name = command.trim().substringBefore(' ').replace(Regex("[^A-Za-z0-9_]"), "_")
    return dump.resolveSibling(dump.fileName.toString().substringBefore('.') + "_" + name + ".csv")
}

/** Writes each table/tree part to CSV; several parts get `.1.csv`, `.2.csv`, ... suffixes. */
private fun writeCsv(parts: List<MatResultPart>, path: Path): List<Pair<Path, MatResultPart>> {
    val tables = parts.filter { it.text == null }
    path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    return tables.mapIndexed { i, part ->
        val file = if (tables.size == 1) path else path.resolveSibling(path.fileName.toString().removeSuffix(".csv") + ".${i + 1}.csv")
        Files.newBufferedWriter(file).use { w ->
            val isTree = part.resultType == "tree"
            val header = (if (isTree) listOf("depth") else emptyList()) + "objectId" + part.columns
            w.write(header.joinToString(",") { it.csv() })
            w.newLine()
            for (row in part.rows) {
                val cells = (if (isTree) listOf(row.depth.toString()) else emptyList()) + (row.objectId?.toString() ?: "") + row.rawValues
                w.write(cells.joinToString(",") { it.csv() })
                w.newLine()
            }
        }
        file to part
    }
}

private fun String.csv() = if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + replace("\"", "\"\"") + "\"" else this
