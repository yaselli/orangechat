'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { normalizeMusicCards, describeMusicCards } = require('../music/card');

test('整形：丢掉坏卡、非 https 封面清空、最多 4 张、有词才算 lyric', () => {
  const cards = normalizeMusicCards([
    { songId: '1901371647', title: 'run away', artist: 'Demxntia', coverUrl: 'http://x', lines: [{ index: 1, timeMs: 54500, text: "you know i'm just a call away", trans: '你深知我随叫随到' }] },
    { songId: 'bad' }, null, 'x',
    { songId: '186016', title: '晴天', artist: '周杰伦', lines: [{ text: '' }] }
  ]);
  assert.deepStrictEqual(cards.map(c => [c.kind, c.coverUrl, c.from]), [['lyric', '', 'you'], ['song', '', 'you']]);
  assert.strictEqual(normalizeMusicCards(Array.from({ length: 9 }, () => ({ songId: '1' }))).length, 4);
});

test('转成给我读的文字', () => {
  const t = describeMusicCards(normalizeMusicCards([
    { songId: '1901371647', title: 'run away', artist: 'Demxntia', lines: [{ index: 1, timeMs: 54500, text: "you know i'm just a call away", trans: '你深知我随叫随到' }] },
    { songId: '186016', title: '晴天', artist: '周杰伦' }
  ]));
  assert.strictEqual(t, "[YOU划了几句歌词：run away — Demxntia（songId 1901371647） · 0:54\n  you know i'm just a call away（你深知我随叫随到）]\n[YOU分享了一首歌：晴天 — 周杰伦（songId 186016）]");
});
