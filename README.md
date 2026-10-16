# 识办

把图片或文字收成待办。识别内容、时间、地点、重要性和必要性，完成后可以勾掉。

- `app/`：Android 客户端
- `desktop/`：Tauri 桌面端
- `server/`：账号、同步和模型识别服务

当前版本：**1.4.0**。安装包在 [Releases](https://github.com/uasier/shiban/releases)。

## 服务端

```bash
cd server
cp .env.example .env
# 修改 JWT_SECRET，并按需填写 GROK_API_KEY
node src/server.js
```

默认监听 `http://127.0.0.1:8787`。模型密钥也可以登录后写在账号设置里，会随账号保存。

## Android

用 Android Studio 打开 `app/`，或在该目录执行：

```bash
./gradlew assembleDebug
```

模拟器里的服务地址用 `http://10.0.2.2:8787`。真机请改成电脑的局域网地址。

## 桌面端

```bash
cd desktop
npm install
npm run dev
```
