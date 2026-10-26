'use strict';
// one-earbud 听歌 · 「YOU正在听」。只在内存里，靠前端心跳维持；不落盘。
// 规则：
//   在放 → playing；暂停 → paused，暂停超过 10 分钟不再算；关掉播放条 → 清空；
//   心跳断了 60 秒（PWA 退后台就放不了）→ 当YOU已经不在听。

const HEARTBEAT_STALE_MS = 60 * 1000;
const PAUSE_VISIBLE_MS = 10 * 60 * 1000;

function str(v, max) { const s = v == null ? '' : String(v).trim(); return s.slice(0, max); }

function createNowPlaying(options) {
  const staleMs = (options && options.staleMs) || HEARTBEAT_STALE_MS;
  const pauseMs = (options && options.pauseVisibleMs) || PAUSE_VISIBLE_MS;
  let cur = null;
  return {
    // 前端每次状态变化和每 ~20 秒心跳都调这个
    report(input, nowMs) {
      const songId = str(input && input.songId, 32);
      if (!/^\d+$/.test(songId)) return false;
      const playing = !!(input && input.playing);
      const wasPlaying = cur && cur.songId === songId && cur.playing;
      cur = {
        songId, title: str(input.title, 160), artist: str(input.artist, 160),
        positionMs: Math.max(0, Number(input.positionMs) || 0), durationMs: Math.max(0, Number(input.durationMs) || 0),
        playing,
        pausedAt: playing ? null : (wasPlaying || !cur || cur.songId !== songId || cur.pausedAt == null ? nowMs : cur.pausedAt),
        receivedAt: nowMs
      };
      return true;
    },
    clear() { cur = null; },
    // 返回 null（没在听）或 { state: 'playing'|'paused', ...，positionMs 按经过的时间外推 }
    current(nowMs) {
      if (!cur) return null;
      if (nowMs - cur.receivedAt > staleMs) return null;
      if (!cur.playing && cur.pausedAt != null && nowMs - cur.pausedAt > pauseMs) return null;
      const pos = cur.playing ? Math.min(cur.durationMs || Infinity, cur.positionMs + (nowMs - cur.receivedAt)) : cur.positionMs;
      return { state: cur.playing ? 'playing' : 'paused', songId: cur.songId, title: cur.title, artist: cur.artist, positionMs: pos, durationMs: cur.durationMs, pausedAt: cur.pausedAt };
    }
  };
}

module.exports = { createNowPlaying, HEARTBEAT_STALE_MS, PAUSE_VISIBLE_MS };
