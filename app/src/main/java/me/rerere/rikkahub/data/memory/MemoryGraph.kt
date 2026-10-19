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

internal fun validateMemoryGraph(relation: MemoryGraphRelation) {
    val types = setOf("user", "assistant", "person", "thing", "event", "topic", "place")
    listOf(relation.subject, relation.target, relation.topic).forEach { node ->
        require(node.name.isNotBlank() && node.name.length <= 40 && node.type in types)
        require(node.aliases.size <= 4 && node.aliases.all { it.length in 2..40 })
    }
    require(relation.topic.type == "topic")
    require(relation.predicate.isNotBlank() && relation.predicate.length <= 30)
    require(relation.kind in setOf("preference", "event", "relationship", "promise", "fact"))
    require(relation.status in setOf("asserted", "negated", "past"))
    require(relation.time.length <= 80)
}

/** One graph record is one evidenced relation, never a model-created replacement for source text. */
data class MemoryGraphCandidate(
    val memoryId: Int,
    val relation: MemoryGraphRelation,
    val createdAt: Long,
)

@Serializable
internal data class MemoryGraphBuildFact(val memoryId: Int, val graph: MemoryGraphRelation?, val quote: String = "")

internal fun parseMemoryGraphBuild(raw: String, originals: Map<Int, String>): List<MemoryGraphBuildFact> {
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
    facts.forEach { fact ->
        requireMemoryGraphBuild(fact.quote.length <= 400, MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.QUOTE)
        fact.graph?.let {
            try { validateMemoryGraph(it)
            } catch (failure: IllegalArgumentException) {
                throw MemoryGraphBuildFailure(MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.RELATION_FIELDS, failure)
            }
            requireMemoryGraphBuild(fact.quote.isNotBlank() && originals.getValue(fact.memoryId).contains(fact.quote),
                MemoryGraphBuildStage.OUTPUT, MemoryGraphBuildProblem.QUOTE)
        }
    }
    return facts
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
name 最多40字；aliases 最多4个，每个2到40字，是确切同义称呼或具体话题表达，不能填“你猜、嘿嘿、聊天”等通用话术。
predicate 最多30字；kind 只能是 preference/event/relationship/promise/fact；status 只能是 asserted/negated/past。
topic 必须是 type=topic 的具体主题。time 只写原文明确的时间，最多80字；未明确时留空，不能猜今天。
关系必须被该记忆及其引文支持，不把临时事件推成长期偏好，不把助手猜测当用户事实；不得推断额外关系。"""
