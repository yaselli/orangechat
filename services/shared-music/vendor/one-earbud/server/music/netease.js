'use strict';
// one-earbud 听歌 · 网易云客户端。
// 直连 music.163.com 的 weapi；思路参考已废弃的 listen/netease-client.js，但这是新模块，不接旧的 fallback / 外部代理。
// 登录态（cookie）只在这里进出，调用方负责存；任何返回给前端或写日志的对象都不带 cookie。

const crypto = require('crypto');
const https = require('https');
const { makeQrDataUri } = require('./qr');

const BASE = 'https://music.163.com';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
const PRESET_KEY = '0CoJUm6Qyw8W8jud';
const IV = '0102030405060708';
const RSA_E = '010001';
const RSA_N = '00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7';
const TIMEOUT_MS = 12000;
// 服务器在海外，网易云按来源 IP 做版权地区限制和登录风控（扫码确认时回 8821）。
// 带上一个国内来源地址就能过地区这一关。地址从环境变量来，不写死在代码里。
const REAL_IP = /^\d{1,3}(\.\d{1,3}){3}$/.test(String(process.env.NETEASE_REAL_IP || '').trim()) ? String(process.env.NETEASE_REAL_IP).trim() : '';

class MusicError extends Error {
  constructor(message, code, status) {
    super(message);
    this.name = 'MusicError';
    this.code = code || 'MUSIC_UPSTREAM_ERROR';
    this.status = status || 502;
  }
}

function str(v, max) { const s = v == null ? '' : String(v).trim(); return max ? s.slice(0, max) : s; }

// ---------- weapi 加密 ----------
function aes(text, key) {
  const c = crypto.createCipheriv('aes-128-cbc', Buffer.from(key, 'utf8'), Buffer.from(IV, 'utf8'));
  return Buffer.concat([c.update(String(text), 'utf8'), c.final()]).toString('base64');
}
function rsa(text) {
  const mod = BigInt('0x' + RSA_N);
  let base = BigInt('0x' + Buffer.from(String(text).split('').reverse().join(''), 'utf8').toString('hex')) % mod;
  let exp = BigInt('0x' + RSA_E); let out = 1n;
  while (exp > 0n) { if (exp & 1n) out = (out * base) % mod; base = (base * base) % mod; exp >>= 1n; }
  return out.toString(16).padStart(256, '0');
}
function randomSecret() {
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  const bytes = crypto.randomBytes(16); let s = '';
  for (const b of bytes) s += chars[b % chars.length];
  return s;
}
function weapiBody(payload) {
  const secret = randomSecret();
  return new URLSearchParams({ params: aes(aes(JSON.stringify(payload || {}), PRESET_KEY), secret), encSecKey: rsa(secret) }).toString();
}

// ---------- cookie ----------
function cookieMap(cookie) {
  const m = new Map();
  String(cookie || '').split(';').forEach(part => {
    const i = part.indexOf('='); if (i <= 0) return;
    const k = part.slice(0, i).trim(); const v = part.slice(i + 1).trim();
    if (k && !/^(path|domain|expires|max-age|secure|httponly|samesite)$/i.test(k)) m.set(k, v);
  });
  return m;
}
function mergeCookie(base, setCookieHeaders) {
  const m = cookieMap(base);
  (setCookieHeaders || []).forEach(h => {
    const first = String(h).split(';')[0]; const i = first.indexOf('=');
    if (i > 0) m.set(first.slice(0, i).trim(), first.slice(i + 1).trim());
  });
  return Array.from(m.entries()).map(e => e[0] + '=' + e[1]).join('; ');
}
function csrfOf(cookie) { return cookieMap(cookie).get('__csrf') || ''; }

// ---------- HTTP ----------
function request(path, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    const url = new URL(BASE + path);
    const headers = { 'User-Agent': UA, Accept: 'application/json,text/plain,*/*', Referer: BASE + '/', Origin: BASE };
    if (opts.cookie) headers.Cookie = opts.cookie;
    if (REAL_IP) { headers['X-Real-IP'] = REAL_IP; headers['X-Forwarded-For'] = REAL_IP; }
    let body = null;
    if (opts.form) { body = opts.form; headers['Content-Type'] = 'application/x-www-form-urlencoded'; headers['Content-Length'] = Buffer.byteLength(body); }
    const req = https.request(url, { method: body ? 'POST' : 'GET', headers, timeout: TIMEOUT_MS }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        const status = res.statusCode || 0;
        if (status < 200 || status >= 300) return reject(new MusicError('网易云接口暂时不可用（HTTP ' + status + '）', 'MUSIC_UPSTREAM_ERROR', 502));
        let json;
        try { json = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
        catch (_) { return reject(new MusicError('网易云返回的内容解析不了', 'MUSIC_BAD_RESPONSE', 502)); }
        resolve({ body: json, cookie: mergeCookie(opts.cookie || '', res.headers['set-cookie']) });
      });
    });
    req.on('timeout', () => req.destroy(new MusicError('网易云连接超时', 'MUSIC_TIMEOUT', 504)));
    req.on('error', err => reject(err instanceof MusicError ? err : new MusicError('网易云连接失败', 'MUSIC_UPSTREAM_ERROR', 502)));
    if (body) req.write(body);
    req.end();
  });
}
function weapi(path, payload, cookie) {
  return request(path, { form: weapiBody(Object.assign({}, payload || {}, { csrf_token: csrfOf(cookie) })), cookie: cookie || '' });
}
function needLogin(cookie) { if (!cookie) throw new MusicError('还没连上网易云', 'MUSIC_NOT_LOGGED_IN', 401); }

// ---------- 数据整形 ----------
function normSong(s) {
  s = s || {};
  const ar = Array.isArray(s.ar) ? s.ar : (Array.isArray(s.artists) ? s.artists : []);
  const al = s.al || s.album || {};
  return {
    songId: str(s.id, 32),
    title: str(s.name, 160) || '未命名',
    artist: ar.map(a => a && a.name).filter(Boolean).join(' / ').slice(0, 160) || '未知歌手',
    album: str(al.name, 160),
    coverUrl: str(al.picUrl, 600).replace(/^http:\/\//, 'https://'),
    durationMs: Number(s.dt || s.duration || 0) || 0
  };
}
function parseLrc(raw) {
  const out = [];
  String(raw || '').split(/\r?\n/).forEach(line => {
    const tags = Array.from(line.matchAll(/\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g));
    const text = line.replace(/\[[^\]]*\]/g, '').trim();
    if (!tags.length || !text) return;
    tags.forEach(m => out.push({ timeMs: Number(m[1]) * 60000 + Number(m[2]) * 1000 + Number(String(m[3] || '0').padEnd(3, '0').slice(0, 3)), text }));
  });
  return out.sort((a, b) => a.timeMs - b.timeMs);
}
// 原文和翻译按时间戳对齐；翻译里时间差 300ms 以内的算同一句
// 网易云把「作词 : xx」「制作人 : xx」这种署名也写进歌词里，开头一串、结尾一串。
// 只剥两头连续的署名行，中间真歌词里偶尔带冒号的不碰。
const CREDIT_LINE = /^[^\s:：,，。!！?？]{1,14}\s*[:：]\s*\S/;
function stripCredits(lines) {
  let a = 0, b = lines.length;
  while (a < b && CREDIT_LINE.test(lines[a].text)) a++;
  while (b > a && CREDIT_LINE.test(lines[b - 1].text)) b--;
  return lines.slice(a, b);
}
function mergeLyrics(lrc, tlrc) {
  const lines = stripCredits(parseLrc(lrc)); const trans = parseLrc(tlrc);
  let j = 0;
  return lines.map((l, index) => {
    while (j < trans.length && trans[j].timeMs < l.timeMs - 300) j++;
    const t = (j < trans.length && Math.abs(trans[j].timeMs - l.timeMs) <= 300) ? trans[j].text : '';
    return { index, timeMs: l.timeMs, text: l.text, trans: t };
  });
}

// ---------- 登录 ----------
// 扫码走 PC 客户端那一路（/api + type 3 + os=pc）。网页那一路（weapi + type 1）在扫码确认时会被风控回 8821。
const PC_CLIENT_COOKIE = 'os=pc; appver=3.1.17.204416; osver=Microsoft-Windows-10-Professional-build-19045-64bit; channel=netease';
async function createQr() {
  const { body } = await request('/api/login/qrcode/unikey', { form: 'type=3', cookie: PC_CLIENT_COOKIE });
  const key = str(body.unikey || (body.data && body.data.unikey), 100);
  if (!key) throw new MusicError('二维码生成失败', 'MUSIC_QR_FAILED', 502);
  const url = BASE + '/login?codekey=' + encodeURIComponent(key);
  return { key, qrImage: makeQrDataUri(url), expiresAt: Date.now() + 180000 };
}
// 返回 { status: waiting|scanned|expired|success, cookie?(仅 success) }
async function checkQr(key) {
  const k = str(key, 120); if (!k) throw new MusicError('缺少二维码 key', 'MUSIC_INPUT_ERROR', 400);
  const { body, cookie } = await request('/api/login/qrcode/client/login', { form: 'key=' + encodeURIComponent(k) + '&type=3', cookie: PC_CLIENT_COOKIE });
  const code = Number(body.code);
  if (code === 800) return { status: 'expired' };
  if (code === 801) return { status: 'waiting' };
  if (code === 802) return { status: 'scanned' };
  if (code === 8821) throw new MusicError('网易云风控拦了这次登录（8821）', 'MUSIC_LOGIN_RISK_BLOCKED', 403);
  if (code !== 803) return { status: 'expired' };
  const full = body.cookie ? mergeCookie(cookie, String(body.cookie).split(/;\s*/)) : cookie;
  return { status: 'success', cookie: full };
}
async function account(cookie) {
  needLogin(cookie);
  const { body, cookie: next } = await weapi('/weapi/w/nuser/account/get', {}, cookie);
  const p = body.profile || {};
  if (!p.userId) throw new MusicError('网易云登录过期了，要重新扫码', 'MUSIC_LOGIN_EXPIRED', 401);
  const vip = body.account && Number(body.account.vipType) > 0;
  return { profile: { userId: str(p.userId, 32), nickname: str(p.nickname, 80), avatarUrl: str(p.avatarUrl, 600) }, vip: !!vip, cookie: next };
}

// ---------- 歌 ----------
async function search(cookie, keyword, limit) {
  needLogin(cookie);
  const q = str(keyword, 80); if (!q) throw new MusicError('搜什么呢', 'MUSIC_INPUT_ERROR', 400);
  const { body } = await weapi('/weapi/cloudsearch/get/web', { s: q, type: 1, limit: Math.min(30, Math.max(1, Number(limit) || 12)), offset: 0, total: true }, cookie || '');
  // 50000005：网易云认为没登录（cloudsearch 未登录就是这个码）
  if (Number(body.code) === 50000005) throw new MusicError('网易云登录过期了，要重新扫码', 'MUSIC_LOGIN_EXPIRED', 401);
  return ((body.result && body.result.songs) || []).map(normSong);
}
// ---------- YOU的歌单（导入播放列表用） ----------
// YOU建的和收藏的歌单；「TA喜欢的音乐」是 specialType 5 那一张，排第一。
async function userPlaylists(cookie, userId) {
  needLogin(cookie);
  const uid = str(userId, 32); if (!/^\d+$/.test(uid)) throw new MusicError('账号 id 不对', 'MUSIC_INPUT_ERROR', 400);
  const { body } = await weapi('/weapi/user/playlist', { uid, limit: 100, offset: 0, includeVideo: true }, cookie);
  return (body.playlist || []).map(p => ({
    playlistId: str(p.id, 32), name: str(p.name, 120), count: Number(p.trackCount) || 0,
    coverUrl: str(p.coverImgUrl, 600).replace(/^http:\/\//, 'https://'),
    mine: String(p.creator && p.creator.userId || '') === uid,
    liked: Number(p.specialType) === 5 && String(p.creator && p.creator.userId || '') === uid
  }));
}
// 一张歌单里的歌：先拿全部 trackIds，再按 500 一批换成歌曲详情，保持歌单里的顺序。
// 每日推荐：YOU自己的那 30 首，按YOU的登录态取
async function dailySongs(cookie) {
  needLogin(cookie);
  const { body } = await weapi('/weapi/v3/discovery/recommend/songs', {}, cookie);
  const list = (body && body.data && Array.isArray(body.data.dailySongs)) ? body.data.dailySongs : [];
  return list.map(normSong).filter(s => s.songId);
}

// 入口页那张雷达卡：网易云把它当天那句话塞在歌单名里，「今天《X》爱不释耳|私人雷达」；封面也是按人给的
async function playlistCard(cookie, playlistId) {
  needLogin(cookie);
  const id = str(playlistId, 32); if (!/^\d+$/.test(id)) throw new MusicError('歌单 id 不对', 'MUSIC_INPUT_ERROR', 400);
  const { body } = await weapi('/weapi/v6/playlist/detail', { id, n: 0, s: 0 }, cookie);
  const p = body.playlist || {};
  const parts = str(p.name, 200).split('|').map(x => x.trim()).filter(Boolean);
  return { name: parts.length > 1 ? parts[parts.length - 1] : (parts[0] || ''), line: parts.length > 1 ? parts.slice(0, -1).join(' ') : '', coverUrl: str(p.coverImgUrl, 600).replace(/^http:\/\//, 'https://') };
}

async function playlistSongs(cookie, playlistId, max) {
  needLogin(cookie);
  const id = str(playlistId, 32); if (!/^\d+$/.test(id)) throw new MusicError('歌单 id 不对', 'MUSIC_INPUT_ERROR', 400);
  const { body } = await weapi('/weapi/v6/playlist/detail', { id, n: 100000, s: 0 }, cookie);
  const ids = ((body.playlist && body.playlist.trackIds) || []).map(t => String(t.id)).filter(x => /^\d+$/.test(x)).slice(0, Math.max(1, Math.min(500, Number(max) || 500)));
  const out = [];
  for (let i = 0; i < ids.length; i += 500) {
    const part = ids.slice(i, i + 500);
    const r = await weapi('/weapi/v3/song/detail', { c: JSON.stringify(part.map(x => ({ id: x }))), ids: JSON.stringify(part) }, cookie);
    const byId = new Map((r.body.songs || []).map(s => [String(s.id), normSong(s)]));
    for (const x of part) if (byId.has(x)) out.push(byId.get(x));
  }
  return out;
}
async function songDetail(cookie, songId) {
  const id = str(songId, 32); if (!/^\d+$/.test(id)) throw new MusicError('歌曲 id 不对', 'MUSIC_INPUT_ERROR', 400);
  const { body } = await weapi('/weapi/v3/song/detail', { c: JSON.stringify([{ id }]), ids: JSON.stringify([id]) }, cookie || '');
  const s = (body.songs || [])[0];
  if (!s) throw new MusicError('找不到这首歌', 'MUSIC_SONG_NOT_FOUND', 404);
  return normSong(s);
}
async function lyrics(cookie, songId) {
  const id = str(songId, 32); if (!/^\d+$/.test(id)) throw new MusicError('歌曲 id 不对', 'MUSIC_INPUT_ERROR', 400);
  const { body } = await request('/api/song/lyric?os=pc&id=' + id + '&lv=-1&kv=-1&tv=-1', { cookie: cookie || '' });
  const lines = mergeLyrics(body.lrc && body.lrc.lyric, body.tlyric && body.tlyric.lyric);
  return { songId: id, lines, pure: !!body.pureMusic || !!body.nolyric };
}
// 播放地址会过期，只现取，不缓存
async function playUrl(cookie, songId) {
  needLogin(cookie);
  const id = str(songId, 32); if (!/^\d+$/.test(id)) throw new MusicError('歌曲 id 不对', 'MUSIC_INPUT_ERROR', 400);
  const { body, cookie: next } = await weapi('/weapi/song/enhance/player/url/v1', { ids: JSON.stringify([id]), level: 'exhigh', encodeType: 'aac' }, cookie);
  const item = (body.data || [])[0] || {};
  // m801 这台 CDN 优先走 IPv6，YOU手机开 5G 时连不上；同一个签名路径换到 m701 能放，服务器上验证过
  const url = str(item.url, 1200).replace(/^http:\/\//, 'https://').replace(/^https:\/\/m801\.music\.126\.net\//, 'https://m701.music.126.net/');
  if (!url) throw new MusicError('这首歌现在放不了', 'MUSIC_UNAVAILABLE', 409);
  return { songId: id, url, trial: !!item.freeTrialInfo, cookie: next };
}

module.exports = { MusicError, createQr, checkQr, account, search, songDetail, userPlaylists, playlistSongs, playlistCard, dailySongs, lyrics, playUrl, mergeLyrics, parseLrc, mergeCookie };
