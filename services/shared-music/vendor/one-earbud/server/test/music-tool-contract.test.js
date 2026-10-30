'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createTaMusicTool } = require('../agent/tool-contract');

const song = { songId: '1', title: 'run away', artist: 'Demxntia', coverUrl: 'https://c', durationMs: 197000,
  lyrics: [{ index: 0, timeMs: 50000, text: 'just look up at the stars', trans: '请抬头仰望浩瀚星空' }, { index: 1, timeMs: 54500, text: "you know i'm just a call away", trans: '你深知我随叫随到' }] };
const cap = createTaMusicTool({ authority: {
  search: async () => [{ songId: '1', title: 'run away', artist: 'Demxntia', album: '', durationMs: 197000 }],
  song: async () => song,
  nowPlaying: () => null,
  renderNowPlaying: () => ''
} });

test('share 带词：卡片是 lyric，行按 index 排序去重；同一个 requestId 只发一张', async () => {
  const a = await cap.handler({ action: 'share', songId: '1', lineIndexes: [1, 0, 1], note: '随叫随到', requestId: 'req-00000001' });
  assert.strictEqual(a.delivery.kind, 'music');
  assert.strictEqual(a.delivery.card.kind, 'lyric');
  assert.deepStrictEqual(a.delivery.card.lines.map(l => l.index), [0, 1]);
  const b = await cap.handler({ action: 'share', songId: '1', requestId: 'req-00000001' });
  assert.strictEqual(b, a);
});

test('share 不带词就是整首歌卡；行号越界明确报错', async () => {
  assert.strictEqual((await cap.handler({ action: 'share', songId: '1' })).delivery.card.kind, 'song');
  await assert.rejects(cap.handler({ action: 'share', songId: '1', lineIndexes: [5] }), e => e.code === 'line_index_out_of_range');
});

test('lyrics 给行号和时间；now_playing 没在听就说没在听', async () => {
  const l = await cap.handler({ action: 'lyrics', songId: '1' });
  assert.deepStrictEqual(l.lines[1], { index: 1, time: '0:54', text: "you know i'm just a call away", trans: '你深知我随叫随到' });
  assert.deepStrictEqual(await cap.handler({ action: 'now_playing' }), { ok: true, action: 'now_playing', listening: false });
});

test('play_next / queue_add go through the queue as ke, carry the note, are idempotent by requestId; queue reads back', async () => {
  const fs = require('fs'), os = require('os'), path = require('path');
  const { createMusicQueueStore } = require('../music/queue');
  const queue = createMusicQueueStore({ file: path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'one-earbud-qt-')), 'q.json') });
  queue.append({ by: 'you', songs: [{ songId: '1', title: 'a' }, { songId: '2', title: 'b' }] });
  const cap = createTaMusicTool({ authority: {
    search: async () => [], queue,
    song: async id => ({ songId: id, title: 't' + id, artist: 'x', coverUrl: '', durationMs: 1000, lyrics: [] })
  } });
  let r = await cap.handler({ action: 'play_next', songId: '9', note: '听这首', requestId: 'req-12345678' });
  assert.equal(r.afterCurrent, 1);
  r = await cap.handler({ action: 'play_next', songId: '9', note: '听这首', requestId: 'req-12345678' });
  assert.equal(queue.get().items.length, 3);
  await cap.handler({ action: 'queue_add', songId: '8' });
  const q = await cap.handler({ action: 'queue' });
  assert.deepEqual(q.items.map(x => x.songId), ['1', '9', '2', '8']);
  assert.equal(q.items[1].addedBy, 'ta');
  assert.equal(q.items[1].note, '听这首');
  assert.equal(q.items[0].playing, true);
});

test('play_now puts the song in as current (as ke) and sends a card that carries which item to switch to', async () => {
  const fs = require('fs'), os = require('os'), path = require('path');
  const { createMusicQueueStore } = require('../music/queue');
  const { normalizeMusicCards, describeMusicCards } = require('../music/card');
  const queue = createMusicQueueStore({ file: path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'one-earbud-qn-')), 'q.json') });
  queue.append({ by: 'you', songs: [{ songId: '1', title: 'a' }, { songId: '2', title: 'b' }] });
  const cap = createTaMusicTool({ authority: {
    search: async () => [], queue, nowPlaying: () => ({ state: 'playing' }),
    song: async id => ({ songId: id, title: 't' + id, artist: 'x', coverUrl: 'https://c', durationMs: 1000, lyrics: [] })
  } });
  const r = await cap.handler({ action: 'play_now', songId: '7', note: '给你放这首', requestId: 'req-playnow1' });
  const q = queue.get();
  const cur = q.items.find(x => x.id === q.currentId);
  assert.equal(cur.songId, '7'); assert.equal(cur.addedBy, 'ta');
  assert.deepEqual(q.items.map(x => x.songId), ['1', '7', '2']);
  assert.equal(r.delivery.card.playNow.itemId, cur.id);
  assert.equal(r.youWereListening, true);
  assert.strictEqual(await cap.handler({ action: 'play_now', songId: '7', requestId: 'req-playnow1' }), r);
  const [card] = normalizeMusicCards([r.delivery.card], 'ta');
  assert.equal(card.playNow.itemId, cur.id);
  assert.match(describeMusicCards([card]), /直接给她放了一首歌/);
  assert.equal(normalizeMusicCards([r.delivery.card], 'you')[0].playNow, undefined);
});
