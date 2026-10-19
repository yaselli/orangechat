package me.rerere.rikkahub.data.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import me.rerere.rikkahub.utils.JsonInstant
import java.util.Locale

@Serializable
data class MemoryGraphNode(val name: String, val type: String, val aliases: List<String> = emptyList())

@Serializable
data class MemoryGraphRelation(
    val subject: MemoryGraphNode,
    val predicate: String,
    val target: MemoryGraphNode,
    val topic: MemoryGraphNode,
    val kind: String,
    val status: String,
    val time: String = "",
) {
    fun label(): String = "${subject.name} → $predicate → ${target.name} · ${topic.name} · $kind/$status" +
        time.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

internal enum class MemoryGraphField(val explanation: String) {
    SUBJECT_NAME("subject.name 不能为空且最多40字"), SUBJECT_TYPE("subject.type 须为 user/assistant/person/thing/event/topic/place"),
    SUBJECT_ALIASES("subject.aliases 最多4个非空别名，每个最多40字"),
    TARGET_NAME("target.name 不能为空且最多40字"), TARGET_TYPE("target.type 须为 user/assistant/person/thing/event/topic/place"),
    TARGET_ALIASES("target.aliases 最多4个非空别名，每个最多40字"),
    TOPIC_NAME("topic.name 不能为空且最多40字"), TOPIC_TYPE("topic.type 必须为 topic"),
    TOPIC_ALIASES("topic.aliases 最多4个非空别名，每个最多40字"),
    PREDICATE("predicate 不能为空且最多30字"), KIND("kind 须为 preference/event/relationship/promise/fact"),
    STATUS("status 须为 asserted/negated/past"), TIME("time 最多80字"),
}

internal class MemoryGraphFieldFailure(val field: MemoryGraphField) : IllegalArgumentException(field.name)

/** Formatting only: never invent a type, relation, status, quote or substitute an unknown enum. */
internal fun normalizeMemoryGraph(relation: MemoryGraphRelation): MemoryGraphRelation {
    fun node(value: MemoryGraphNode) = value.copy(name = value.name.trim(), type = value.type.trim().lowercase(Locale.ROOT),
        aliases = value.aliases.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }.distinct().take(4))
    return relation.copy(subject = node(relation.subject), target = node(relation.target), topic = node(relation.topic),
        predicate = relation.predicate.trim(), kind = relation.kind.trim().lowercase(Locale.ROOT),
        status = relation.status.trim().lowercase(Locale.ROOT), time = relation.time.trim())
}

internal fun validateMemoryGraph(relation: MemoryGraphRelation) {
    fun field(condition: Boolean, name: MemoryGraphField) { if (!condition) throw MemoryGraphFieldFailure(name) }
    val types = setOf("user", "assistant", "person", "thing", "event", "topic", "place")
    val fields = listOf(
        Triple(MemoryGraphField.SUBJECT_NAME, MemoryGraphField.SUBJECT_TYPE, MemoryGraphField.SUBJECT_ALIASES),
        Triple(MemoryGraphField.TARGET_NAME, MemoryGraphField.TARGET_TYPE, MemoryGraphField.TARGET_ALIASES),
        Triple(MemoryGraphField.TOPIC_NAME, MemoryGraphField.TOPIC_TYPE, MemoryGraphField.TOPIC_ALIASES),
    )
    listOf(relation.subject, relation.target, relation.topic).forEachIndexed { index, node ->
        field(node.name.isNotBlank() && node.name.length <= 40, fields[index].first)
        field(node.type in types, fields[index].second)
        field(node.aliases.size <= 4 && node.aliases.all { it.isNotBlank() && it.length <= 40 }, fields[index].third)
    }
    field(relation.topic.type == "topic", MemoryGraphField.TOPIC_TYPE)
    field(relation.predicate.isNotBlank() && relation.predicate.length <= 30, MemoryGraphField.PREDICATE)
    field(relation.kind in setOf("preference", "event", "relationship", "promise", "fact"), MemoryGraphField.KIND)
    field(relation.status in setOf("asserted", "negated", "past"), MemoryGraphField.STATUS)
    field(relation.time.length <= 80, MemoryGraphField.TIME)
}

/** One graph record is one evidenced relation, never a model-created replacement for source text. */
data class MemoryGraphCandidate(
    val memoryId: Int,
    val relation: MemoryGraphRelation,
    val createdAt: Long,
)

@Serializable
internal data class MemoryGraphBuildFact(val memoryId: Int, val graph: MemoryGraphRelation?, val quote: String = "")

private fun decodeMemoryGraphBuild(raw: String, originals: Map<Int, String>): List<MemoryGraphBuildFact> {
    requireMemoryGraphBuild(raw.isNotBlank(), MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.EMPTY_OUTPUT)
    requireMemoryGraphBuild(raw.length <= 30000, MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.OVERSIZED)
    require(originals.size in 1..8)
    val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val facts = try { JsonInstant.decodeFromString<List<MemoryGraphBuildFact>>(text)
    } catch (failure: SerializationException) {
        throw MemoryGraphBuildFailure(MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.JSON_STRUCTURE, failure)
    }
    requireMemoryGraphBuild(facts.size == originals.size && facts.map { it.memoryId }.toSet() == originals.keys,
        MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.MEMORY_IDS)
    return facts
}

private fun checkedMemoryGraphFact(fact: MemoryGraphBuildFact, originals: Map<Int, String>): MemoryGraphBuildFact {
    requireMemoryGraphBuild(fact.quote.length <= 400, MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.QUOTE)
    val normalized = fact.graph?.let(::normalizeMemoryGraph)
    normalized?.let {
        try { validateMemoryGraph(it)
        } catch (failure: MemoryGraphFieldFailure) {
            throw MemoryGraphBuildFailure(MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.RELATION_FIELDS, failure)
        }
        requireMemoryGraphBuild(fact.quote.isNotBlank() && originals.getValue(fact.memoryId).contains(fact.quote),
            MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.QUOTE)
    }
    return fact.copy(graph = normalized)
}

internal data class MemoryGraphBuildInspection(
    val valid: List<MemoryGraphBuildFact>, val warnings: List<String>,
)

/** Protocol/id failures remain atomic; a bad relation cannot discard unrelated valid rows or be marked processed. */
internal fun inspectMemoryGraphBuild(raw: String, originals: Map<Int, String>): MemoryGraphBuildInspection {
    val facts = decodeMemoryGraphBuild(raw, originals)
    val valid = mutableListOf<MemoryGraphBuildFact>()
    val warnings = mutableListOf<String>()
    var firstFailure: MemoryGraphBuildFailure? = null
    for (fact in facts) {
        try { valid += checkedMemoryGraphFact(fact, originals)
        } catch (failure: MemoryGraphBuildFailure) {
            if (failure.problem !in setOf(MemoryGraphBuildProblem.RELATION_FIELDS, MemoryGraphBuildProblem.QUOTE)) throw failure
            if (firstFailure == null) firstFailure = failure
            val field = (failure.cause as? MemoryGraphFieldFailure)?.field?.explanation
                ?: "依据摘录为空、过长或与原文不一致"
            warnings += "#${fact.memoryId}：$field"
        }
    }
    if (valid.isEmpty()) throw requireNotNull(firstFailure)
    return MemoryGraphBuildInspection(valid, warnings)
}

private fun graphKey(node: MemoryGraphNode) = node.type + ":" + node.name.trim().lowercase(Locale.ROOT)

private fun mentionsGraphNode(query: String, node: MemoryGraphNode): Boolean {
    // The two participants are shared across nearly every memory; they cannot seed an archive-wide fan-out.
    if (node.type in setOf("user", "assistant")) return false
    return (listOf(node.name) + node.aliases).any { alias ->
        val name = alias.trim().lowercase(Locale.ROOT)
        if (name.codePointCount(0, name.length) < 2) false
        else if (name.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) query.contains(name)
        else Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])").containsMatchIn(query)
    }
}

/** Entity/topic linking, then at most one edge of expansion. No shared-pronoun or unbounded topic walk.
 * Query lines are ordered current-user first, then recent dialogue; the newest identifiable topic wins.
 * This is explicit-name/alias linking, not an extra language-model intent classification request.
 */
fun selectMemoryGraph(query: String, candidates: List<MemoryGraphCandidate>): List<MemoryGraphCandidate> {
    val ordered = candidates.sortedWith(compareByDescending<MemoryGraphCandidate> { it.createdAt }.thenBy { it.memoryId })
    val lines = query.lowercase(Locale.ROOT).lines().filter { it.isNotBlank() }
    for (line in lines) {
        fun relevance(candidate: MemoryGraphCandidate): Int =
            (if (mentionsGraphNode(line, candidate.relation.target)) 4 else 0) +
                (if (mentionsGraphNode(line, candidate.relation.subject)) 3 else 0) +
                (if (mentionsGraphNode(line, candidate.relation.topic)) 1 else 0)
        val direct = ordered.filter { relevance(it) > 0 }.sortedByDescending(::relevance).take(4)
        if (direct.isEmpty()) continue
        val seeds = direct.flatMap { listOf(it.relation.subject, it.relation.target) }
            .filterNot { it.type in setOf("user", "assistant", "topic") }.mapTo(hashSetOf(), ::graphKey)
        val neighbours = ordered.filter { candidate ->
            listOf(candidate.relation.subject, candidate.relation.target).any { graphKey(it) in seeds }
        }
        return (direct + neighbours).distinctBy { it.memoryId }
            .distinctBy { listOf(graphKey(it.relation.subject), it.relation.predicate, graphKey(it.relation.target),
                it.relation.kind, it.relation.status, it.relation.time) }.take(4)
    }
    return emptyList()
}

internal const val MEMORY_GRAPH_INSTRUCTIONS = """为每条明确记忆同时提取至多一条 graph 关系；没有可靠关系时 graph=null。
graph 格式：{"subject":{"name":"她","type":"user","aliases":[]},"predicate":"喜欢","target":{"name":"炸鸡","type":"thing","aliases":["香脆鸡块"]},"topic":{"name":"饮食","type":"topic","aliases":["吃饭","吃什么"]},"kind":"preference","status":"asserted","time":""}。
节点 type 只能是 user/assistant/person/thing/event/topic/place；我和她分别用 assistant/user，其他人物用 person。
name 最多40字；aliases 最多4个，每个1到40字，单字姓名合法；只写确切同义称呼或具体话题表达，不能填“你猜、嘿嘿、聊天”等通用话术。
predicate 最多30字；kind 只能是 preference/event/relationship/promise/fact；status 只能是 asserted/negated/past。
topic 必须是 type=topic 的具体主题。time 只写原文明确的时间，最多80字；未明确时留空，不能猜今天。
关系必须被该记忆及其引文支持，不把临时事件推成长期偏好，不把助手猜测当用户事实；不得推断额外关系。"""
