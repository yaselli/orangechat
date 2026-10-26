'use strict';

const crypto = require('node:crypto');
const path = require('node:path');
const { qqBridge } = require('./qq-bridge');
const { createMusicAuthority } = require('../vendor/one-earbud/server/music/authority');

/** QQ keeps its protocol/credentials in the service. The app sees only public music data. */
function createQQAuthority({ root, store, bridge = qqBridge(), now = Date.now }) {
  const pending = new Map();
  async function invoke(method, args = {}, cookie = store.cookie()) {
    const response = await bridge.call(method, { ...args, credential: cookie ? JSON.parse(cookie) : {},
      devicePath: path.join(root, 'device.json') });
    if (cookie && response.credential) store.refreshCookie(JSON.stringify(response.credential));
    return response.result;
  }
  const client = {
    async createQr() {
      const result = await invoke('qr');
      const key = crypto.randomBytes(24).toString('base64url');
      pending.clear();
      pending.set(key, { identifier: result.identifier, expiresAt: now() + 180000 });
      return { key, qrImage: result.qrImage, expiresAt: now() + 180000 };
    },
    async checkQr(key) {
      const entry = pending.get(key);
      if (!entry || now() > entry.expiresAt) { pending.delete(key); return { status: 'expired' }; }
      const result = await invoke('poll', { identifier: entry.identifier });
      if (result.status === 'success') { pending.delete(key); return { status: 'success', cookie: JSON.stringify(result.credential) }; }
      if (result.status === 'expired') pending.delete(key);
      return { status: result.status };
    },
    async account(cookie) {
      const credential = JSON.parse(cookie);
      if (!credential.musicid || !credential.musickey) throw Object.assign(new Error('MUSIC_LOGIN_EXPIRED'), { code: 'MUSIC_LOGIN_EXPIRED' });
      return { profile: { userId: String(credential.musicid), nickname: 'QQ 音乐 · ' + credential.musicid }, vip: false };
    },
    search: (cookie, query, limit) => invoke('search', { query, limit }, cookie),
    songDetail: (cookie, songId) => invoke('song', { songId }, cookie),
    lyrics: async (cookie, songId) => ({ lines: await invoke('lyrics', { songId }, cookie) }),
    playUrl: (cookie, songId) => invoke('url', { songId }, cookie),
    userPlaylists: cookie => invoke('playlists', {}, cookie),
    playlistSongs: (cookie, playlistId) => invoke('source', { source: String(playlistId) }, cookie),
    dailySongs: cookie => invoke('source', { source: 'daily' }, cookie),
  };
  const base = createMusicAuthority({ store, client, queueFile: path.join(root, 'queue.json'), now });
  return { ...base,
    async sources() {
      return { specials: [{ id: 'daily', name: '猜你喜欢' }, { id: 'radar', name: '雷达推荐' },
        { id: 'liked', name: '我喜欢' }], playlists: await client.userPlaylists(store.cookie()) };
    },
    sourceSongs: source => invoke('source', { source }),
    logout() { pending.clear(); return base.logout(); },
  };
}

module.exports = { createQQAuthority };
