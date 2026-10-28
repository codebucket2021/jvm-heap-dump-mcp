package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.nio.file.Paths
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun Server.registerSessionTools() {
    registerTool(
        buildTool(
            name = "open_heap_dump",
            description = """
                Opens a JVM heap dump (.hprof) file and starts an analysis session.
                Returns a session ID that is required by all other analysis tools.
                Also returns a heap summary so you immediately know the scale of the dump.
                Call this first before using any other tool.
                By default MAT discards objects that are unreachable from GC roots; the summary reports how
                many were discarded. If that is most of the dump (e.g. GC storms caused by short-lived big
                objects in a dump taken without `live`), reopen with keep_unreachable_objects=true to query
                them. That parses the dump again in a sibling `<name>_keep_unreachable` directory (hard link,
                no copy when possible) so the normal index files are not overwritten.
            """.trimIndent(),
            schema = toolSchema(
                "path" to stringProp("Absolute path to the .hprof heap dump file"),
                "id" to stringProp("Optional custom session ID; auto-generated UUID if omitted"),
                "keep_unreachable_objects" to boolProp(
                    "Keep unreachable objects (MAT's -keep_unreachable_objects) so OQL and all queries include them",
                    default = false,
                ),
                required = listOf("path"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val path = args.getString("path")
                ?: return@runCatching errorResult("Missing required parameter: path")
            val id = args.getString("id")
            val keepUnreachable = args.getBooleanOrDefault("keep_unreachable_objects", false)

            val session = SnapshotManager.openSnapshot(Paths.get(path), id, keepUnreachable)
            val summary = MatFacade.getHeapSummary(session.snapshot)
            val discarded = MatFacade.getUnreachableSummary(session.snapshot)

            textResult(buildString {
                appendLine("## Heap Dump Opened")
                appendLine()
                appendLine("**Session ID:** `${session.id}`")
                appendLine("**File:** ${session.filePath}")
                appendLine("**Opened at:** ${DATE_FMT.format(session.openedAt)}")
                appendLine("**Unreachable objects:** ${if (keepUnreachable) "kept" else "discarded (MAT default)"}")
                appendLine()
                appendLine("### Heap Summary")
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                appendLine("| Dump file size  | ${formatSize(session.filePath.toFile().length())} |")
                appendLine("| Used heap size  | ${formatSize(summary.usedHeapSize)} |")
                appendLine("| Objects         | ${"%,d".format(summary.objectCount)} |")
                appendLine("| Classes         | ${"%,d".format(summary.classCount)} |")
                appendLine("| GC roots        | ${"%,d".format(summary.gcRootCount)} |")
                summary.jvmInfo?.let { appendLine("| JVM info        | $it |") }
                summary.snapshotDate?.let { appendLine("| Snapshot date   | $it |") }
                if (discarded != null && discarded.objectCount > 0) {
                    appendLine("| Discarded unreachable objects | ${"%,d".format(discarded.objectCount)} (${formatSize(discarded.shallowHeap)}) |")
                    appendLine()
                    appendLine(
                        "MAT discarded ${formatSize(discarded.shallowHeap)} of unreachable objects. Run `unreachable_objects` " +
                            "with run_mat_query for their class histogram, or reopen with keep_unreachable_objects=true to query them."
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "close_heap_dump",
            description = """
                Closes an open heap dump session and releases all associated memory.
                Always call this when analysis is complete to avoid memory leaks.
                The session ID becomes invalid after closing.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                required = listOf("id"),
            ),
        )
    ) { _, request ->
        runCatching {
            val id = request.arguments.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")

            SnapshotManager.closeSnapshot(id)
            textResult("Session `$id` closed successfully.")
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "list_sessions",
            description = """
                Lists all currently open heap dump sessions with their IDs, file paths, and sizes.
                Use this to discover available session IDs or check what dumps are loaded.
            """.trimIndent(),
            schema = toolSchema(),
        )
    ) { _, _ ->
        runCatching {
            val sessions = SnapshotManager.listSessions()

            if (sessions.isEmpty()) {
                return@runCatching textResult(
                    "No open sessions. Use `open_heap_dump` to load a heap dump."
                )
            }

            textResult(buildString {
                appendLine("## Open Sessions (${sessions.size})")
                appendLine()
                appendLine("| Session ID | File | Opened At | Heap Size | Unreachable Objects |")
                appendLine("|------------|------|-----------|-----------|---------------------|")
                for (s in sessions) {
                    val unreachable = if (s.keepUnreachable) "kept" else "discarded"
                    appendLine("| `${s.id}` | ${s.path} | ${s.openedAt} | ${formatSize(s.heapSizeBytes)} | $unreachable |")
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
