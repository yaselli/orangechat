'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');
const { createTenants } = require('../src/tenants');

async function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'watermelon-music-'));
  let qr = 0;
  const client = {
    createQr: async () => ({ key: 'qr-' + ++qr, qrImage: 'data:image/png;base64,AA==', expiresAt: Date.now() + 60000 }),
    checkQr: async key => ({ status: 'success', cookie: 'secret-' + key }),
    account: async cookie => ({ profile: { userId: cookie.endsWith('1') ? 1 : 2, nickname: cookie }, vip: false }),
    search: async cookie => [{ songId: '1', title: cookie, artist: 'artist', durationMs: 300000 }],
    songDetail: async (_cookie, songId) => ({ songId, title: 'song', artist: 'artist', durationMs: 300000 }),
    lyrics: async () => ({ lines: [{ index: 0, timeMs: 0, text: 'line' }] }),
  };
  const tenants = createTenants({ directory, client });
  const registrationKey = 'private-server-owner-key';
  const app = createApp({ tenants, registrationsPerHour: 100, registrationKey });
  const server = app.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); fs.rmSync(directory, { recursive: true }); });
  const origin = 'http://127.0.0.1:' + server.address().port;
  async function call(token, name, body, method = body === undefined ? 'GET' : 'POST') {
    const response = await fetch(origin + '/api/music' + name, { method,
      headers: { Authorization: token ? 'Bearer ' + token : '', 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: response.status, body: await response.json(), cache: response.headers.get('cache-control') };
  }
  async function login(token) {
    const qr = (await call(token, '/login/qr', {})).body;
    await call(token, '/login/qr/' + qr.key);
    return qr;
  }
  return { directory, tenants, client, call, login, registrationKey,
    register: async () => (await call(registrationKey, '/sessions', {})).body.token };
}

test('two users cannot read or overwrite each other’s account, QR, queue or listening state', async t => {
  const f = await fixture(t), a = await f.register(), b = await f.register();
  const qr = (await f.call(a, '/login/qr', {})).body;
  assert.equal((await f.call(b, '/login/qr/' + qr.key)).body.status, 'expired');
  await f.call(a, '/login/qr/' + qr.key);
  assert.equal((await f.call(a, '/status')).body.connected, true);
  assert.equal((await f.call(b, '/status')).body.connected, false);
  await f.call(a, '/queue/append', { song: { songId: '1', title: 'only A' } });
  assert.equal((await f.call(b, '/queue')).body.queue.items.length, 0);
  await f.call(a, '/now-playing', { songId: '1', title: 'only A', playing: true, durationMs: 10000 });
  assert.match((await f.call(a, '/context')).body.text, /only A/);
  assert.equal((await f.call(b, '/context')).body.text, '');
  await f.call(b, '/logout', {});
  assert.equal((await f.call(a, '/status')).body.connected, true);
  const status = await f.call(a, '/status');
  assert.equal(status.cache, 'no-store');
  assert.equal(Object.hasOwn(status.body, 'cookie'), false);
});

test('a private self-hosted service requires its owner’s key for pairing and uses a separate device credential afterwards', async t => {
  const f = await fixture(t);
  assert.equal((await f.call('', '/sessions', {})).status, 401);
  assert.equal((await f.call('wrong-access-key', '/sessions', {})).body.error, 'MUSIC_ACCESS_DENIED');
  const token = await f.register();
  assert.notEqual(token, f.registrationKey);
  assert.equal((await f.call(token, '/status')).status, 200);
  assert.equal((await f.call(f.registrationKey, '/status')).status, 401);
});

test('invalid credentials and forged user IDs never select another tenant', async t => {
  const f = await fixture(t), token = await f.register();
  await f.login(token);
  for (const forged of ['', '../' + token, 'x'.repeat(43), token.slice(0, -1) + '!']) {
    assert.equal((await f.call(forged, '/status')).status, 401);
  }
  const second = await f.register();
  await f.call(second, '/now-playing', { userId: token, songId: '1', title: 'second', playing: true });
  assert.equal((await f.call(token, '/context')).body.text, '');
});

test('concurrent retries with one idempotency key add exactly one song', async t => {
  const f = await fixture(t), token = await f.register();
  await f.login(token);
  const input = { action: 'queue_add', songId: '1', requestId: 'same-request-1', note: 'for you' };
  const results = await Promise.all(Array.from({ length: 5 }, () => f.call(token, '/tool', input)));
  assert.ok(results.every(x => x.body.ok));
  assert.equal((await f.call(token, '/queue')).body.queue.items.length, 1);
});

test('logout clears old QR and shared queue; reinstall/session deletion revokes the credential', async t => {
  const f = await fixture(t), token = await f.register();
  await f.login(token);
  const old = (await f.call(token, '/login/qr', {})).body;
  await f.call(token, '/queue/append', { song: { songId: '1' } });
  await f.call(token, '/logout', {});
  assert.equal((await f.call(token, '/login/qr/' + old.key)).body.status, 'expired');
  assert.equal((await f.call(token, '/queue')).body.queue.items.length, 0);
  assert.equal((await f.call(token, '/status')).body.connected, false);
  await f.call(token, '/session', undefined, 'DELETE');
  assert.equal((await f.call(token, '/status')).status, 401);
});

test('credentials survive service restart and never appear in tenant files', async t => {
  const f = await fixture(t), token = await f.register();
  await f.login(token);
  const restarted = createTenants({ directory: f.directory });
  assert.equal(await restarted.run(token, t => t.authority.status().connected), true);
  for (const id of fs.readdirSync(f.directory)) {
    const root = path.join(f.directory, id);
    assert.equal(fs.statSync(root).mode & 0o777, 0o700);
    for (const file of fs.readdirSync(root)) assert.ok(!fs.readFileSync(path.join(root, file), 'utf8').includes(token));
    assert.equal(fs.statSync(path.join(root, 'netease-auth.json')).mode & 0o777, 0o600);
  }
});

test('AI cannot delete songs or change playback mode; upstream secrets are not forwarded on errors', async t => {
  const f = await fixture(t), token = await f.register();
  assert.equal((await f.call(token, '/tool', { action: 'queue_clear' })).status, 400);
  assert.equal((await f.call(token, '/tool', { action: 'share', songId: '../private' })).status, 400);
  f.client.search = async () => { throw Object.assign(new Error('cookie=must-not-leak'), { code: 'MUSIC_UPSTREAM_ERROR', status: 502 }); };
  const failed = await f.call(token, '/search?q=test');
  assert.equal(failed.status, 502);
  assert.ok(!JSON.stringify(failed.body).includes('must-not-leak'));
  const limited = createTenants({ directory: path.join(f.directory, 'limited'), maxUsers: 1 });
  limited.create();
  assert.throws(() => limited.create(), /MUSIC_SERVICE_FULL/);
});

test('slow work has a per-user queue bound and never blocks a second user', async t => {
  const f = await fixture(t), a = await f.register(), b = await f.register();
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  const waiting = Array.from({ length: 20 }, () => f.tenants.run(a, () => gate));
  await assert.rejects(f.tenants.run(a, () => null), /MUSIC_BUSY/);
  assert.equal((await f.call(b, '/status')).status, 200);
  release();
  await Promise.all(waiting);
});

test('cache pressure never discards an active user’s pending QR; idle accounts can be reloaded', async t => {
  const f = await fixture(t);
  let now = Date.now();
  const registry = createTenants({ directory: path.join(f.directory, 'cache-test'), maxCached: 1,
    client: f.client, now: () => now });
  const a = registry.create(), b = registry.create();
  const qr = await registry.run(a, t => t.authority.startQr());
  await assert.rejects(registry.run(b, t => t.authority.status()), /MUSIC_BUSY/);
  assert.equal(await registry.run(a, t => t.authority.pollQr(qr.key).then(r => r.status)), 'success');
  now += 180001;
  assert.equal(await registry.run(b, t => t.authority.status().connected), false);
  now += 180001;
  assert.equal(await registry.run(a, t => t.authority.status().connected), true);
});
