'use strict';
// music_tool：TA这边的听歌工具。
//   search      搜歌（用YOU的网易云登录态）
//   lyrics      读一首歌的完整歌词（带翻译），TA「听」歌的方式就是读它
//   share       把一首歌作为卡片发进对话，可以附一句话、附TA挑的几句词（requestId 幂等）
//   now_playing 看YOU现在在听什么
//   queue       看YOU的播放列表（放到哪首、什么模式、谁加的）
//   play_next   把一首歌塞到YOU正在听的那首后面（不打断，可附一句话；requestId 幂等）
//   queue_add   把一首歌加到列表最后（可附一句话；requestId 幂等）
//   play_now    直接给YOU放一首：插进列表成为当前这首，同时往对话里发一张卡。
//               YOU正在 one-earbud 里听歌就直接切过去；播放器停着时苹果不许网页自己出声，YOU点一下卡片或播放键才响。
//   删、挪、换模式都是YOU的事，queue.js 会拒绝TA。
// TA听不见声音；这里的一切都是文字：歌名、歌手、歌词、YOU放到哪一秒。

const TA_MUSIC_CAPABILITY_ID = 'music_tool';
const TA_MUSIC_CAPABILITY_CATEGORY = 'music';
const SHARE_LINES_MAX = 8;

const TA_MUSIC_INPUT_SCHEMA = Object.freeze({
  type: 'object',
  additionalProperties: false,
  required: ['action'],
  properties: {
    action: { enum: ['search', 'lyrics', 'share', 'now_playing', 'queue', 'play_next', 'queue_add', 'play_now'], description: 'play_now=直接给YOU放这首（插进列表成为当前曲、发一张卡；YOU在听着就立刻切过去，停着时YOU点一下才响，可附 note）;search=搜歌;lyrics=读一首歌的完整歌词(带翻译);share=把一首歌作为卡片发给YOU(可附一句话和几句词);now_playing=看YOU现在在听什么;queue=看YOU的播放列表;play_next=把一首歌排在YOU正在听的那首后面(不打断,可附一句话);queue_add=加到列表最后(可附一句话)。' },
    query: { type: 'string', minLength: 1, maxLength: 80, description: 'search 用：关键词，歌名/歌手都行。' },
    songId: { type: 'string', pattern: '^[0-9]{1,20}$', description: 'lyrics/share/play_next/queue_add/play_now 用：网易云歌曲 id，从 search 或 now_playing 拿。' },
    note: { type: 'string', maxLength: 300, description: 'share 可选：附在卡片上的一句话；play_next/queue_add 可选：列表里这首歌底下那行小字（最多 120 字）。' },
    lineIndexes: { type: 'array', maxItems: SHARE_LINES_MAX, items: { type: 'integer', minimum: 0 }, description: 'share 可选：附上的歌词行号（lyrics 返回的 index），最多 8 句。' },
    requestId: { type: 'string', minLength: 8, maxLength: 80, description: 'share/play_next/queue_add/play_now 可选：幂等键；重试复用同一个值就不会重复发卡或重复加歌。' }
  }
});

function musicToolError(code, message) {
  const error = new Error(message || code);
  error.code = code;
  error.knownToolOutcome = true;
  return error;
}
function fmt(ms) { const s = Math.floor((ms || 0) / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

function createTaMusicTool(options) {
  const authority = options && options.authority;
  if (!authority || typeof authority.search !== 'function') throw new TypeError('music_tool capability requires the music authority');
  const shared = new Map(); // requestId -> result（幂等，只留最近 50 个）

  async function wrap(fn) {
    try { return await fn(); }
    catch (e) {
      if (e && e.code && String(e.code).startsWith('MUSIC_')) throw musicToolError(e.code, e.message);
      throw e;
    }
  }

  return Object.freeze({
    name: TA_MUSIC_CAPABILITY_ID,
    title: '听歌',
    description: '和 YOU 一起听歌。你听不见声音，读的是歌词。search=搜歌;lyrics=读完整歌词(带翻译,每句有 index);share=把一首歌作为卡片发给YOU(可附一句话 note、附几句词 lineIndexes);now_playing=看YOU此刻在听什么、唱到哪句;queue=看YOU的播放列表;play_next=把歌排进YOU的列表、紧跟正在放的那首(不打断YOU,可附 note);queue_add=加到列表最后。我只能往后加，删和挪是YOU的。',
    sourceId: 'host-native',
    annotations: Object.freeze({ readOnlyHint: false, destructiveHint: false, idempotentHint: true, capabilityCategory: TA_MUSIC_CAPABILITY_CATEGORY }),
    inputSchema: TA_MUSIC_INPUT_SCHEMA,
    async handler(input) {
      input = input || {};
      const action = typeof input.action === 'string' ? input.action : '';
      if (action === 'search') {
        const q = typeof input.query === 'string' ? input.query.trim() : '';
        if (!q) throw musicToolError('query_required', 'search 需要 query。');
        const songs = await wrap(() => authority.search(q, 10));
        return { ok: true, action, songs: songs.map(s => ({ songId: s.songId, title: s.title, artist: s.artist, album: s.album, duration: fmt(s.durationMs) })) };
      }
      if (action === 'lyrics') {
        const songId = String(input.songId || '');
        if (!/^\d+$/.test(songId)) throw musicToolError('song_id_required', 'lyrics 需要 songId。');
        const song = await wrap(() => authority.song(songId));
        return { ok: true, action, song: { songId, title: song.title, artist: song.artist }, lines: song.lyrics.map(l => ({ index: l.index, time: fmt(l.timeMs), text: l.text, trans: l.trans || undefined })) };
      }
      if (action === 'now_playing') {
        const cur = authority.nowPlaying();
        if (!cur) return { ok: true, action, listening: false };
        return { ok: true, action, listening: true, state: cur.state, songId: cur.songId, title: cur.title, artist: cur.artist, position: fmt(cur.positionMs), duration: fmt(cur.durationMs), text: authority.renderNowPlaying() };
      }
      if (action === 'share') {
        const songId = String(input.songId || '');
        if (!/^\d+$/.test(songId)) throw musicToolError('song_id_required', 'share 需要 songId。');
        const requestId = typeof input.requestId === 'string' ? input.requestId.trim() : '';
        if (requestId && shared.has(requestId)) return shared.get(requestId);
        const song = await wrap(() => authority.song(songId));
        const idx = Array.from(new Set((Array.isArray(input.lineIndexes) ? input.lineIndexes : []).filter(n => Number.isInteger(n)))).sort((a, b) => a - b).slice(0, SHARE_LINES_MAX);
        const bad = idx.filter(i => !song.lyrics[i]);
        if (bad.length) throw musicToolError('line_index_out_of_range', '这些行号不存在：' + bad.join(', ') + '（先用 lyrics 看行号）');
        const lines = idx.map(i => ({ index: i, timeMs: song.lyrics[i].timeMs, text: song.lyrics[i].text, trans: song.lyrics[i].trans || '' }));
        const note = typeof input.note === 'string' ? input.note.trim().slice(0, 300) : '';
        const card = { kind: lines.length ? 'lyric' : 'song', songId, title: song.title, artist: song.artist, coverUrl: song.coverUrl, durationMs: song.durationMs, lines, note, from: 'ta' };
        const out = { ok: true, action, delivery: { kind: 'music', card } };
        if (requestId) { shared.set(requestId, out); if (shared.size > 50) shared.delete(shared.keys().next().value); }
        return out;
      }
      if (action === 'queue') {
        if (!authority.queue) throw musicToolError('queue_unavailable', '播放列表还没接上。');
        const q = authority.queue.get();
        const at = q.items.findIndex(x => x.id === q.currentId);
        return { ok: true, action, mode: q.mode, count: q.items.length, current: at < 0 ? null : at,
          items: q.items.slice(0, 60).map((x, i) => ({ i, songId: x.songId, title: x.title, artist: x.artist, addedBy: x.addedBy, note: x.note || undefined, playing: i === at || undefined })) };
      }
      if (action === 'play_next' || action === 'queue_add') {
        if (!authority.queue) throw musicToolError('queue_unavailable', '播放列表还没接上。');
        const songId = String(input.songId || '');
        if (!/^\d+$/.test(songId)) throw musicToolError('song_id_required', action + ' 需要 songId。');
        const requestId = typeof input.requestId === 'string' ? input.requestId.trim() : '';
        const key = action + ':' + requestId;
        if (requestId && shared.has(key)) return shared.get(key);
        const song = await wrap(() => authority.song(songId));
        const note = typeof input.note === 'string' ? input.note.trim() : '';
        const payload = { by: 'ta', song: { songId, title: song.title, artist: song.artist, coverUrl: song.coverUrl, durationMs: song.durationMs }, note };
        let r;
        try { r = action === 'play_next' ? authority.queue.playNext(payload) : authority.queue.append(payload); }
        catch (e) { throw musicToolError(String(e && e.message || 'queue_failed'), String(e && e.message || '')); }
        const pos = r.queue.items.findIndex(x => x.id === r.added[0].id);
        const cur = r.queue.items.findIndex(x => x.id === r.queue.currentId);
        const out = { ok: true, action, song: { songId, title: song.title, artist: song.artist }, position: pos, afterCurrent: cur >= 0 ? pos - cur : null, count: r.queue.items.length };
        if (requestId) { shared.set(key, out); if (shared.size > 50) shared.delete(shared.keys().next().value); }
        return out;
      }
      if (action === 'play_now') {
        if (!authority.queue) throw musicToolError('queue_unavailable', '播放列表还没接上。');
        const songId = String(input.songId || '');
        if (!/^\d+$/.test(songId)) throw musicToolError('song_id_required', 'play_now 需要 songId。');
        const requestId = typeof input.requestId === 'string' ? input.requestId.trim() : '';
        const key = action + ':' + requestId;
        if (requestId && shared.has(key)) return shared.get(key);
        const song = await wrap(() => authority.song(songId));
        const note = typeof input.note === 'string' ? input.note.trim().slice(0, 300) : '';
        let r;
        try { r = authority.queue.playNow({ by: 'ta', song: { songId, title: song.title, artist: song.artist, coverUrl: song.coverUrl, durationMs: song.durationMs }, note }); }
        catch (e) { throw musicToolError(String(e && e.message || 'queue_failed'), String(e && e.message || '')); }
        const itemId = (r.added[0] && r.added[0].id) || r.queue.currentId || '';
        const listening = typeof authority.nowPlaying === 'function' ? authority.nowPlaying() : null;
        const card = { kind: 'song', songId, title: song.title, artist: song.artist, coverUrl: song.coverUrl, durationMs: song.durationMs, lines: [], note, from: 'ta', playNow: { itemId, at: Date.now() } };
        const out = { ok: true, action, song: { songId, title: song.title, artist: song.artist },
          youWereListening: Boolean(listening && listening.state === 'playing'),
          text: listening && listening.state === 'playing' ? 'YOU 正在听歌，卡片一到就会切过去。' : 'YOU 现在没在放歌：卡片和听歌栏会摆好这首，YOU 点一下才响。',
          delivery: { kind: 'music', card } };
        if (requestId) { shared.set(key, out); if (shared.size > 50) shared.delete(shared.keys().next().value); }
        return out;
      }
      throw musicToolError('unknown_action', 'action 只支持 search / lyrics / share / now_playing / queue / play_next / queue_add / play_now。');
    }
  });
}

module.exports = { createTaMusicTool, TA_MUSIC_CAPABILITY_ID };
