package me.rerere.rikkahub.data.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.plugin.webview.MusicPlayerService
import okhttp3.HttpUrl.Companion.toHttpUrl

class SharedMusicManager(
    private val context: Context,
    private val credentials: SharedMusicCredentials,
    private val api: SharedMusicApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val epoch = AtomicLong()
    private val _connection = MutableStateFlow(credentials.get())
    val connection = _connection.asStateFlow()
    private val _account = MutableStateFlow<JsonObject?>(null)
    val account = _account.asStateFlow()
    private val _queue = MutableStateFlow(MusicQueue())
    val queue = _queue.asStateFlow()
    private val _playback = MutableStateFlow<MusicPlayback?>(null)
    val playback = _playback.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var ticker: Job? = null
    private var heartbeat: Job? = null
    private var sourceObtainedAt = 0L

    init {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val snapshot = _playback.value ?: return
                        if (intent?.action == MusicPlayerService.ACTION_MUSIC_STOPPED) {
                            if (
                                snapshot.sourceUrl == intent.getStringExtra("filePath") ||
                                    snapshot.state == "loading"
                            ) {
                                scope.launch { stop() }
                            }
                        } else if (
                            snapshot.sourceUrl == MusicPlayerService.getNowPlaying()["filePath"]
                        ) {
                            _playback.value =
                                snapshot.copy(
                                    state = "loading",
                                    observedAt = System.currentTimeMillis(),
                                )
                            val endedEpoch = epoch.get()
                            scope.launch {
                                try {
                                    next(auto = true)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    if (epoch.get() == endedEpoch + 1) {
                                        _error.value = failure.message ?: "下一首暂时无法播放"
                                        stop()
                                    }
                                }
                            }
                        }
                    }
                },
                IntentFilter(MusicPlayerService.ACTION_MUSIC_COMPLETED).apply {
                    addAction(MusicPlayerService.ACTION_MUSIC_STOPPED)
                },
            )
    }

    fun isEnabled(assistantId: String): Boolean =
        assistantId in _connection.value?.assistants.orEmpty()

    fun contextFor(assistantId: String): String? =
        if (isEnabled(assistantId))
            _playback.value?.contextFor(assistantId, System.currentTimeMillis())
        else null

    suspend fun connect(server: String, accessKey: String) = operations.withLock {
        val normalized = musicServerUrl(server).toString().trimEnd('/')
        val old = _connection.value
        val connection = if (old?.server == normalized) old else api.register(normalized, accessKey)
        if (old?.token != connection.token) stop()
        withContext(Dispatchers.IO) { credentials.save(connection) }
        _connection.value = connection
        _account.value = null
        refreshAccount()
    }

    suspend fun enableAssistant(assistantId: String, enabled: Boolean) = operations.withLock {
        val current = _connection.value ?: error("请先连接音乐服务")
        val updated =
            current.copy(
                assistants =
                    if (enabled) current.assistants + assistantId
                    else current.assistants - assistantId
            )
        withContext(Dispatchers.IO) { credentials.save(updated) }
        _connection.value = updated
    }

    suspend fun refreshAccount() {
        _account.value = request(listOf("status"))
    }

    suspend fun logout() = operations.withLock {
        request(listOf("logout"), "POST")
        stop()
        _queue.value = MusicQueue()
        refreshAccount()
    }

    suspend fun forgetConnection() = operations.withLock {
        val remoteFailure =
            try {
                request(listOf("session"), "DELETE")
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
        stop()
        withContext(Dispatchers.IO) { credentials.save(null) }
        _connection.value = null
        _account.value = null
        _queue.value = MusicQueue()
        if (
            remoteFailure != null && (remoteFailure as? MusicApiException)?.code != "UNAUTHORIZED"
        ) {
            _error.value = "已断开本机连接；服务暂时不可达，未确认清除远端登录。"
        }
    }

    suspend fun qr(): JsonObject = request(listOf("login", "qr"), "POST")

    suspend fun pollQr(key: String): JsonObject =
        request(listOf("login", "qr", key)).also {
            if (it["status"]?.jsonPrimitive?.contentOrNull == "success") refreshAccount()
        }

    suspend fun search(query: String): List<MusicSong> =
        api.json.decodeFromJsonElement(
            request(listOf("search"), query = mapOf("q" to query.take(80)))["songs"]
                ?: error("没有搜索结果")
        )

    suspend fun sources(): JsonObject = request(listOf("sources"))

    suspend fun sourceSongs(id: String): List<MusicSong> =
        api.json.decodeFromJsonElement(
            request(listOf("source", id, "songs"))["songs"] ?: error("没有歌单内容")
        )

    suspend fun song(id: String): MusicSong =
        api.json.decodeFromJsonElement(request(listOf("song", id))["song"] ?: error("无法读取歌曲"))

    suspend fun refreshQueue() {
        setQueue(request(listOf("queue")))
    }

    suspend fun importPlaylist(id: String) = operations.withLock {
        setQueue(
            request(listOf("queue", "import"), "POST", buildJsonObject { put("playlistId", id) })
        )
    }

    suspend fun enqueue(song: MusicSong, next: Boolean = true) = operations.withLock {
        setQueue(
            request(
                listOf("queue", if (next) "next" else "append"),
                "POST",
                buildJsonObject {
                    put("song", api.json.encodeToJsonElement(song.copy(lyrics = emptyList())))
                },
            )
        )
    }

    suspend fun loadSource(id: String, assistantId: String, conversationId: String) {
        val turn = epoch.incrementAndGet()
        operations.withLock {
            setQueue(
                request(listOf("queue", "load"), "POST", buildJsonObject { put("source", id) })
            )
            _queue.value.items
                .firstOrNull { it.id == _queue.value.currentId }
                ?.let {
                    playResolved(it, assistantId, conversationId, true, turn)
                }
        }
    }

    suspend fun play(song: MusicSong, assistantId: String, conversationId: String) {
        val turn = epoch.incrementAndGet()
        operations.withLock {
            setQueue(
                request(
                    listOf("queue", "now"),
                    "POST",
                    buildJsonObject {
                        put("song", api.json.encodeToJsonElement(song.copy(lyrics = emptyList())))
                    },
                )
            )
            val item =
                _queue.value.items.firstOrNull { it.id == _queue.value.currentId }
                    ?: return@withLock
            playResolved(item, assistantId, conversationId, true, turn)
        }
    }

    suspend fun select(item: MusicQueueItem, assistantId: String, conversationId: String) {
        val turn = epoch.incrementAndGet()
        operations.withLock {
            setQueue(
                request(listOf("queue", "current"), "POST", buildJsonObject { put("id", item.id) })
            )
            playResolved(item, assistantId, conversationId, true, turn)
        }
    }

    suspend fun changeMode(mode: String) = operations.withLock {
        setQueue(request(listOf("queue", "mode"), "POST", buildJsonObject { put("mode", mode) }))
    }

    suspend fun move(id: String, to: Int) = operations.withLock {
        setQueue(
            request(
                listOf("queue", "move"),
                "POST",
                buildJsonObject {
                    put("id", id)
                    put("to", to)
                },
            )
        )
    }

    suspend fun remove(id: String) = operations.withLock {
        setQueue(request(listOf("queue", id), "DELETE"))
        if (_playback.value?.item?.id == id) stop()
    }

    suspend fun toggle() {
        val current = _playback.value ?: return
        if (current.state == "loading" && MusicPlayerService.isWaitingForQueue()) {
            stop()
        } else if (current.state in listOf("playing", "loading")) {
            withContext(Dispatchers.Main.immediate) { MusicPlayerService.pause(context) }
        } else if (
            current.state == "stopped" ||
                current.sourceUrl == null ||
                System.currentTimeMillis() - sourceObtainedAt > 15 * 60000
        ) {
            val turn = epoch.incrementAndGet()
            operations.withLock {
                playResolved(
                    current.item,
                    current.assistantId,
                    current.conversationId,
                    true,
                    turn,
                    current.positionMs,
                )
            }
        } else withContext(Dispatchers.Main.immediate) { MusicPlayerService.resume(context) }
    }

    suspend fun seek(positionMs: Long) =
        withContext(Dispatchers.Main.immediate) {
            val current = _playback.value ?: return@withContext
            if (current.sourceUrl == null) {
                _playback.value =
                    current.copy(
                        positionMs = positionMs.coerceIn(0, current.durationMs.coerceAtLeast(0))
                    )
                return@withContext
            }
            MusicPlayerService.seekTo(
                context,
                positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            )
        }

    suspend fun stop() {
        epoch.incrementAndGet()
        val current = _playback.value
        withContext(Dispatchers.Main.immediate) {
            if (
                current?.sourceUrl != null &&
                    (MusicPlayerService.getNowPlaying()["filePath"] == current.sourceUrl ||
                        current.state == "loading")
            ) {
                MusicPlayerService.stop(context)
            }
            _playback.value = null
            ticker?.cancel()
            ticker = null
            heartbeat?.cancel()
            heartbeat = null
        }
        if (current != null) runCatching { request(listOf("now-playing"), "DELETE") }
    }

    suspend fun next(auto: Boolean = false, previous: Boolean = false) {
        val current = _playback.value ?: return
        val turn = epoch.incrementAndGet()
        operations.withLock {
            refreshQueue()
            val items = _queue.value.items
            if (items.isEmpty()) return@withLock
            val at = items.indexOfFirst { it.id == current.item.id }.coerceAtLeast(0)
            val next =
                when {
                    auto && _queue.value.mode == "one" -> items[at]
                    _queue.value.mode == "shuffle" && items.size > 1 ->
                        items.filterIndexed { i, _ -> i != at }.random()
                    else -> items[Math.floorMod(at + if (previous) -1 else 1, items.size)]
                }
            setQueue(
                request(listOf("queue", "current"), "POST", buildJsonObject { put("id", next.id) })
            )
            playResolved(next, current.assistantId, current.conversationId, true, turn)
        }
    }

    suspend fun executeTool(
        assistantId: String,
        conversationId: String,
        input: JsonObject,
    ): List<UIMessagePart> {
        val playbackEpoch = epoch.get()
        return operations.withLock {
            check(isEnabled(assistantId)) { "此角色未开启一起听歌" }
            val current = _playback.value
            check(current == null || current.assistantId == assistantId) { "当前由其他角色陪伴听歌" }
            val action = input["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (action == "now_playing") {
                val text = contextFor(assistantId)
                return@withLock listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("action", action)
                            put("listening", text != null)
                            if (text != null) put("text", text)
                        }
                            .toString()
                    )
                )
            }
            val args =
                JsonObject(
                    input +
                        ("requestId" to
                            kotlinx.serialization.json.JsonPrimitive(
                                input["requestId"]?.jsonPrimitive?.contentOrNull
                                    ?: Uuid.random().toString()
                            ))
                )
            val result = request(listOf("tool"), "POST", args)
            var playbackWarning: String? = null
            if (action in listOf("play_next", "queue_add", "play_now")) refreshQueue()
            if (action == "play_now" && epoch.compareAndSet(playbackEpoch, playbackEpoch + 1)) {
                val item = _queue.value.items.firstOrNull { it.id == _queue.value.currentId }
                if (item != null)
                    try {
                        playResolved(
                            item,
                            assistantId,
                            conversationId,
                            current?.state in listOf("playing", "loading"),
                            playbackEpoch + 1,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        playbackWarning = failure.message ?: "播放器暂时无法打开"
                    }
            }
            val delivery = result["delivery"]?.jsonObject?.get("card") as? JsonObject
            val summary = buildJsonObject {
                (result - "delivery").forEach { (key, value) -> put(key, value) }
                if (action == "play_now") {
                    put("text", "已保存歌卡。是否已播放以当前设备播放器状态为准；待播放状态需要用户点击。")
                    put("playbackState", _playback.value?.state ?: "stopped")
                    playbackWarning?.let { put("playbackWarning", it) }
                }
            }
            buildList {
                add(UIMessagePart.Text(summary.toString()))
                if (delivery != null)
                    add(
                        UIMessagePart.Text(
                            "分享歌曲：${delivery["title"]?.jsonPrimitive?.contentOrNull.orEmpty()} — " +
                                delivery["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            metadata = buildJsonObject { put(SHARED_MUSIC_CARD, delivery) },
                        )
                    )
            }
        }
    }

    suspend fun reportFailure(block: suspend () -> Unit) {
        try {
            _error.value = null
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _error.value = failure.message ?: "音乐操作失败"
        }
    }

    private suspend fun request(
        path: List<String>,
        method: String = "GET",
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val connection = _connection.value ?: error("请先连接音乐服务")
        val result = api.request(connection, path, method, body, query)
        if (
            _connection.value?.token != connection.token ||
                _connection.value?.server != connection.server
        ) {
            throw CancellationException("Music connection changed")
        }
        return result
    }

    private fun setQueue(response: JsonObject) {
        response["queue"]?.let { _queue.value = api.json.decodeFromJsonElement(it) }
    }

    private suspend fun playResolved(
        item: MusicQueueItem,
        assistantId: String,
        conversationId: String,
        start: Boolean,
        turn: Long,
        positionMs: Long = 0,
    ) {
        val detail = song(item.songId)
        val playbackSource = if (start) request(listOf("song", item.songId, "url")) else null
        val url =
            if (start) playbackSource?.get("url")?.jsonPrimitive?.contentOrNull ?: error("无法获取播放地址")
            else null
        val trial = playbackSource?.get("trial")?.jsonPrimitive?.booleanOrNull == true
        val resumePosition = if (trial) 0 else positionMs
        if (url != null) {
            val parsed = url.toHttpUrl()
            require(parsed.isHttps && parsed.host.endsWith(".music.126.net")) { "播放地址不受支持" }
        }
        if (turn != epoch.get()) return
        withContext(Dispatchers.Main.immediate) {
            if (turn != epoch.get()) return@withContext
            val previous = _playback.value
            if (
                !start &&
                    _playback.value?.sourceUrl == MusicPlayerService.getNowPlaying()["filePath"]
            ) {
                MusicPlayerService.stop(context)
            }
            val now = System.currentTimeMillis()
            _playback.value =
                MusicPlayback(
                    assistantId,
                    conversationId,
                    item,
                    detail,
                    if (start) "loading" else "ready",
                    resumePosition,
                    detail.durationMs,
                    now,
                    sourceUrl = url,
                    trial = trial,
                )
            if (url != null) {
                sourceObtainedAt = now
                try {
                    MusicPlayerService.play(
                        context,
                        url,
                        detail.title,
                        detail.artist,
                        keepForQueue = true,
                        initialPositionMs = resumePosition,
                    )
                } catch (failure: Exception) {
                    _playback.value = previous
                    throw failure
                }
            }
            if (start) ensureTicker()
            else {
                ticker?.cancel()
                ticker = null
                heartbeat?.cancel()
                heartbeat = null
            }
        }
        if (!start) runCatching { request(listOf("now-playing"), "DELETE") }
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var ticks = 0
            var lastReportedState: String? = null
            while (_playback.value != null) {
                val current = _playback.value ?: break
                val now = System.currentTimeMillis()
                if (current.sourceUrl != null) {
                    val native = MusicPlayerService.playbackSnapshot()
                    if (native.source == current.sourceUrl) {
                        val pausedAt =
                            if (native.state == "paused") current.pausedAt ?: now else null
                        _playback.value =
                            current.copy(
                                state = native.state,
                                positionMs = native.positionMs,
                                durationMs =
                                    native.durationMs.takeIf { it > 0 } ?: current.durationMs,
                                observedAt = now,
                                pausedAt = pausedAt,
                            )
                    } else if (current.state != "loading" || now - current.observedAt > 5000) {
                        _playback.value = null
                        runCatching { request(listOf("now-playing"), "DELETE") }
                        break
                    }
                } else _playback.value = current.copy(observedAt = now)
                val playing = _playback.value
                if (
                    heartbeat?.isActive != true &&
                        playing != null &&
                        playing.state in listOf("playing", "paused") &&
                        (++ticks % 15 == 0 || playing.state != lastReportedState)
                ) {
                    lastReportedState = playing.state
                    heartbeat = scope.launch {
                        try {
                            request(
                                listOf("now-playing"),
                                "POST",
                                buildJsonObject {
                                    put("songId", playing.song.songId)
                                    put("title", playing.song.title)
                                    put("artist", playing.song.artist)
                                    put("positionMs", playing.positionMs)
                                    put("durationMs", playing.durationMs)
                                    put("playing", playing.state == "playing")
                                },
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            /* Playback and local state remain independent of a failed heartbeat. */
                        }
                    }
                }
                delay(1000)
            }
        }
    }
}
