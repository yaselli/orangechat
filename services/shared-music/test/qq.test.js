'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');
const { createQQAuthority } = require('../src/qq');
const { createQQBridge } = require('../src/qq-bridge');

async function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'music-qq-test-'));
  const seen = [];
  let sequence = 0;
  const bridge = { async call(method, input) {
    seen.push({ method, input });
    if (method === 'qr') return { result: { identifier: 'private-qr-' + ++sequence, qrImage: 'data:image/png;base64,AA==' } };
    if (method === 'poll') return { result: { status: 'success', credential: { musicid: sequence, musickey: 'private-login-ticket' } } };
    if (method === 'search') return { result: [{ songId: '12', title: 'QQ song', artist: 'artist', platform: 'qq' }] };
    if (method === 'song') return { result: { songId: input.songId, title: 'QQ song', artist: 'artist', platform: 'qq' } };
    if (method === 'lyrics') return { result: [{ index: 0, timeMs: 1000, text: '词' }] };
    if (method === 'playlists') return { result: [{ id: '23', name: 'QQ list' }] };
    if (method === 'source') return { result: [{ songId: '12', title: input.source, platform: 'qq' }] };
    if (method === 'url') return { result: { url: 'https://ws.stream.qqmusic.qq.com/song.mp3', trial: false } };
    throw Error('unexpected test operation');
  } };
  const app = createApp({ directory, registrationKey: 'own-server-pairing-key',
    qqFactory: options => createQQAuthority({ ...options, bridge }) });
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); fs.rmSync(directory, { recursive: true }); });
  const origin = 'http://127.0.0.1:' + server.address().port + '/api/music';
  let token = '';
  async function call(route, body, platform = 'qq', method = body === undefined ? 'GET' : 'POST', device = token) {
    const response = await fetch(origin + route, { method, headers: { 'Content-Type': 'application/json',
      'X-Music-Platform': platform, Authorization: 'Bearer ' + (route === '/sessions' ? 'own-server-pairing-key' : device) },
      body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  }
  token = (await call('/sessions', {})).body.token;
  async function login() { const qr = (await call('/login/qr', {})).body; return { qr, result: await call('/login/qr/' + qr.key) }; }
  return { call, login, directory, seen, token };
}

test('one paired device keeps independent account, queue and heartbeat for each platform', async t => {
  const f = await fixture(t);
  assert.equal((await f.call('/status')).body.connected, false);
  assert.equal((await f.call('/status')).body.platform, 'qq');
  await f.login();
  assert.equal((await f.call('/status')).body.connected, true);
  assert.equal((await f.call('/status', undefined, 'netease')).body.connected, false);
  await f.call('/queue/append', { song: { songId: '12', title: 'QQ queue' } });
  await f.call('/queue/append', { song: { songId: '12', title: 'NCM queue' } }, 'netease');
  assert.equal((await f.call('/queue')).body.queue.items[0].title, 'QQ queue');
  assert.equal((await f.call('/queue', undefined, 'netease')).body.queue.items[0].title, 'NCM queue');
  await f.call('/now-playing', { songId: '12', title: 'QQ listening', playing: true });
  assert.equal((await f.call('/context', undefined, 'netease')).body.text, '');
  assert.match((await f.call('/context')).body.text, /QQ listening/);
});

test('QQ accounts and QR attempts stay isolated between two installations on the same server', async t => {
  const f = await fixture(t);
  const second = (await f.call('/sessions', {})).body.token;
  const qr = (await f.call('/login/qr', {})).body;
  assert.equal((await f.call('/login/qr/' + qr.key, undefined, 'qq', 'GET', second)).body.status, 'expired');
  await f.call('/login/qr/' + qr.key);
  assert.equal((await f.call('/status', undefined, 'qq', 'GET', second)).body.connected, false);
  await f.call('/queue/append', { song: { songId: '12', title: 'only first device' } });
  assert.equal((await f.call('/queue', undefined, 'qq', 'GET', second)).body.queue.items.length, 0);
  await f.call('/logout', {}, 'qq', 'POST', second);
  assert.equal((await f.call('/status')).body.connected, true);
});

test('the real Python worker rejects malformed IDs without leaking upstream credentials', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'qq-worker-test-'));
  const bridge = createQQBridge();
  try {
    await assert.rejects(bridge.call('song', { songId: '../private', credential: {}, devicePath: path.join(root, 'device.json') }),
      error => error.code === 'MUSIC_INPUT_ERROR' && !error.message.includes('private'));
    await assert.rejects(bridge.call('search', { query: 'test', credential: {}, devicePath: path.join(root, 'device.json') }),
      error => error.code === 'MUSIC_NOT_LOGGED_IN' && error.status === 401);
  } finally { bridge.close(); fs.rmSync(root, { recursive: true, force: true }); }
});

test('QQ searches require this device login before any upstream call and carry its credential afterwards', async t => {
  const f = await fixture(t);
  const response = await f.call('/search?q=test');
  assert.equal(response.status, 401);
  assert.equal(response.body.error, 'MUSIC_NOT_LOGGED_IN');
  assert.equal(f.seen.length, 0);
  await f.login();
  assert.equal((await f.call('/search?q=test')).status, 200);
  const request = f.seen.find(x => x.method === 'search').input;
  assert.equal(request.credential.musickey, 'private-login-ticket');
  assert.ok(request.credential.musicid > 0);
});

test('repeating a successful QQ search reuses only this device cache and relogin clears it', async t => {
  const f = await fixture(t); await f.login();
  await f.call('/search?q=test'); await f.call('/search?q=test');
  assert.equal(f.seen.filter(x => x.method === 'search').length, 1);
  await f.call('/logout', {}); await f.login();
  await f.call('/search?q=test');
  assert.equal(f.seen.filter(x => x.method === 'search').length, 2);
});

test('QQ QR identifiers and credentials stay server-side and a guessed QR key cannot log in', async t => {
  const f = await fixture(t);
  const { qr, result } = await f.login();
  assert.ok(qr.qrImage.startsWith('data:image/png;base64,'));
  assert.ok(!JSON.stringify(qr).includes('private-qr-'));
  assert.ok(!JSON.stringify(result.body).includes('private-login-ticket'));
  assert.equal((await f.call('/login/qr/guessed-key')).body.status, 'expired');
  assert.equal((await f.call('/login/qr/' + qr.key)).body.status, 'expired');
  const account = (await f.call('/status')).body;
  assert.ok(!JSON.stringify(account).includes('musickey'));
});

test('QQ sources, numeric song IDs and all AI actions use the selected authority', async t => {
  const f = await fixture(t); await f.login();
  assert.equal((await f.call('/sources')).body.specials[0].name, '猜你喜欢');
  assert.equal((await f.call('/source/liked/songs')).body.songs[0].title, 'liked');
  assert.equal((await f.call('/search?q=test')).body.songs[0].platform, 'qq');
  assert.equal((await f.call('/song/12')).body.song.platform, 'qq');
  assert.match((await f.call('/song/12/url')).body.url, /stream.qqmusic.qq.com/);
  await f.call('/tool', { action: 'queue_add', songId: '12', requestId: 'qq-add-123' });
  await f.call('/tool', { action: 'queue_add', songId: '12', requestId: 'qq-add-123' });
  assert.equal((await f.call('/queue')).body.queue.items.length, 1);
  assert.equal((await f.call('/tool', { action: 'share', songId: '12', lineIndexes: [0] })).body.delivery.card.lines[0].text, '词');
  assert.equal((await f.call('/tool', { action: 'queue_clear' })).status, 400);
});

test('QQ logout clears only QQ, while disconnect revokes the whole paired device', async t => {
  const f = await fixture(t); await f.login();
  await f.call('/queue/append', { song: { songId: '12', title: 'QQ' } });
  await f.call('/queue/append', { song: { songId: '12', title: 'keep NCM' } }, 'netease');
  const old = (await f.call('/login/qr', {})).body.key;
  await f.call('/logout', {});
  assert.equal((await f.call('/queue')).body.queue.items.length, 0);
  assert.equal((await f.call('/status')).body.connected, false);
  assert.equal((await f.call('/login/qr/' + old)).body.status, 'expired');
  assert.equal((await f.call('/queue', undefined, 'netease')).body.queue.items[0].title, 'keep NCM');
  await f.call('/session', undefined, 'qq', 'DELETE');
  assert.equal((await f.call('/status')).status, 401);
  assert.equal((await f.call('/status', undefined, 'netease')).status, 401);
  assert.equal(fs.readdirSync(f.directory).length, 0);
});

test('platform input cannot become a path and QQ login files are private', async t => {
  const f = await fixture(t); await f.login();
  assert.equal((await f.call('/status', undefined, '../qq')).status, 400);
  const root = path.join(f.directory, fs.readdirSync(f.directory)[0]);
  assert.equal(fs.statSync(path.join(root, 'qq')).mode & 0o777, 0o700);
  assert.equal(fs.statSync(path.join(root, 'qq/auth.json')).mode & 0o777, 0o600);
  assert.ok(!fs.readFileSync(path.join(root, 'qq/auth.json'), 'utf8').includes(f.token));
});
