'use strict';
// 聊天里的音乐卡：YOU挂在输入框上发来的（整首歌 / 几句词），和TA share 出去的是同一种形状。
// 这里只做整形和转成给TA读的文字，不连网易云。

const MAX_CARDS = 4;
const MAX_LINES = 8;

function str(v, max) { const s = v == null ? '' : String(v).trim(); return s.slice(0, max); }
function fmt(ms) { const s = Math.floor((ms || 0) / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); }

function normalizeMusicCards(input, from) {
  if (!Array.isArray(input)) return [];
  const out = [];
  for (const raw of input) {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue;
    const songId = str(raw.songId, 32);
    if (!/^\d+$/.test(songId)) continue;
    const lines = (Array.isArray(raw.lines) ? raw.lines : []).filter(l => l && typeof l === 'object').slice(0, MAX_LINES).map(l => ({
      index: Math.max(0, Math.floor(Number(l.index) || 0)), timeMs: Math.max(0, Number(l.timeMs) || 0), text: str(l.text, 300), trans: str(l.trans, 300)
    })).filter(l => l.text);
    out.push({
      kind: lines.length ? 'lyric' : 'song', songId, title: str(raw.title, 160) || '未命名', artist: str(raw.artist, 160),
      coverUrl: /^https:\/\//.test(str(raw.coverUrl, 600)) ? str(raw.coverUrl, 600) : '', durationMs: Math.max(0, Number(raw.durationMs) || 0),
      lines, note: str(raw.note, 300), from: from === 'ta' ? 'ta' : 'you',
      // TA直接放的那张（play_now）：记着是列表里哪一项、什么时候发的，前端据此只在刚到的时候切一次
      ...(from === 'ta' && raw.playNow && typeof raw.playNow === 'object' ? { playNow: { itemId: str(raw.playNow.itemId, 64), at: Math.max(0, Number(raw.playNow.at) || 0) } } : {})
    });
    if (out.length >= MAX_CARDS) break;
  }
  return out;
}

function describeMusicCards(cards) {
  return (cards || []).map(c => {
    const who = c.from === 'ta' ? (process.env.ONE_EARBUD_TA_NAME || 'TA') : (process.env.ONE_EARBUD_YOU_NAME || 'YOU');
    const head = c.title + (c.artist ? ' — ' + c.artist : '') + '（songId ' + c.songId + '）';
    if (c.kind === 'song') return c.playNow ? '[' + who + '直接给她放了一首歌：' + head + ']' : '[' + who + '分享了一首歌：' + head + ']';
    const body = c.lines.map(l => '  ' + l.text + (l.trans ? '（' + l.trans + '）' : '')).join('\n');
    return '[' + who + '划了几句歌词：' + head + ' · ' + fmt(c.lines[0].timeMs) + '\n' + body + ']';
  }).join('\n');
}

module.exports = { normalizeMusicCards, describeMusicCards, MAX_CARDS };
