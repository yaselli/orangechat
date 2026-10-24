const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

// Execute the real entry point with network-free Firebase adapters.
function load({ sendError } = {}) {
  const sent = [];
  const logs = [];
  class HttpsError extends Error {
    constructor(code, message) {
      super(message);
      this.code = code;
    }
  }
  const sandbox = {
    exports: {},
    require(name) {
      if (name === "firebase-functions/v2/https") return { onCall: (handler) => handler, HttpsError };
      if (name === "firebase-functions/logger") return {
        info: (...args) => logs.push(args), error: (...args) => logs.push(args),
      };
      if (name === "firebase-admin") return {
        apps: [{}],
        messaging: () => ({ send: async (message) => {
          sent.push(JSON.parse(JSON.stringify(message)));
          if (sendError) throw sendError;
          return "test-message-id";
        } }),
      };
      throw new Error(`Unexpected dependency: ${name}`);
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../index.js"), "utf8"), sandbox);
  return { call: sandbox.exports.sendAnnouncement, sent, logs };
}

const administrator = { uid: "admin-user", token: { admin: true } };

test("anonymous calls never send an announcement", async () => {
  const { call, sent } = load();
  await assert.rejects(call({ data: { body: "test" } }), { code: "unauthenticated" });
  assert.equal(sent.length, 0);
});

for (const token of [{}, { admin: false }, { admin: "true" }, { admin: 1 }]) {
  test(`ordinary or non-boolean admin claims are rejected: ${JSON.stringify(token)}`, async () => {
    const { call, sent } = load();
    await assert.rejects(call({
      auth: { uid: "ordinary-user", token }, data: { body: "test", admin: true },
    }), { code: "permission-denied" });
    assert.equal(sent.length, 0);
  });
}

test("missing token claims fail closed", async () => {
  const { call, sent } = load();
  await assert.rejects(call({ auth: { uid: "ordinary-user" }, data: { body: "test" } }), {
    code: "permission-denied",
  });
  assert.equal(sent.length, 0);
});

test("administrator can send with existing defaults", async () => {
  const { call, sent, logs } = load();
  const result = await call({ auth: administrator, data: { body: "announcement" } });
  assert.equal(result.success, true);
  assert.equal(result.messageId, "test-message-id");
  assert.deepEqual(sent, [{
    topic: "announcement", android: { priority: "high" },
    data: { title: "橘瓣公告", body: "announcement" },
  }]);
  assert.equal(JSON.stringify(logs).includes('"body"'), false);
});

test("administrator can supply title link and topic", async () => {
  const { call, sent } = load();
  await call({ auth: administrator, data: {
    title: "Title", body: "Body", url: "https://example.com/notice", topic: "test-notices",
  } });
  assert.deepEqual(sent[0].data, { title: "Title", body: "Body", url: "https://example.com/notice" });
  assert.equal(sent[0].topic, "test-notices");
});

for (const data of [undefined, {}, { body: 5 }, { body: "   " },
  { body: "test", title: {} }, { body: "test", url: {} }, { body: "test", topic: "bad topic" }]) {
  test(`invalid announcement does not call FCM: ${JSON.stringify(data)}`, async () => {
    const { call, sent } = load();
    await assert.rejects(call({ auth: administrator, data }), { code: "invalid-argument" });
    assert.equal(sent.length, 0);
  });
}

test("FCM failure returns a generic error without leaking SDK details", async () => {
  const sendError = Object.assign(new Error("private SDK details"), { code: "messaging/internal-error" });
  const { call, sent, logs } = load({ sendError });
  await assert.rejects(call({ auth: administrator, data: { body: "test" } }), (error) => {
    assert.equal(error.code, "internal");
    assert.equal(error.message.includes("private SDK details"), false);
    return true;
  });
  assert.equal(sent.length, 1);
  assert.equal(JSON.stringify(logs).includes("private SDK details"), false);
});
