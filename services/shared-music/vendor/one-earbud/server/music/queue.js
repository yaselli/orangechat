'use strict';
// one-earbud 听歌 · 播放列表（服务器上一份，YOU和TA都能往里放）。
// 规矩：TA（ke）只能往后加，不打断YOU正在听的那首，也不删、不挪YOU的歌；
// YOU（li）什么都能动。播放到哪儿、什么模式，由YOU的播放器报上来。

const fs = require('fs');
const path = require('path');

const MODES = ['seq', 'shuffle', 'one'];
const WHO = ['you', 'ta'];
const MAX_ITEMS = 500;
const NOTE_MAX = 120;

function defaultFile() {
  return path.join(process.env.ONE_EARBUD_DATA_DIR || path.join(__dirname, '..', '..', 'data'), 'queue.json');
}
function str(v, max) { return String(v == null ? '' : v).trim().slice(0, max); }
function makeId() { return 'q-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8); }

function normalizeSong(song) {
  const s = song && typeof song === 'object' ? song : {};
  const songId = str(s.songId, 32);
  if (!/^\d+$/.test(songId)) throw new Error('queue_song_invalid');
  return {
    songId,
    title: str(s.title, 200),
    artist: str(s.artist, 200),
    coverUrl: str(s.coverUrl, 600),
    durationMs: Math.max(0, Math.round(Number(s.durationMs) || 0))
  };
}

function createMusicQueueStore(options) {
  const file = (options && options.file) || defaultFile();
  const now = (options && options.now) || function () { return Date.now(); };

  function empty() { return { items: [], currentId: null, mode: 'seq', updatedAt: null }; }
  function read() {
    try {
      const v = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (v && Array.isArray(v.items)) return { ...empty(), ...v, mode: MODES.includes(v.mode) ? v.mode : 'seq' };
    } catch (_) {}
    return empty();
  }
  function write(q) {
    q.updatedAt = new Date(now()).toISOString();
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const tmp = file + '.tmp-' + process.pid + '-' + Date.now();
    fs.writeFileSync(tmp, JSON.stringify(q, null, 2));
    fs.renameSync(tmp, file);
    return q;
  }
  function who(v) { const w = String(v || ''); if (!WHO.includes(w)) throw new Error('queue_actor_invalid'); return w; }
  function mataItem(song, by, note) {
    return { id: makeId(), ...normalizeSong(song), addedBy: by, note: by === 'ta' ? str(note, NOTE_MAX) : '', addedAt: new Date(now()).toISOString() };
  }
  function room(q, n) { if (q.items.length + n > MAX_ITEMS) throw new Error('queue_full'); }

  return {
    MODES,
    get() { return read(); },

    // 加到最后。YOU搜歌时的「＋」、导入歌单都走这个。
    append(input) {
      const by = who(input && input.by);
      const q = read();
      const songs = Array.isArray(input.songs) ? input.songs : [input.song];
      room(q, songs.length);
      const added = songs.map(s => mataItem(s, by, input.note));
      q.items.push(...added);
      if (!q.currentId && q.items.length) q.currentId = q.items[0].id;
      write(q);
      return { queue: q, added };
    },

    // 「放下一首」：插在正在放的那首后面、也排在TA之前已经插进去还没轮到的歌后面，所以TA连塞几首会按顺序排好。
    // 不改 currentId，所以不会打断YOU正在听的。
    playNext(input) {
      const by = who(input && input.by);
      const q = read();
      room(q, 1);
      const item = mataItem(input.song, by, input.note);
      let at = q.items.findIndex(x => x.id === q.currentId);
      if (at < 0) { q.items.push(item); if (!q.currentId) q.currentId = item.id; write(q); return { queue: q, added: [item] }; }
      at += 1;
      while (at < q.items.length && q.items[at].queuedNext) at += 1;
      item.queuedNext = true;
      q.items.splice(at, 0, item);
      write(q);
      return { queue: q, added: [item] };
    },

    // 2026-09-27 YOU定的 Music 2.1：任何一首只要被YOU放了，就插进列表、成为当前这首。
    // 插在正在放的那首紧后面（不跳过TA排的歌，TA排的照样在它后面等着），然后直接改 currentId。
    playNow(input) {
      const by = who(input && input.by);
      const q = read();
      const song = normalizeSong(input.song);
      const cur = q.items.find(x => x.id === q.currentId);
      if (cur && cur.songId === song.songId) return { queue: q, added: [] };
      room(q, 1);
      const item = mataItem(input.song, by, input.note);
      const at = q.items.findIndex(x => x.id === q.currentId);
      q.items.splice(at < 0 ? q.items.length : at + 1, 0, item);
      q.currentId = item.id;
      write(q);
      return { queue: q, added: [item] };
    },

    // 换歌单：整份换成新的，新的第一首成为当前；
    // TA排的、还没轮到的歌（在当前这首后面、addedBy ke）跟着搬家，接在新列表第一首后面，带着TA 的图标和那句话。
    replaceWith(input) {
      if (who(input && input.by) !== 'you') throw new Error('queue_forbidden');
      const q = read();
      const songs = (Array.isArray(input.songs) ? input.songs : []).filter(s => s && /^\d+$/.test(String(s.songId || '')));
      if (!songs.length) throw new Error('queue_empty_source');
      const at = q.items.findIndex(x => x.id === q.currentId);
      const carry = q.items.slice(at + 1).filter(x => x.addedBy === 'ta');
      const fresh = songs.slice(0, Math.max(1, MAX_ITEMS - carry.length)).map(s => mataItem(s, 'you', ''));
      q.items = [fresh[0], ...carry, ...fresh.slice(1)];
      q.currentId = fresh[0].id;
      write(q);
      return { queue: q, added: fresh, carried: carry.length };
    },

    // 只有YOU能删、能挪。
    remove(input) {
      if (who(input && input.by) !== 'you') throw new Error('queue_forbidden');
      const q = read();
      const at = q.items.findIndex(x => x.id === input.id);
      if (at < 0) throw new Error('queue_item_not_found');
      q.items.splice(at, 1);
      if (q.currentId === input.id) q.currentId = q.items.length ? q.items[Math.min(at, q.items.length - 1)].id : null;
      return write(q);
    },
    move(input) {
      if (who(input && input.by) !== 'you') throw new Error('queue_forbidden');
      const q = read();
      const from = q.items.findIndex(x => x.id === input.id);
      if (from < 0) throw new Error('queue_item_not_found');
      const [it] = q.items.splice(from, 1);
      const to = Math.max(0, Math.min(q.items.length, Math.round(Number(input.to) || 0)));
      q.items.splice(to, 0, it);
      return write(q);
    },
    clear(input) {
      if (who(input && input.by) !== 'you') throw new Error('queue_forbidden');
      return write(empty());
    },

    // YOU的播放器报：现在放到哪首了、换了什么模式。轮到的那首就不再算「插队中」。
    setCurrent(input) {
      if (who(input && input.by) !== 'you') throw new Error('queue_forbidden');
      const q = read();
      const it = q.items.find(x => x.id === input.id);
      if (!it) throw new Error('queue_item_not_found');
      q.currentId = it.id; delete it.queuedNext;
      return write(q);
    },
    setMode(input) {
      if (who(input && input.by) !== 'you') throw new Error('queue_forbidden');
      if (!MODES.includes(input.mode)) throw new Error('queue_mode_invalid');
      const q = read(); q.mode = input.mode;
      return write(q);
    }
  };
}

module.exports = { createMusicQueueStore, MODES, MAX_ITEMS, defaultFile };
