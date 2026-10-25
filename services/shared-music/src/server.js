'use strict';
const path = require('node:path');
const { createApp } = require('./app');
process.umask(0o077);
const directory = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
const app = createApp({ directory, maxUsers: Number(process.env.MAX_USERS) || 1000,
  maxCached: Number(process.env.MAX_ACTIVE_USERS) || 100,
  trustProxy: process.env.TRUST_PROXY === '1' ? 1 : false });
const server = app.listen(Number(process.env.PORT) || 8787, '0.0.0.0', () => console.log('Shared music service ready'));
server.requestTimeout = 30000;
server.headersTimeout = 15000;
