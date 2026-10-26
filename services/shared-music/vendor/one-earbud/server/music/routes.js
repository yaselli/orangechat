'use strict';
// 分你一只耳机 · REST 接口。路由自带 /music 前缀（挂在 /api 下就是 /api/music/*），鉴权由外层负责。

function sendError(res, error, tag) {
  const status = Number(error && error.status) || 500;
  const code = (error && error.code) || 'MUSIC_FAILED';
  if (status >= 500) console.warn('[vnext:music]', tag, code, String(error && error.message || '').slice(0, 200));
  return res.status(status).json({ ok: false, error: code, message: status < 500 || code.startsWith('MUSIC_') ? String(error.message || '') : '' });
}

function mountMusicRoutes(router, authority) {
  const nostore = res => res.setHeader('Cache-Control', 'no-store');
  const guard = (res) => { if (!authority) { res.status(503).json({ ok: false, error: 'music_unavailable' }); return false; } return true; };

  router.get('/music/status', (req, res) => { nostore(res); if (!guard(res)) return; res.json(Object.assign({ ok: true }, authority.status())); });
  router.post('/music/login/qr', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json(Object.assign({ ok: true }, await authority.startQr())); } catch (e) { sendError(res, e, 'qr'); }
  });
  router.get('/music/login/qr/:key', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json(Object.assign({ ok: true }, await authority.pollQr(String(req.params.key || '').slice(0, 120)))); } catch (e) { sendError(res, e, 'qr-poll'); }
  });
  router.post('/music/logout', (req, res) => { nostore(res); if (!guard(res)) return; res.json(Object.assign({ ok: true }, authority.logout())); });
  router.get('/music/search', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json({ ok: true, songs: await authority.search(String(req.query.q || ''), Number(req.query.limit) || 20) }); } catch (e) { sendError(res, e, 'search'); }
  });
  router.get('/music/song/:id', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json({ ok: true, song: await authority.song(String(req.params.id || '')) }); } catch (e) { sendError(res, e, 'song'); }
  });
  router.get('/music/song/:id/url', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { const r = await authority.playUrl(String(req.params.id || '')); res.json({ ok: true, url: r.url, trial: r.trial }); } catch (e) { sendError(res, e, 'url'); }
  });
  router.post('/music/now-playing', (req, res) => {
    nostore(res); if (!guard(res)) return;
    const ok = authority.reportNowPlaying(req.body || {});
    res.status(ok ? 200 : 400).json({ ok });
  });
  router.delete('/music/now-playing', (req, res) => { nostore(res); if (!guard(res)) return; authority.clearNowPlaying(); res.json({ ok: true }); });

  // ---------- 播放列表（YOU这边的操作；by 固定为 li，前端不能冒充 ke） ----------
  const Q_STATUS = { queue_forbidden: 403, queue_item_not_found: 404, queue_full: 409 };
  function queueError(res, e) {
    const code = String(e && e.message || 'queue_failed');
    if (/^queue_/.test(code)) return res.status(Q_STATUS[code] || 400).json({ ok: false, error: code });
    return sendError(res, e, 'queue');
  }
  function q(fn) {
    return (req, res) => {
      nostore(res); if (!guard(res)) return;
      try { res.json({ ok: true, queue: fn(req.body || {}, req) }); } catch (e) { queueError(res, e); }
    };
  }
  router.get('/music/queue', q(() => authority.queue.get()));
  router.get('/music/playlists', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json({ ok: true, playlists: await authority.playlists() }); } catch (e) { sendError(res, e, 'playlists'); }
  });
  router.post('/music/queue/import', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    const b = req.body || {};
    try { const r = await authority.importPlaylist(String(b.playlistId || ''), { replace: b.replace === true }); res.json({ ok: true, queue: r.queue, imported: r.imported, skipped: r.skipped }); }
    catch (e) { if (/^queue_/.test(String(e && e.message))) return queueError(res, e); sendError(res, e, 'import'); }
  });
  // Music 入口：YOU的歌单、每日推荐、私人雷达；点一下整份换过去（TA排的没轮到的跟着搬）
  router.get('/music/sources', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json(Object.assign({ ok: true }, await authority.sources())); } catch (e) { sendError(res, e, 'sources'); }
  });
  router.get('/music/source/:id/songs', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { res.json({ ok: true, songs: await authority.sourceSongs(String(req.params.id || '')) }); } catch (e) { sendError(res, e, 'source'); }
  });
  router.post('/music/queue/load', async (req, res) => {
    nostore(res); if (!guard(res)) return;
    try { const r = await authority.loadSource(String((req.body || {}).source || '')); res.json({ ok: true, queue: r.queue, loaded: r.loaded, carried: r.carried }); }
    catch (e) { if (/^queue_/.test(String(e && e.message))) return queueError(res, e); sendError(res, e, 'load'); }
  });
  router.post('/music/queue/append', q(b => authority.queue.append({ by: 'you', songs: Array.isArray(b.songs) ? b.songs.slice(0, 500) : [b.song] }).queue));
  // YOU点开就放：插进列表当前位置；搜歌/卡片上的＋：放在下一首
  router.post('/music/queue/now', q(b => authority.queue.playNow({ by: 'you', song: b.song }).queue));
  router.post('/music/queue/next', q(b => authority.queue.playNext({ by: 'you', song: b.song }).queue));
  router.post('/music/queue/current', q(b => authority.queue.setCurrent({ by: 'you', id: String(b.id || '') })));
  router.post('/music/queue/mode', q(b => authority.queue.setMode({ by: 'you', mode: String(b.mode || '') })));
  router.post('/music/queue/move', q(b => authority.queue.move({ by: 'you', id: String(b.id || ''), to: b.to })));
  router.post('/music/queue/clear', q(() => authority.queue.clear({ by: 'you' })));
  router.delete('/music/queue/:id', q((b, req) => authority.queue.remove({ by: 'you', id: String(req.params.id || '') })));
}

module.exports = { mountMusicRoutes };
