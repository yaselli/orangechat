'use strict';
const RADAR_PLAYLIST_ID = '3136952023'; // 网易云「私人雷达」，同一个 id，内容按登录的人给
// one-earbud 听歌 · 业务 Authority：把网易云客户端、YOU的登录态、「正在听」拼在一起。
// 前端路由和TA的 music_tool 工具都只走这里，不各自连网易云。

const netease = require('./netease');
const { createMusicQueueStore } = require('./queue');
const { createMusicAuthStore } = require('./store');
const { createNowPlaying } = require('./now-playing');

const YOU_NAME = process.env.ONE_EARBUD_YOU_NAME || 'YOU';
function fmt(ms) { const s = Math.floor((ms || 0) / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

function createMusicAuthority(options) {
  options = options || {};
  const client = options.client || netease;
  const store = options.store || createMusicAuthStore(options);
  const nowPlaying = options.nowPlaying || createNowPlaying(options);
  const queue = options.queue || createMusicQueueStore(options.queueFile ? { file: options.queueFile } : undefined);
  let uidCache = '';
  async function myUid() {
    const st = store.publicState();
    const saved = st && st.profile && String(st.profile.userId || '');
    if (/^\d+$/.test(saved)) return saved;
    if (uidCache) return uidCache;
    const acc = await client.account(store.cookie());
    uidCache = String(acc.profile && acc.profile.userId || '');
    return uidCache;
  }
  const now = options.now || (() => Date.now());
  const lyricCache = new Map(); // songId -> lines，只留最近 20 首
  const pendingQr = new Map();  // key -> expiresAt

  async function withCookie(fn) {
    const cookie = store.cookie();
    const out = await fn(cookie);
    if (out && out.cookie) { store.refreshCookie(out.cookie); delete out.cookie; }
    return out;
  }
  async function lyricsCached(songId) {
    if (lyricCache.has(songId)) return lyricCache.get(songId);
    const r = await client.lyrics(store.cookie(), songId);
    lyricCache.set(songId, r.lines);
    if (lyricCache.size > 20) lyricCache.delete(lyricCache.keys().next().value);
    return r.lines;
  }

  return {
    status() { return store.publicState(); },
    // 播放列表：YOU和TA共用的一份。规矩在 queue.js 里（TA只能往后加）。
    queue,
    // 导入：YOU的歌单。账号 id 早先存成了空的，缺就现拿一次，放内存里。
    async playlists() {
      const uid = await myUid();
      const once = () => withCookie(async c => ({ list: await client.userPlaylists(c, uid) })).then(r => r.list);
      // 网易云偶尔断一下，悄悄再试一次，YOU就察觉不到
      try { return await once(); } catch (e) { if (e && (e.code === 'MUSIC_UPSTREAM_ERROR' || e.code === 'MUSIC_TIMEOUT')) return once(); throw e; }
    },
    async importPlaylist(playlistId, opts) {
      const songs = await withCookie(async c => ({ songs: await client.playlistSongs(c, playlistId, 500) })).then(r => r.songs);
      if (opts && opts.replace) queue.clear({ by: 'you' });
      const room = 500 - queue.get().items.length;
      const take = songs.slice(0, Math.max(0, room)).map(x => ({ songId: x.songId, title: x.title, artist: x.artist, coverUrl: x.coverUrl, durationMs: x.durationMs }));
      if (!take.length) return { queue: queue.get(), imported: 0, skipped: songs.length };
      const r = queue.append({ by: 'you', songs: take });
      return { queue: r.queue, imported: take.length, skipped: songs.length - take.length };
    },
    // Music 入口：YOU的歌单 + 每日推荐 + 私人雷达。私人雷达是网易云一个固定的歌单 id，内容按人给。
    async sources() {
      // 两张卡用网易云的真图：每日推荐拿当天第一首的专辑图，雷达拿它自己的封面和那句话；拿不到就只给名字，前端画底色
      const [playlists, daily, radar] = await Promise.all([
        this.playlists().catch(() => []),
        withCookie(async c => ({ songs: await client.dailySongs(c) })).then(r => r.songs[0] || null).catch(() => null),
        client.playlistCard ? withCookie(async c => ({ card: await client.playlistCard(c, RADAR_PLAYLIST_ID) })).then(r => r.card).catch(() => null) : null
      ]);
      return { specials: [
        { id: 'daily', name: '每日推荐', coverUrl: daily ? daily.coverUrl : '', line: daily ? '从《' + (String(daily.title).replace(/\s*[（(][^）)]*[）)]\s*/g, '').trim() || daily.title) + '》开始' : '' },
        { id: 'radar', name: '私人雷达', coverUrl: radar ? radar.coverUrl : '', line: radar ? radar.line : '' }
      ], playlists };
    },
    // 一份来源里的歌（每日推荐 / 雷达 / 歌单 id）：歌单详情页只看不放，loadSource 整份换过去
    async sourceSongs(source) {
      const s = String(source || '');
      let songs;
      if (s === 'daily') songs = await withCookie(async c => ({ songs: await client.dailySongs(c) })).then(r => r.songs);
      else if (s === 'radar' || /^\d+$/.test(s)) songs = await withCookie(async c => ({ songs: await client.playlistSongs(c, s === 'radar' ? RADAR_PLAYLIST_ID : s, 500) })).then(r => r.songs);
      else { const e = new Error('歌单不对'); e.code = 'MUSIC_INPUT_ERROR'; e.status = 400; throw e; }
      return songs.map(x => ({ songId: x.songId, title: x.title, artist: x.artist, album: x.album || '', coverUrl: x.coverUrl, durationMs: x.durationMs }));
    },
    async loadSource(source) {
      const songs = await this.sourceSongs(source);
      const r = queue.replaceWith({ by: 'you', songs: songs.map(x => ({ songId: x.songId, title: x.title, artist: x.artist, coverUrl: x.coverUrl, durationMs: x.durationMs })) });
      return { queue: r.queue, loaded: r.added.length, carried: r.carried };
    },
    async startQr() {
      const q = await client.createQr();
      pendingQr.set(q.key, q.expiresAt);
      return { key: q.key, qrImage: q.qrImage, expiresAt: q.expiresAt };
    },
    // 只认自己发出去的 key，别人拿任意 key 来轮询不给
    async pollQr(key) {
      const exp = pendingQr.get(key);
      if (!exp) return { status: 'expired' };
      if (now() > exp) { pendingQr.delete(key); return { status: 'expired' }; }
      const r = await client.checkQr(key);
      if (r.status !== 'success') { if (r.status === 'expired') pendingQr.delete(key); return { status: r.status }; }
      pendingQr.delete(key);
      const acc = await client.account(r.cookie);
      store.save(acc.cookie || r.cookie, acc.profile, acc.vip, now());
      return { status: 'success', profile: acc.profile, vip: acc.vip };
    },
    logout() { store.clear(); nowPlaying.clear(); return { connected: false }; },
    async search(q, limit) { return withCookie(async c => ({ songs: await client.search(c, q, limit) })).then(r => r.songs); },
    async song(songId) {
      const [detail, lines] = await Promise.all([client.songDetail(store.cookie(), songId), lyricsCached(String(songId))]);
      return Object.assign({}, detail, { lyrics: lines });
    },
    async lyrics(songId) { return lyricsCached(String(songId)); },
    async playUrl(songId) { return withCookie(c => client.playUrl(c, songId)); },
    reportNowPlaying(input) {
      const ok = nowPlaying.report(input, now());
      if (ok && input && input.songId) lyricsCached(String(input.songId)).catch(() => {});
      return ok;
    },
    clearNowPlaying() { nowPlaying.clear(); },
    nowPlaying() { return nowPlaying.current(now()); },
    // 给TA的 prompt 用；没在听返回 ''
    renderNowPlaying() {
      const cur = nowPlaying.current(now());
      if (!cur) return '';
      const lines = lyricCache.get(cur.songId) || [];
      let k = -1;
      for (let i = 0; i < lines.length; i++) if (lines[i].timeMs <= cur.positionMs + 300) k = i;
      const say = l => l ? l.text + (l.trans ? '（' + l.trans + '）' : '') : '';
      const head = cur.state === 'playing'
        ? '[' + YOU_NAME + ' 正在听：' + cur.title + ' — ' + cur.artist + ' · ' + fmt(cur.positionMs) + ' / ' + fmt(cur.durationMs) + ']'
        : '[' + YOU_NAME + ' 停在：' + cur.title + ' — ' + cur.artist + ' · ' + fmt(cur.positionMs) + '，暂停了 ' + Math.max(1, Math.round((now() - cur.pausedAt) / 60000)) + ' 分钟]';
      const out = [head];
      if (k >= 0) {
        if (lines[k - 1]) out.push('上一句：' + say(lines[k - 1]));
        out.push((cur.state === 'playing' ? '唱到：' : '停在这句：') + say(lines[k]));
        if (lines[k + 1]) out.push('下一句：' + say(lines[k + 1]));
      } else if (lines.length) {
        out.push('还在前奏，第一句是：' + say(lines[0]));
      }
      return out.join('\n');
    }
  };
}

module.exports = { createMusicAuthority };
