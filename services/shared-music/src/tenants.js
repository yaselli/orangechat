'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createMusicAuthority } = require('../vendor/one-earbud/server/music/authority');
const { createMusicAuthStore } = require('../vendor/one-earbud/server/music/store');
const { createQQAuthority } = require('./qq');
const { createTaMusicTool } = require('../vendor/one-earbud/server/agent/tool-contract');

function failure(code, status = 401) {
  return Object.assign(new Error(code), { code, status });
}

/** A random installation credential selects the tenant; callers never supply a user ID or disk path. */
function createTenants({ directory, maxUsers = 1000, maxCached = 100, client, qqFactory = createQQAuthority, now = Date.now } = {}) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  const entries = new Map();
  let count = fs.readdirSync(directory).filter(x => /^[a-f0-9]{64}$/.test(x)).length;
  function create() {
    if (count >= maxUsers) throw failure('MUSIC_SERVICE_FULL', 503);
    const token = crypto.randomBytes(32).toString('base64url');
    const id = crypto.createHash('sha256').update(token).digest('hex');
    const root = path.join(directory, id);
    fs.mkdirSync(root, { mode: 0o700 });
    fs.writeFileSync(path.join(root, 'session.json'), JSON.stringify({ createdAt: Date.now() }), { mode: 0o600 });
    count++;
    return token; // Never persisted in plaintext or returned by another endpoint.
  }
  function resolve(token) {
    if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw failure('UNAUTHORIZED');
    const id = crypto.createHash('sha256').update(token).digest('hex');
    const root = path.join(directory, id);
    if (!fs.existsSync(path.join(root, 'session.json'))) throw failure('UNAUTHORIZED');
    let tenant = entries.get(id);
    if (!tenant) {
      if (entries.size >= maxCached) {
        // A QR remains valid for three minutes; evicting a recently used authority would lose
        // that user's QR keys, listening snapshot and retry receipts while they are still active.
        const idle = [...entries].find(([, value]) => value.pending === 0 && now() - value.lastUsed > 180000);
        if (!idle) throw failure('MUSIC_BUSY', 503);
        entries.delete(idle[0]);
      }
      const store = createMusicAuthStore({ file: path.join(root, 'netease-auth.json') });
      tenant = { id, root, pending: 0, tail: Promise.resolve() };
      tenant.reset = () => {
        tenant.authority = createMusicAuthority({ store, queueFile: path.join(root, 'queue.json'), client });
        tenant.tool = createTaMusicTool({ authority: tenant.authority });
      };
      tenant.reset();
      tenant.qq = () => {
        if (!tenant.qqTenant) {
          const qqRoot = path.join(root, 'qq');
          fs.mkdirSync(qqRoot, { recursive: true, mode: 0o700 });
          const qqStore = createMusicAuthStore({ file: path.join(qqRoot, 'auth.json') });
          const value = { id, root: qqRoot };
          value.reset = () => {
            value.authority = qqFactory({ root: qqRoot, store: qqStore, now });
            value.tool = createTaMusicTool({ authority: value.authority });
          };
          value.reset(); tenant.qqTenant = value;
        }
        return tenant.qqTenant;
      };
      entries.set(id, tenant);
    }
    // Refresh LRU order without touching credentials or listening timestamps.
    entries.delete(id);
    tenant.lastUsed = now();
    entries.set(id, tenant);
    return tenant;
  }
  async function run(token, operation, platform = 'netease') {
    if (!['netease', 'qq'].includes(platform)) throw failure('MUSIC_INPUT_ERROR', 400);
    const tenant = resolve(token);
    if (tenant.pending >= 20) throw failure('MUSIC_BUSY', 429);
    tenant.pending++;
    const task = tenant.tail.then(async () => {
      if (!fs.existsSync(path.join(tenant.root, 'session.json'))) throw failure('UNAUTHORIZED');
      return operation(platform === 'qq' ? tenant.qq() : tenant);
    });
    tenant.tail = task.catch(() => {});
    try { return await task; } finally { tenant.pending--; }
  }
  async function revoke(token) {
    return run(token, tenant => {
      tenant.authority.logout();
      tenant.qqTenant?.authority.logout();
      fs.rmSync(tenant.root, { recursive: true });
      entries.delete(tenant.id);
      count--;
      return { ok: true };
    });
  }
  return { create, run, revoke };
}

module.exports = { createTenants, failure };
