package com.github.djaler.jvmheapdumpmcp.model

import kotlinx.serialization.Serializable

@Serializable
data class HeapSummary(
    val usedHeapSize: Long,
    val objectCount: Long,
    val classCount: Int,
    val gcRootCount: Int,
    val snapshotDate: String? = null,
    val jvmInfo: String? = null,
)

@Serializable
data class LeakSuspect(
    val description: String,
    val retainedHeap: Long,
    val probability: String,
    val detail: String? = null,
)

@Serializable
data class HistogramEntry(
    val className: String,
    val objectCount: Long,
    val shallowHeap: Long,
    val retainedHeap: Long,
)

@Serializable
data class DominatorEntry(
    val objectId: Int,
    val className: String,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val retainedPercent: Double,
    val label: String? = null,
)

@Serializable
data class ObjectInfo(
    val objectId: Int,
    val className: String,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val gcRootType: String? = null,
    val fields: List<FieldInfo> = emptyList(),
)

@Serializable
data class FieldInfo(
    val name: String,
    val type: String,
    val value: String,
    val objectId: Int? = null,
)

@Serializable
data class ReferenceInfo(
    val objectId: Int,
    val className: String,
    val fieldName: String?,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val label: String? = null,
)

@Serializable
data class GcRootPath(
    val steps: List<GcRootStep>,
)

@Serializable
data class GcRootStep(
    val objectId: Int,
    val className: String,
    val fieldName: String?,
    val shallowHeap: Long,
    val gcRootType: String? = null,
)

@Serializable
data class ThreadInfo(
    val objectId: Int,
    val name: String,
    val retainedHeap: Long,
    val shallowHeap: Long,
    val stackFrames: List<String> = emptyList(),
)

@Serializable
data class OqlResult(
    val query: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean = false,
)

@Serializable
data class StringInfo(
    val objectId: Int,
    val value: String,
    val retainedHeap: Long,
)

@Serializable
data class SessionInfo(
    val id: String,
    val path: String,
    val openedAt: String,
    val heapSizeBytes: Long,
    val keepUnreachable: Boolean = false,
)

/** Objects MAT discarded as unreachable while parsing (empty when they were kept). */
@Serializable
data class UnreachableSummary(
    val objectCount: Long,
    val shallowHeap: Long,
)

/** A MAT query as registered in its query registry (the commands of `eclipsec -command=`). */
@Serializable
data class MatQueryInfo(
    val command: String,
    val name: String,
    val category: String?,
    val description: String?,
    val usage: String,
    val arguments: List<MatQueryArgument> = emptyList(),
)

@Serializable
data class MatQueryArgument(
    val name: String,
    val flag: String?,
    val type: String,
    val mandatory: Boolean,
    val multiple: Boolean,
    val defaultValue: String?,
    val help: String?,
)

/** How to refine a table/tree result — mirrors the rendering params of MAT reports. */
data class MatRenderOptions(
    val limit: Int = 100,
    val expandDepth: Int = 0,
    val retained: String? = null,
    val sortColumn: String? = null,
    val sortAscending: Boolean = false,
    val filters: Map<String, String> = emptyMap(),
)

/**
 * One rendered part of a MAT query result. Tables and trees fill [columns]/[rows]; text and HTML
 * results fill [text]. Composite results and report sections produce several parts.
 */
@Serializable
data class MatResultPart(
    val title: String?,
    val resultType: String,
    val columns: List<String> = emptyList(),
    val rows: List<MatResultRow> = emptyList(),
    val totalRows: Int = 0,
    val text: String? = null,
)

@Serializable
data class MatResultRow(
    val values: List<String>,
    val rawValues: List<String>,
    val objectId: Int?,
    val depth: Int = 0,
)

@Serializable
data class MatReportInfo(
    val id: String,
    val name: String,
    val description: String?,
)

@Serializable
data class MatReportOutput(
    val report: String,
    val files: List<String>,
    val entries: List<String>,
)

@Serializable
data class ArrayInspection(
    val objectId: Int,
    val className: String,
    val length: Int,
    val elementType: String,
    val elements: List<String>,
    val offset: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class ClassInstanceEntry(
    val objectId: Int,
    val className: String,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val label: String? = null,
)

@Serializable
data class CollectionFillRateResult(
    val className: String,
    val totalCount: Int,
    val buckets: List<FillRateBucket>,
)

@Serializable
data class FillRateBucket(
    val rangeLabel: String,
    val count: Int,
    val totalShallowHeap: Long,
    val totalWastedHeap: Long,
)

@Serializable
data class MapContentsResult(
    val objectId: Int,
    val className: String,
    val entryCount: Int,
    val totalRetainedHeap: Long,
    val keyTypeSummary: List<TypeSummary>,
    val valueTypeSummary: List<TypeSummary>,
    val topEntries: List<MapEntryInfo>,
)

@Serializable
data class TypeSummary(
    val className: String,
    val count: Int,
    val totalRetainedHeap: Long,
)

@Serializable
data class MapEntryInfo(
    val keyObjectId: Int,
    val keyClassName: String,
    val keyLabel: String?,
    val valueObjectId: Int,
    val valueClassName: String,
    val valueRetainedHeap: Long,
)

@Serializable
data class HistogramDiffEntry(
    val className: String,
    val objectCountDelta: Long,
    val shallowHeapDelta: Long,
    val objectCount1: Long,
    val objectCount2: Long,
)

@Serializable
data class ThreadLocalInfo(
    val threadLocalObjectId: Int?,
    val threadLocalClassName: String?,
    val valueObjectId: Int,
    val valueClassName: String,
    val valueRetainedHeap: Long,
    val valueLabel: String?,
)
