package me.rerere.rikkahub.data.memory

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase

interface HistoryMemoryStorage {
    suspend fun sources(scope: String, nodeIds: List<String>): List<HistoryMemorySource>
    suspend fun page(scope: String, after: String): List<HistoryMemorySource>
    suspend fun boundary(scope: String, conversationId: String, messages: List<UIMessage>): HistoryRecallBoundary
    suspend fun keywords(scope: String, query: String, boundary: HistoryRecallBoundary): List<MemorySlice>
    suspend fun indexedIds(scope: String, namespace: String): Set<String>
    suspend fun vectors(
        scope: String, namespace: String, after: String, boundary: HistoryRecallBoundary,
    ): List<HistoryMemoryVector>
    suspend fun removeOtherModels(scope: String, namespace: String)
    suspend fun putValid(vectors: List<HistoryMemoryVector>)
    suspend fun pruneExpired(scope: String, oldestMillis: Long) {}
    suspend fun refreshKeywords(scope: String): Int
}

/** All candidates come from the currently selected message, never an alternative or tool/reasoning text. */
class HistoryMemoryRepository(private val database: AppDatabase) : HistoryMemoryStorage {
    private val dao get() = database.historyMemoryDao()

    override suspend fun sources(scope: String, nodeIds: List<String>): List<HistoryMemorySource> {
        if (nodeIds.isEmpty()) return emptyList()
        return dao.sources(SimpleSQLiteQuery(
            "$SOURCE_SQL AND nodeId IN (${nodeIds.joinToString(",") { "?" }})",
            (listOf(scope) + nodeIds).toTypedArray(),
        ))
    }

    override suspend fun page(scope: String, after: String): List<HistoryMemorySource> = dao.sources(SimpleSQLiteQuery(
        "$SOURCE_SQL AND nodeId > ? ORDER BY nodeId LIMIT 64", arrayOf(scope, after),
    ))

    override suspend fun boundary(
        scope: String, conversationId: String, messages: List<UIMessage>,
    ): HistoryRecallBoundary {
        val latest = messages.last { it.role == MessageRole.USER }
        val row = dao.sources(SimpleSQLiteQuery(
            "$SOURCE_SQL AND conversationId = ? AND messageId = ? LIMIT 1",
            arrayOf(scope, conversationId, latest.id.toString()),
        )).firstOrNull()
        val firstVisible = messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .take(16).map { it.id.toString() }
        val firstIndex = if (row == null || firstVisible.isEmpty()) 0 else dao.sources(SimpleSQLiteQuery(
            "$SOURCE_SQL AND conversationId = ? AND messageId IN (${firstVisible.joinToString(",") { "?" }})",
            (listOf(scope, conversationId) + firstVisible).toTypedArray(),
        )).minOfOrNull { it.nodeIndex } ?: row.nodeIndex
        return HistoryRecallBoundary(
            conversationId, firstIndex, latest.createdAt.toString(),
            messages.mapTo(hashSetOf()) { it.id.toString() },
        )
    }

    override suspend fun keywords(scope: String, query: String, boundary: HistoryRecallBoundary): List<MemorySlice> {
        val terms = historyQueryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" || ' OR ' || ") { "jieba_query(?)" }
        val args: List<Any> = terms + listOf<Any>(
            scope, boundary.latestTime, boundary.conversationId, boundary.maxNodeIndex,
            if (boundary.oldestMillis == null) 0 else 1, (boundary.oldestMillis ?: 0L) / 1000 - 1,
        )
        val ids = mutableListOf<String>()
        database.openHelper.writableDatabase.query(
            """SELECT DISTINCT n.id FROM message_fts
                JOIN message_node n ON n.id = message_fts.node_id
                JOIN conversationentity c ON c.id = n.conversation_id
                WHERE message_fts.text MATCH ($match) AND c.assistant_id = ?
                AND json_valid(n.messages) AND n.select_index >= 0
                AND json_extract(n.messages, '$[' || n.select_index || '].id') = message_fts.message_id
                AND json_extract(n.messages, '$[' || n.select_index || '].role') IN ('user','assistant')
                AND json_extract(n.messages, '$[' || n.select_index || '].createdAt') <= ?
                AND (n.conversation_id != ? OR n.node_index < ?)
                AND (? = 0 OR CASE
                    WHEN substr(json_extract(n.messages, '$[' || n.select_index || '].createdAt'),20) GLOB '*[+-]*'
                        OR json_extract(n.messages, '$[' || n.select_index || '].createdAt') GLOB '*Z'
                    THEN CAST(strftime('%s', json_extract(n.messages, '$[' || n.select_index || '].createdAt')) AS INTEGER)
                    ELSE CAST(strftime('%s', json_extract(n.messages, '$[' || n.select_index || '].createdAt'), 'utc') AS INTEGER)
                    END >= ?)
                ORDER BY rank, n.id LIMIT 80""",
            args.toTypedArray<Any>(),
        ).use { while (it.moveToNext()) ids += it.getString(0) }
        return sources(scope, ids).filter(boundary::allows).flatMap { historyMemorySlices(it) }
    }

    override suspend fun indexedIds(scope: String, namespace: String) = dao.indexedIds(scope, namespace).toHashSet()
    override suspend fun vectors(scope: String, namespace: String, after: String, boundary: HistoryRecallBoundary) =
        dao.vectors(scope, namespace, after, boundary.conversationId, boundary.maxNodeIndex, boundary.latestTime)
    override suspend fun removeOtherModels(scope: String, namespace: String) = dao.removeOtherModels(scope, namespace)

    /** Source checks and inserts share the write transaction: network awaits cannot resurrect stale vectors. */
    override suspend fun putValid(vectors: List<HistoryMemoryVector>) = database.withTransaction {
        val scope = vectors.firstOrNull()?.scope ?: return@withTransaction
        val current = sources(scope, vectors.map { it.nodeId }.distinct()).associateBy { it.reference.key }
        val validSlices = current.mapValues { historyMemorySlices(it.value).associateBy { slice -> slice.id } }
        dao.put(vectors.filter { record ->
            val key = "${record.conversationId}/${record.nodeId}/${record.messageId}"
            val slice = validSlices[key]?.get(record.sliceId)
            slice != null && slice.sourceHash == record.sourceHash &&
                slice.start == record.start && slice.end == record.end
        })
    }

    private val lastPrune = mutableMapOf<String, Pair<Long, Long>>()
    private val pruneMutex = kotlinx.coroutines.sync.Mutex()
    private val pruneCursors = mutableMapOf<String, String>()

    override suspend fun pruneExpired(scope: String, oldestMillis: Long) {
        if (!pruneMutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val previous = lastPrune[scope]
            if (previous != null && now - previous.first < 3_600_000 && oldestMillis - previous.second < 86_400_000) return
            var after = pruneCursors[scope].orEmpty()
            // At most 256 nodes per query; a large archive must not turn local cleanup into a send-time scan.
            repeat(4) {
                currentCoroutineContext().ensureActive()
                val nodes = dao.indexedNodes(scope, after)
                if (nodes.isEmpty()) {
                    pruneCursors.remove(scope)
                    lastPrune[scope] = now to oldestMillis
                    return
                }
                database.withTransaction {
                    val current = sources(scope, nodes).associateBy { it.nodeId }
                    val expired = nodes.filter { node ->
                        val time = current[node]?.createdAt?.let(::organizationSourceMillis)
                        time == null || time < oldestMillis
                    }
                    if (expired.isNotEmpty()) dao.removeNodes(scope, expired)
                }
                after = nodes.last()
            }
            pruneCursors[scope] = after
        } finally { pruneMutex.unlock() }
    }

    /** Repairs old truncated FTS rows locally; no embedding calls and no changes to message content. */
    override suspend fun refreshKeywords(scope: String): Int = withContext(Dispatchers.IO) {
        var after = ""
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = page(scope, after)
            if (page.isEmpty()) break
            database.withTransaction {
                val fresh = sources(scope, page.map { it.nodeId })
                for (source in fresh) {
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM message_fts WHERE node_id = ? AND message_id = ?",
                        arrayOf(source.nodeId, source.messageId),
                    )
                    database.openHelper.writableDatabase.execSQL(
                        """INSERT INTO message_fts(text,node_id,message_id,conversation_id,title,update_at)
                            SELECT ?,?,?,?,title,CAST(update_at AS TEXT) FROM conversationentity
                            WHERE id = ? AND assistant_id = ?""",
                        arrayOf(source.text, source.nodeId, source.messageId, source.conversationId,
                            source.conversationId, scope),
                    )
                }
                count += fresh.size
            }
            after = page.last().nodeId
        }
        count
    }

    companion object {
        // SQLite extracts text only: base64 images, tool payloads and hidden background never enter RAM or embeddings.
        internal val SOURCE_SQL = """SELECT * FROM (
            SELECT n.id AS nodeId, n.conversation_id AS conversationId, n.node_index AS nodeIndex,
                c.assistant_id AS scope,
                json_extract(n.messages, '$[' || n.select_index || '].id') AS messageId,
                json_extract(n.messages, '$[' || n.select_index || '].role') AS role,
                json_extract(n.messages, '$[' || n.select_index || '].createdAt') AS createdAt,
                COALESCE((SELECT group_concat(json_extract(p.value, '$.text'), char(10))
                    FROM json_each(n.messages, '$[' || n.select_index || '].parts') p
                    WHERE json_extract(p.value, '$.type') = 'text'
                    AND COALESCE(json_extract(p.value, '$.metadata.orangechat_extra_info'), 0) != 1), '') AS text
            FROM message_node n JOIN conversationentity c ON c.id = n.conversation_id
            WHERE json_valid(n.messages) AND n.select_index >= 0
        ) WHERE scope = ? AND role IN ('user','assistant') AND messageId IS NOT NULL
            AND createdAt IS NOT NULL AND text != '' AND length(text) <= 64000"""
    }
}
