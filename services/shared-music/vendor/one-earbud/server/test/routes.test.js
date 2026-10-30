'use strict';
const test = require('node:test');
const assert = require('node:assert');
const express = require('express');
const { mountMusicRoutes } = require('../music/routes');

function app(authority) {
  const a = express(); a.use(express.json()); const r = express.Router(); mountMusicRoutes(r, authority); a.use('/api', r);
  return new Promise(res => { const s = a.listen(0, () => res(s)); });
}
async function call(s, method, p, body) {
  const r = await fetch('http://127.0.0.1:' + s.address().port + p, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, json: await r.json() };
}

test('没有 authority 时一律 503', async () => {
  const s = await app(null);
  try { assert.strictEqual((await call(s, 'GET', '/api/music/status')).status, 503); } finally { s.close(); }
});

test('状态、搜歌、上报正在听；上游错误按 status 透出，不透 cookie', async () => {
  const seen = [];
  const s = await app({
    status: () => ({ connected: true, profile: { nickname: 'YOU' } }),
    search: async q => { if (q === 'boom') { const e = new Error('网易云登录过期了'); e.status = 401; e.code = 'MUSIC_LOGIN_EXPIRED'; throw e; } return [{ songId: '1', title: q }]; },
    reportNowPlaying: b => { seen.push(b); return /^\d+$/.test(String(b.songId)); },
    clearNowPlaying: () => seen.push('clear')
  });
  try {
    assert.deepStrictEqual((await call(s, 'GET', '/api/music/status')).json, { ok: true, connected: true, profile: { nickname: 'YOU' } });
    assert.strictEqual((await call(s, 'GET', '/api/music/search?q=' + encodeURIComponent('晴天'))).json.songs[0].title, '晴天');
    const bad = await call(s, 'GET', '/api/music/search?q=boom');
    assert.strictEqual(bad.status, 401); assert.strictEqual(bad.json.error, 'MUSIC_LOGIN_EXPIRED');
    assert.strictEqual((await call(s, 'POST', '/api/music/now-playing', { songId: '12', playing: true })).status, 200);
    assert.strictEqual((await call(s, 'POST', '/api/music/now-playing', { songId: 'x' })).status, 400);
    await call(s, 'DELETE', '/api/music/now-playing');
    assert.strictEqual(seen[seen.length - 1], 'clear');
  } finally { s.close(); }
});

test('播放列表：她这边的操作一律按 li 记账，身体里写 by:ke 也没用；错误码按规矩透出', async () => {
  const fs = require('fs'), os = require('os'), path = require('path');
  const { createMusicQueueStore } = require('../music/queue');
  const queue = createMusicQueueStore({ file: path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'one-earbud-qr-')), 'q.json') });
  const s = await app({ queue });
  try {
    let r = await call(s, 'POST', '/api/music/queue/append', { songs: [{ songId: '1', title: 'a' }, { songId: '2', title: 'b' }], by: 'ta' });
    assert.strictEqual(r.status, 200);
    assert.deepStrictEqual(r.json.queue.items.map(x => x.addedBy), ['you', 'you']);
    const [a, b] = r.json.queue.items;
    r = await call(s, 'POST', '/api/music/queue/move', { id: b.id, to: 0 });
    assert.deepStrictEqual(r.json.queue.items.map(x => x.songId), ['2', '1']);
    r = await call(s, 'POST', '/api/music/queue/mode', { mode: 'nope' });
    assert.strictEqual(r.status, 400); assert.strictEqual(r.json.error, 'queue_mode_invalid');
    r = await call(s, 'DELETE', '/api/music/queue/zzz');
    assert.strictEqual(r.status, 404);
    r = await call(s, 'DELETE', '/api/music/queue/' + a.id);
    assert.deepStrictEqual(r.json.queue.items.map(x => x.songId), ['2']);
    r = await call(s, 'GET', '/api/music/queue');
    assert.strictEqual(r.json.queue.items.length, 1);
  } finally { s.close(); }
});
