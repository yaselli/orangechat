# 公告推送云函数

`sendAnnouncement` 是 Firebase HTTPS Callable，不是自定义 Bearer 密钥接口。它要求已登录，且由 Firebase 验证的 ID token 中严格包含布尔值 `admin: true`。普通登录用户、请求正文中的 `admin`、字符串 `"true"` 均不能获得权限。

管理员 claim 只能在可信服务端通过 Firebase Admin SDK 设置。为现有用户授权时先读取并保留其其他 custom claims，再添加 `admin: true`；不要在 App 中放服务账号密钥或提供自行提权接口。修改 claim 后，管理员客户端需要刷新 ID token（或重新登录）再调用。

使用 Firebase 客户端 SDK 的 `httpsCallable(functions, "sendAnnouncement")`，传入 `{ title, body, url?, topic? }`。`body` 必填，默认标题为“橘瓣公告”，默认主题为 `announcement`；客户端 SDK 会附带登录令牌。

本地运行 `npm test`，测试不连接 Firebase、不发送通知，也不需要服务账号。部署仍使用现有 `npm run deploy`，需要目标项目的 Firebase 部署权限。仓库中的修复不会自动更新已部署实例；本次未验证线上部署状态，也未授予管理员或执行部署。

- [Firebase HTTPS Callable](https://firebase.google.com/docs/functions/callable)
- [管理员自定义声明与令牌刷新](https://firebase.google.com/docs/auth/admin/custom-claims)
