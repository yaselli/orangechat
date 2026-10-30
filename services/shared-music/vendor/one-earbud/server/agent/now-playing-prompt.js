'use strict';
// 分你一只耳机 · 每轮注入给 TA 的「YOU 正在听」。
// 规则：在放 → 带；暂停 → 带，但暂停超过 10 分钟不带；心跳断了 60 秒（YOU 关了 App）→ 不带；关掉听歌栏 → 不带。
// 没在听就返回空字符串：这一轮 TA 的 prompt 里什么都没有，而不是一句「没在听」。
// 用法：在你拼 prompt 的地方，把返回值接在每轮动态上下文的末尾（每轮现拼，不进历史）。

const BLOCK_TAG = '[music.now_playing.fresh]';

function renderNowPlayingBlock(authority) {
  try {
    const text = authority.renderNowPlaying();
    return text ? BLOCK_TAG + '\n' + text + '\n\n' : '';
  } catch (error) {
    console.warn('[one-earbud:prompt] render_failed', String(error && error.message || error).slice(0, 160));
    return '';
  }
}

module.exports = { renderNowPlayingBlock, BLOCK_TAG };
