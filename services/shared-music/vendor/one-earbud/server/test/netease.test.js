'use strict';
const test = require('node:test');
const assert = require('node:assert');
const n = require('../music/netease');

test('parseLrc 按时间排序、丢掉没字的行、一行多个时间戳展开', () => {
  const lines = n.parseLrc('[00:05.10]b\n[00:01.00][00:09.5]a\n[00:03.00]\n[ti:x]');
  assert.deepStrictEqual(lines.map(l => [l.timeMs, l.text]), [[1000, 'a'], [5100, 'b'], [9500, 'a']]);
});

test('mergeLyrics 翻译按时间对齐，300ms 以内算同一句，没有翻译留空', () => {
  const out = n.mergeLyrics('[00:10.00]just look up\n[00:14.00]call away\n[00:20.00]run', '[00:10.20]请抬头\n[00:14.50]隔太远不算');
  assert.deepStrictEqual(out.map(l => [l.index, l.text, l.trans]), [[0, 'just look up', '请抬头'], [1, 'call away', ''], [2, 'run', '']]);
});

test('mergeCookie 合并 set-cookie，丢掉 path 之类属性', () => {
  assert.strictEqual(n.mergeCookie('a=1; b=2', ['b=3; Path=/; HttpOnly', 'MUSIC_U=x; Max-Age=10']), 'a=1; b=3; MUSIC_U=x');
});

test('没登录不能搜歌、不能拿播放地址', async () => {
  await assert.rejects(n.search('', '晴天'), e => e.code === 'MUSIC_NOT_LOGGED_IN');
  await assert.rejects(n.playUrl('', '186016'), e => e.code === 'MUSIC_NOT_LOGGED_IN');
});

test('歌曲 id 不是数字直接拒', async () => {
  await assert.rejects(n.lyrics('', '12a'), e => e.code === 'MUSIC_INPUT_ERROR');
});

test('mergeLyrics drops the credit lines at both ends, keeps lyrics with colons in the middle', () => {
  const { mergeLyrics } = require('../music/netease');
  const lrc = ['[00:00.00]作词 : Rainie小雨', '[00:01.00]作曲 : 喻子珊', '[00:25.00]空气都安静着', '[00:30.00]她说：别走', '[01:11.00]你是我夏天里的薄荷',
    '[04:20.00]配唱制作人 : Wenasa黄嘉雯', '[04:21.00]吉他 : 陈麒元', '[04:25.00]出品 : 网易电波 x 百沐娱乐'].join('\n');
  const out = mergeLyrics(lrc, '');
  assert.deepEqual(out.map(x => x.text), ['空气都安静着', '她说：别走', '你是我夏天里的薄荷']);
  assert.deepEqual(out.map(x => x.index), [0, 1, 2]);
});
