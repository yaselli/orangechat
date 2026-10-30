package me.rerere.rikkahub.data.music

/**
 * Local playback clock only. A seek, repeated chorus, resume or loop never schedules extra
 * requests.
 */
internal class MusicCommentaryPolicy {
    private var key: String? = null
    private var lastTick = 0L
    private var listeningMs = 0L
    private var previouslyPlaying = false
    private var lastAttempt: Long? = null
    private val attempted = linkedSetOf<String>()

    fun observe(playback: MusicPlayback?, enabled: Boolean, now: Long): MusicPlayback? {
        val currentKey = playback?.commentaryKey()
        if (currentKey != key) {
            key = currentKey
            listeningMs = 0
            previouslyPlaying = false
        }
        val playing =
            enabled &&
                playback?.state == "playing" &&
                !playback.trial &&
                playback.assistantId.isNotBlank() &&
                playback.conversationId.isNotBlank() &&
                now - playback.observedAt in 0..5_000
        if (playing && previouslyPlaying) listeningMs += (now - lastTick).coerceIn(0, 2_000)
        lastTick = now
        previouslyPlaying = playing
        if (!playing || playback == null || listeningMs < 45_000 || currentKey in attempted)
            return null
        if (lastAttempt?.let { now - it < 300_000 } == true) return null
        val line = playback.song.lyrics.lastOrNull { it.timeMs <= playback.positionMs }
        if (line == null || line.text.isBlank() || now - playback.observedAt !in 0..5_000)
            return null
        lastAttempt = now
        attempted += requireNotNull(currentKey)
        if (attempted.size > 100) attempted.remove(attempted.first())
        return playback
    }
}

internal fun MusicPlayback.commentaryKey(): String =
    "$assistantId/$conversationId/${song.platform}/${song.songId}"

internal fun MusicPlayback.canDeliverCommentary(cue: MusicPlayback, now: Long): Boolean =
    commentaryKey() == cue.commentaryKey() &&
        item.id == cue.item.id &&
        state == "playing" &&
        !trial &&
        now - observedAt in 0..5_000 &&
        positionMs - cue.positionMs in -2_000..90_000

internal fun musicCommentaryReply(value: String): String? {
    val text = value.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
    if (
        text.isBlank() ||
            Regex("\\[(?:PASS|WAIT|STOP|SKIP)]", RegexOption.IGNORE_CASE).containsMatchIn(text)
    )
        return null
    return text.take(500)
}
