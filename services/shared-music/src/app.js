'use strict';

const express = require('express');
const crypto = require('node:crypto');
const { createTenants, failure } = require('./tenants');

const ACTIONS = new Set(['search', 'lyrics', 'share', 'now_playing', 'queue', 'play_next', 'queue_add', 'play_now']);
function validateTool(input) {
  if (!input || !ACTIONS.has(input.action)) throw failure('unknown_action', 400);
  if (input.query != null && (typeof input.query !== 'string' || input.query.length > 80)) throw failure('query_invalid', 400);
  if (input.songId != null && !/^\d{1,20}$/.test(String(input.songId))) throw failure('song_id_required', 400);
  if (input.note != null && (typeof input.note !== 'string' || input.note.length > 300)) throw failure('note_invalid', 400);
  if (input.requestId != null && (typeof input.requestId !== 'string' || !/^[A-Za-z0-9_-]{8,80}$/.test(input.requestId))) throw failure('request_id_invalid', 400);
  if (input.lineIndexes != null && (!Array.isArray(input.lineIndexes) || input.lineIndexes.length > 8 || input.lineIndexes.some(n => !Number.isInteger(n) || n < 0))) throw failure('line_index_invalid', 400);
  return input;
}

/** Bounded per-IP windows. Neither tokens, cookies, queries nor response bodies are logged. */
function limiter(maximum, windowMs, now = Date.now) {
  const windows = new Map();
  return (req, res, next) => {
    const time = now();
    for (const [key, value] of windows) if (time - value.at >= windowMs) windows.delete(key);
    const key = req.ip;
    let value = windows.get(key);
    if (!value) {
      if (windows.size >= 10000) return res.status(503).json({ ok: false, error: 'MUSIC_BUSY' });
      value = { at: time, count: 0 }; windows.set(key, value);
    }
    if (++value.count > maximum) return res.status(429).json({ ok: false, error: 'MUSIC_RATE_LIMITED' });
    next();
  };
}

function createApp(options) {
  const registrationKey = String(options.registrationKey || '');
  if (!/^[\x21-\x7e]{16,256}$/.test(registrationKey)) throw new Error('Set ONE_EARBUD_TOKEN to 16–256 visible ASCII characters');
  const expectedKey = crypto.createHash('sha256').update(registrationKey).digest();
  const tenants = options.tenants || createTenants(options);
  const app = express();
  app.disable('x-powered-by');
  if (options.trustProxy) app.set('trust proxy', options.trustProxy);
  app.use(express.json({ limit: '256kb' }));
  app.use('/api/music', (_req, res, next) => {
    res.set({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); next();
  });
  app.get('/health', (_req, res) => res.json({ ok: true, service: 'watermelon-shared-music' }));
  app.post('/api/music/sessions', limiter(60, 60000), (req, res, next) => {
    const supplied = String(req.headers.authorization || '').replace(/^Bearer /, '');
    const actual = crypto.createHash('sha256').update(supplied).digest();
    if (!crypto.timingSafeEqual(actual, expectedKey)) return next(failure('MUSIC_ACCESS_DENIED', 401));
    next();
  }, limiter(options.registrationsPerHour || 6, 3600000), (_req, res, next) => {
    try { res.status(201).json({ ok: true, token: tenants.create() }); } catch (error) { next(error); }
  });
  const token = req => String(req.headers.authorization || '').replace(/^Bearer /, '');
  app.use('/api/music', limiter(600, 60000));
  function route(method, name, execute) {
    app[method]('/api/music' + name, async (req, res, next) => {
      try {
        const result = await tenants.run(token(req), tenant => execute(tenant, req), req.get('X-Music-Platform') || 'netease');
        res.json({ ok: true, ...result });
      } catch (error) { next(error); }
    });
  }
  route('get', '/status', (t, r) => ({ ...t.authority.status(), platform: r.get('X-Music-Platform') || 'netease' }));
  route('post', '/login/qr', t => t.authority.startQr());
  route('get', '/login/qr/:key', (t, r) => t.authority.pollQr(String(r.params.key).slice(0, 120)));
  route('post', '/logout', t => {
    t.authority.logout(); t.authority.queue.clear({ by: 'you' }); t.reset();
    return { connected: false };
  });
  route('get', '/search', async (t, r) => ({ songs: await t.authority.search(String(r.query.q || '').slice(0, 80), 20) }));
  route('get', '/song/:id', async (t, r) => ({ song: await t.authority.song(r.params.id) }));
  route('get', '/song/:id/url', (t, r) => t.authority.playUrl(r.params.id));
  route('post', '/now-playing', (t, r) => {
    if (!t.authority.reportNowPlaying(r.body)) throw failure('MUSIC_INPUT_ERROR', 400);
    return {};
  });
  route('delete', '/now-playing', t => { t.authority.clearNowPlaying(); return {}; });
  route('get', '/context', t => ({ text: t.authority.renderNowPlaying() }));
  route('get', '/sources', t => t.authority.sources());
  route('get', '/source/:id/songs', async (t, r) => ({ songs: await t.authority.sourceSongs(r.params.id) }));
  route('get', '/queue', t => ({ queue: t.authority.queue.get() }));
  route('post', '/queue/load', (t, r) => t.authority.loadSource(r.body.source));
  route('post', '/queue/import', (t, r) => t.authority.importPlaylist(r.body.playlistId, { replace: r.body.replace === true }));
  for (const [name, method] of [['now', 'playNow'], ['next', 'playNext'], ['append', 'append']]) {
    route('post', '/queue/' + name, (t, r) => ({ queue: t.authority.queue[method]({ by: 'you', song: r.body.song }).queue }));
  }
  route('post', '/queue/current', (t, r) => ({ queue: t.authority.queue.setCurrent({ by: 'you', id: r.body.id }) }));
  route('post', '/queue/mode', (t, r) => ({ queue: t.authority.queue.setMode({ by: 'you', mode: r.body.mode }) }));
  route('post', '/queue/move', (t, r) => ({ queue: t.authority.queue.move({ by: 'you', id: r.body.id, to: r.body.to }) }));
  route('delete', '/queue/:id', (t, r) => ({ queue: t.authority.queue.remove({ by: 'you', id: r.params.id }) }));
  route('post', '/tool', (t, r) => t.tool.handler(validateTool(r.body)));
  app.delete('/api/music/session', async (req, res, next) => {
    try { res.json(await tenants.revoke(token(req))); } catch (error) { next(error); }
  });
  app.use((error, _req, res, _next) => {
    const candidate = Number(error.status) || (error.knownToolOutcome ? 400 : 500);
    const status = candidate >= 400 && candidate <= 599 ? candidate : 500;
    const code = /^[A-Za-z0-9_]+$/.test(error.code || '') ? error.code : 'MUSIC_FAILED';
    // Never forward an upstream URL, stack, cookie or raw response through an error message.
    res.status(status).json({ ok: false, error: code, message: status < 500 ? code : '音乐服务暂时不可用，请稍后重试' });
  });
  return app;
}

module.exports = { createApp, validateTool };
