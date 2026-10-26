'use strict';

const { spawn } = require('node:child_process');
const path = require('node:path');

/** One private stdio worker, bounded requests; credentials never enter argv or a public port. */
function createQQBridge({ executable = process.env.QQ_PYTHON || 'python3', script = path.join(__dirname, 'qq_worker.py') } = {}) {
  let worker, buffer = '', sequence = 0;
  const pending = new Map();
  const fail = code => Object.assign(new Error(code), { code, status: ({ MUSIC_INPUT_ERROR: 400,
    MUSIC_NOT_LOGGED_IN: 401, MUSIC_LOGIN_EXPIRED: 401, MUSIC_QQ_VERIFICATION_REQUIRED: 403,
    MUSIC_QQ_ACCOUNT_RESTRICTED: 403, MUSIC_RATE_LIMITED: 429 })[code] || 503 });
  function stop() {
    const old = worker;
    worker = null;
    buffer = '';
    old?.kill();
    for (const task of pending.values()) { clearTimeout(task.timer); task.reject(fail('MUSIC_QQ_UNAVAILABLE')); }
    pending.clear();
  }
  function start() {
    if (worker) return;
    const child = spawn(executable, ['-u', script], { stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true });
    worker = child;
    child.stdout.setEncoding('utf8');
    child.on('error', () => { if (worker === child) stop(); });
    child.on('exit', () => { if (worker === child) stop(); });
    child.stdin.on('error', () => { if (worker === child) stop(); });
    child.stdout.on('data', chunk => {
      if (worker !== child) return;
      buffer += chunk.toString('utf8');
      if (Buffer.byteLength(buffer) > 4_000_000) return stop();
      let at;
      while ((at = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, at); buffer = buffer.slice(at + 1);
        let response;
        try { response = JSON.parse(line); } catch (_) { stop(); return; }
        const task = pending.get(response.id);
        if (!task) continue;
        pending.delete(response.id); clearTimeout(task.timer);
        if (response.ok) task.resolve(response);
        else task.reject(fail(new Set(['MUSIC_LOGIN_EXPIRED', 'MUSIC_NOT_LOGGED_IN', 'MUSIC_UNAVAILABLE',
          'MUSIC_RATE_LIMITED', 'MUSIC_QQ_VERIFICATION_REQUIRED', 'MUSIC_QQ_ACCOUNT_RESTRICTED',
          'MUSIC_QQ_UNAVAILABLE', 'MUSIC_INPUT_ERROR']).has(response.error) ? response.error : 'MUSIC_UPSTREAM_ERROR'));
      }
    });
    // An idle worker must not keep Node alive; active requests have their own deadline timers.
    child.unref(); child.stdin.unref(); child.stdout.unref();
  }
  function call(method, params = {}) {
    if (pending.size >= 32) return Promise.reject(fail('MUSIC_BUSY'));
    start();
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(fail('MUSIC_TIMEOUT')); }, 16000);
      pending.set(id, { resolve, reject, timer });
      worker.stdin.write(JSON.stringify({ id, method, ...params }) + '\n');
    });
  }
  return { call, close: stop };
}

let shared;
module.exports = { createQQBridge, qqBridge: () => shared ||= createQQBridge() };
