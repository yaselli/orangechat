'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { createMusicQueueStore } = require('../music/queue');

function fresh() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'one-earbud-queue-'));
  return createMusicQueueStore({ file: path.join(dir, 'q.json') });
}
const S = (id, t) => ({ songId: String(id), title: t || 'song' + id, artist: 'a' });

test('append keeps order and the first song becomes current', () => {
  const q = fresh();
  q.append({ by: 'you', songs: [S(1), S(2), S(3)] });
  const s = q.get();
  assert.deepEqual(s.items.map(x => x.songId), ['1', '2', '3']);
  assert.equal(s.currentId, s.items[0].id);
  assert.equal(s.items[0].addedBy, 'you');
});

test('ke playNext slots in right after the current song, does not interrupt, keeps his own order', () => {
  const q = fresh();
  q.append({ by: 'you', songs: [S(1), S(2), S(3)] });
  const cur = q.get().currentId;
  q.playNext({ by: 'ta', song: S(10), note: '听这首' });
  q.playNext({ by: 'ta', song: S(11) });
  const s = q.get();
  assert.equal(s.currentId, cur);
  assert.deepEqual(s.items.map(x => x.songId), ['1', '10', '11', '2', '3']);
  assert.equal(s.items[1].addedBy, 'ta');
  assert.equal(s.items[1].note, '听这首');
});

test('once a queued song is reached it no longer holds the "next" slot', () => {
  const q = fresh();
  q.append({ by: 'you', songs: [S(1), S(2)] });
  q.playNext({ by: 'ta', song: S(10) });
  const ten = q.get().items[1];
  q.setCurrent({ by: 'you', id: ten.id });
  q.playNext({ by: 'ta', song: S(11) });
  assert.deepEqual(q.get().items.map(x => x.songId), ['1', '10', '11', '2']);
});

test('ke cannot remove, move, clear, set current or mode', () => {
  const q = fresh();
  q.append({ by: 'you', songs: [S(1), S(2)] });
  const id = q.get().items[1].id;
  for (const fn of ['remove', 'move', 'setCurrent']) assert.throws(() => q[fn]({ by: 'ta', id, to: 0 }), /queue_forbidden/);
  assert.throws(() => q.clear({ by: 'ta' }), /queue_forbidden/);
  assert.throws(() => q.setMode({ by: 'ta', mode: 'one' }), /queue_forbidden/);
  assert.equal(q.get().items.length, 2);
});

test('li can move and remove; removing the current song moves current to its neighbour', () => {
  const q = fresh();
  q.append({ by: 'you', songs: [S(1), S(2), S(3)] });
  const [a, b, c] = q.get().items;
  q.move({ by: 'you', id: c.id, to: 0 });
  assert.deepEqual(q.get().items.map(x => x.songId), ['3', '1', '2']);
  q.remove({ by: 'you', id: a.id });
  assert.equal(q.get().currentId, b.id);
});

test('mode is validated; notes only kept for ke; bad song ids refused', () => {
  const q = fresh();
  q.setMode({ by: 'you', mode: 'shuffle' });
  assert.equal(q.get().mode, 'shuffle');
  assert.throws(() => q.setMode({ by: 'you', mode: 'loop' }), /queue_mode_invalid/);
  const { added } = q.append({ by: 'you', song: S(5), note: 'x' });
  assert.equal(added[0].note, '');
  assert.throws(() => q.append({ by: 'you', song: { songId: 'abc' } }), /queue_song_invalid/);
});

test('li playNow slots right after current, becomes current, ke queued songs wait behind it', () => {
  const q = fresh();
  q.append({ by: 'you', songs: [S(1), S(2)] });
  q.playNext({ by: 'ta', song: S(10) });
  const { added } = q.playNow({ by: 'you', song: S(20) });
  const got = q.get();
  assert.deepEqual(got.items.map(x => x.songId), ['1', '20', '10', '2']);
  assert.equal(got.currentId, added[0].id);
  // same song as current again: no duplicate
  assert.equal(q.playNow({ by: 'you', song: S(20) }).added.length, 0);
  // li playNext goes right after current
  q.playNext({ by: 'you', song: S(30) });
  assert.deepEqual(q.get().items.map(x => x.songId), ['1', '20', '10', '30', '2']);
});

test('replaceWith: new list takes over, first song is current, my unplayed songs move right after it', () => {
  const fs2 = require('fs'), os2 = require('os'), path2 = require('path');
  const q = createMusicQueueStore({ file: path2.join(fs2.mkdtempSync(path2.join(os2.tmpdir(), 'one-earbud-qr-')), 'q.json') });
  q.append({ by: 'you', songs: [{ songId: '1' }, { songId: '2' }, { songId: '3' }] });
  q.playNext({ by: 'ta', song: { songId: '9' }, note: '听这首' });
  const r = q.replaceWith({ by: 'you', songs: [{ songId: '20' }, { songId: '21' }] });
  assert.deepEqual(r.queue.items.map(x => x.songId), ['20', '9', '21']);
  assert.equal(r.queue.items.find(x => x.id === r.queue.currentId).songId, '20');
  assert.equal(r.queue.items[1].note, '听这首');
  assert.equal(r.carried, 1);
  assert.throws(() => q.replaceWith({ by: 'ta', songs: [{ songId: '5' }] }), /queue_forbidden/);
  assert.throws(() => q.replaceWith({ by: 'you', songs: [] }), /queue_empty_source/);
});
