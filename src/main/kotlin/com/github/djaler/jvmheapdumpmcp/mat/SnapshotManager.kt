package com.github.djaler.jvmheapdumpmcp.mat

import com.github.djaler.jvmheapdumpmcp.model.HeapSummary
import com.github.djaler.jvmheapdumpmcp.model.SessionInfo
import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.snapshot.SnapshotFactory
import org.eclipse.mat.util.IProgressListener
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

data class SessionState(
    val id: String,
    val snapshot: ISnapshot,
    val filePath: Path,
    val openedAt: Instant,
    val metadata: HeapSummary,
    val keepUnreachable: Boolean = false,
)

object SnapshotManager {

    private val log = Logger.getLogger(SnapshotManager::class.java.name)

    private val sessions = ConcurrentHashMap<String, SessionState>()

    /**
     * Opens [path] as a MAT snapshot. With [keepUnreachable], MAT keeps the objects that are no
     * longer reachable from GC roots (MAT's `-keep_unreachable_objects`) so they can be queried.
     * MAT stores its index files next to the dump and reuses them on the next open, so a dump
     * parsed that way must live in its own directory — see [keepUnreachableCopy].
     */
    fun openSnapshot(path: Path, id: String? = null, keepUnreachable: Boolean = false): SessionState {
        MatBootstrap.initialize()

        val sessionId = id ?: UUID.randomUUID().toString()
        val dump = if (keepUnreachable) keepUnreachableCopy(path) else path
        val options = if (keepUnreachable) mapOf(KEEP_UNREACHABLE_OPTION to "true") else emptyMap()
        val snapshot = SnapshotFactory.openSnapshot(dump.toFile(), options, SilentProgressListener)
        val metadata = MatFacade.getHeapSummary(snapshot)
        val state = SessionState(
            id = sessionId,
            snapshot = snapshot,
            filePath = dump,
            openedAt = Instant.now(),
            metadata = metadata,
            keepUnreachable = keepUnreachable,
        )
        sessions[sessionId] = state
        return state
    }

    fun closeSnapshot(id: String) {
        val state = sessions.remove(id)
            ?: throw IllegalArgumentException("Session not found: $id")
        SnapshotFactory.dispose(state.snapshot)
    }

    fun getSession(id: String): SessionState =
        sessions[id] ?: throw IllegalArgumentException("Session not found: $id")

    fun listSessions(): List<SessionInfo> =
        sessions.values.map { it.toSessionInfo() }

    /**
     * Returns `<dir>/<name>_keep_unreachable/<file>`, creating it as a hard link to [path] (no
     * extra disk space) or, when the file system cannot link, as a copy.
     */
    private fun keepUnreachableCopy(path: Path): Path {
        val source = path.toAbsolutePath()
        val fileName = source.fileName.toString()
        val dir = source.resolveSibling(fileName.substringBefore('.') + KEEP_UNREACHABLE_DIR_SUFFIX)
        val target = dir.resolve(fileName)
        if (Files.exists(target)) return target

        Files.createDirectories(dir)
        try {
            Files.createLink(target, source)
        } catch (e: Exception) {
            log.info("Cannot hard-link $source (${e.message}); copying it to $target")
            Files.copy(source, target)
        }
        return target
    }

    private fun SessionState.toSessionInfo() = SessionInfo(
        id = id,
        path = filePath.toString(),
        openedAt = DateTimeFormatter.ISO_INSTANT.format(openedAt),
        heapSizeBytes = metadata.usedHeapSize,
        keepUnreachable = keepUnreachable,
    )

    private const val KEEP_UNREACHABLE_OPTION = "keep_unreachable_objects"
    private const val KEEP_UNREACHABLE_DIR_SUFFIX = "_keep_unreachable"

    private object SilentProgressListener : IProgressListener {
        override fun beginTask(name: String?, totalWork: Int) {}
        override fun done() {}
        override fun isCanceled(): Boolean = false
        override fun setCanceled(value: Boolean) {}
        override fun subTask(name: String?) {}
        override fun worked(work: Int) {}
        override fun sendUserMessage(severity: IProgressListener.Severity?, message: String?, exception: Throwable?) {}
    }
}
