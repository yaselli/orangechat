'use strict';
const test = require('node:test');
const assert = require('node:assert');
const os = require('os'); const path = require('path'); const fs = require('fs');
const { createMusicAuthority } = require('../music/authority');

function fake() {
  return {
    createQr: async () => ({ key: 'k1', qrImage: 'data:x', expiresAt: 10000 }),
    checkQr: async () => ({ status: 'success', cookie: 'MUSIC_U=secret' }),
    account: async () => ({ profile: { userId: '1', nickname: 'YOU' }, vip: true, cookie: 'MUSIC_U=secret; __csrf=c' }),
    lyrics: async () => ({ lines: [{ index: 0, timeMs: 50000, text: 'just look up at the stars', trans: '请抬头仰望浩瀚星空' }, { index: 1, timeMs: 54500, text: "you know i'm just a call away", trans: '你深知我随叫随到' }, { index: 2, timeMs: 59000, text: "i'll come and take your hand", trans: '' }] }),
    search: async (c) => { assert.ok(c.includes('MUSIC_U')); return []; }
  };
}
function setup() {
  let t = 1000;
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'one-earbud-music-')), 'auth.json');
  const a = createMusicAuthority({ client: fake(), file, now: () => t });
  return { a, file, tick: ms => { t += ms; } };
}

test('扫码登录：只认自己发出的 key；成功后存 cookie，公开状态不带 cookie', async () => {
  const { a, file } = setup();
  assert.deepStrictEqual(await a.pollQr('别人的key'), { status: 'expired' });
  const q = await a.startQr();
  const r = await a.pollQr(q.key);
  assert.strictEqual(r.status, 'success');
  assert.strictEqual(JSON.stringify(a.status()).includes('MUSIC_U'), false);
  assert.strictEqual(a.status().connected, true);
  assert.strictEqual((fs.statSync(file).mode & 0o777), 0o600);
  await a.search('晴天');
});

test('正在听：唱到哪句、前后各一句都带；暂停十分钟后、心跳断一分钟后、关掉后都不带', async () => {
  const { a, tick } = setup();
  a.reportNowPlaying({ songId: '1901371647', title: 'run away', artist: 'Demxntia', positionMs: 55000, durationMs: 197000, playing: true });
  await new Promise(r => setImmediate(r));
  const s = a.renderNowPlaying();
  assert.match(s, /YOU 正在听：run away — Demxntia · 0:55 \/ 3:17/);
  assert.match(s, /唱到：you know i'm just a call away（你深知我随叫随到）/);
  assert.match(s, /上一句：just look up/);
  assert.match(s, /下一句：i'll come and take your hand/);
  a.reportNowPlaying({ songId: '1901371647', title: 'run away', artist: 'Demxntia', positionMs: 56000, durationMs: 197000, playing: false });
  tick(30000); a.reportNowPlaying({ songId: '1901371647', title: 'run away', artist: 'Demxntia', positionMs: 56000, durationMs: 197000, playing: false });
  assert.match(a.renderNowPlaying(), /YOU 停在：run away.*暂停了 1 分钟/);
  for (let i = 0; i < 21; i++) { tick(30000); a.reportNowPlaying({ songId: '1901371647', title: 'run away', artist: 'Demxntia', positionMs: 56000, durationMs: 197000, playing: false }); }
  assert.strictEqual(a.renderNowPlaying(), '');
  a.reportNowPlaying({ songId: '1901371647', title: 'run away', artist: 'Demxntia', positionMs: 1000, durationMs: 197000, playing: true });
  tick(61000);
  assert.strictEqual(a.renderNowPlaying(), '');
  a.reportNowPlaying({ songId: '1901371647', title: 'run away', artist: 'Demxntia', positionMs: 1000, durationMs: 197000, playing: true });
  a.clearNowPlaying();
  assert.strictEqual(a.renderNowPlaying(), '');
});
