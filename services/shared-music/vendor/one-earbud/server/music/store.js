'use strict';
// one-earbud 听歌 · YOU的网易云登录态。按 secret 处理：文件 0600、原子写；cookie 只出不进任何读接口和日志。

const fs = require('fs');
const path = require('path');

function defaultFile() {
  return path.join(process.env.ONE_EARBUD_DATA_DIR || path.join(__dirname, '..', '..', 'data'), 'netease-auth.json');
}

function createMusicAuthStore(options) {
  const file = (options && options.file) || defaultFile();
  function read() {
    try {
      const v = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (v && typeof v.cookie === 'string' && v.cookie) return v;
    } catch (_) {}
    return null;
  }
  function write(value) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = file + '.tmp-' + process.pid + '-' + Date.now();
    fs.writeFileSync(tmp, JSON.stringify(value, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, file);
    try { fs.chmodSync(file, 0o600); } catch (_) {}
  }
  return {
    cookie() { const v = read(); return v ? v.cookie : ''; },
    // 对外只给这些，不含 cookie
    publicState() {
      const v = read();
      if (!v) return { connected: false };
      return { connected: true, profile: v.profile || null, vip: !!v.vip, connectedAt: v.connectedAt || null };
    },
    save(cookie, profile, vip, nowMs) {
      const prev = read();
      write({ cookie, profile: profile || (prev && prev.profile) || null, vip: vip == null ? !!(prev && prev.vip) : !!vip,
        connectedAt: (prev && prev.connectedAt) || new Date(nowMs || Date.now()).toISOString(), updatedAt: new Date(nowMs || Date.now()).toISOString() });
    },
    // 网易云每次请求可能刷新 cookie；有变化才写
    refreshCookie(cookie) { const v = read(); if (v && cookie && cookie !== v.cookie) { v.cookie = cookie; v.updatedAt = new Date().toISOString(); write(v); } },
    clear() { try { fs.unlinkSync(file); } catch (_) {} }
  };
}

module.exports = { createMusicAuthStore, defaultFile };
