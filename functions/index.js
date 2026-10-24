/**
 * 橘瓣 OrangeChat — 公告推送 Cloud Functions
 *
 * 功能：通过 Firebase HTTPS Callable 向已订阅设备广播公告通知。
 * 仅允许 Firebase Auth 已验证令牌中包含 admin: true 的管理员调用。
 * 配置与调用方式见 README.md；请求正文不能授予管理员权限。
 *
 * 接收端 App: AnnouncementMessagingService.kt
 */

const functions = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

// 初始化 Admin SDK（Cloud Functions 环境下自动鉴权）
if (admin.apps.length === 0) {
  admin.initializeApp();
}

/**
 * 发送公告（HTTPS Callable）
 *
 * - title: 通知标题（可选，默认"橘瓣公告"）
 * - body:  通知正文（必填）
 * - url:   点击跳转链接（可选）
 * - topic: 推送主题，默认 "announcement"（App 端订阅该主题即可收到）
 */
exports.sendAnnouncement = functions.onCall(async (request) => {
  // 鉴权
  const auth = request.auth;
  if (!auth) {
    throw new functions.HttpsError("unauthenticated", "需要登录后调用。");
  }
  if (auth.token?.admin !== true) {
    throw new functions.HttpsError("permission-denied", "仅管理员可以发送公告。");
  }

  const data = request.data || {};
  const title = data.title || "橘瓣公告";
  const body = data.body;
  const url = data.url || null;
  const topic = data.topic || "announcement";

  if (typeof body !== "string" || !body.trim()) {
    throw new functions.HttpsError("invalid-argument", "缺少 body（公告正文）。");
  }
  if (typeof title !== "string" || (url !== null && typeof url !== "string") ||
      typeof topic !== "string" || !/^[a-zA-Z0-9\-_.~%]+$/.test(topic)) {
    throw new functions.HttpsError("invalid-argument", "title、url、topic 必须为有效字符串。");
  }

  const message = {
    topic: topic,
    android: {
      priority: "high",
    },
    data: {
      title: title,
      body: body,
      ...(url ? { url: url } : {}),
    },
  };

  try {
    const response = await admin.messaging().send(message);
    logger.info("公告推送成功", { messageId: response });
    return { success: true, messageId: response };
  } catch (error) {
    logger.error("公告推送失败", { code: error.code || "unknown" });
    throw new functions.HttpsError("internal", "推送失败，请查看服务端日志。");
  }
});
