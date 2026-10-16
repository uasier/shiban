# 识办

把图片或文字收成待办。识别内容、时间、地点、重要性和必要性，完成后可以勾掉。

- `app/`：Android 客户端
- `desktop/`：Tauri 桌面端
- `server/`：账号、同步和模型识别服务

当前版本：**1.4.5**。安装包在 [Releases](https://github.com/uasier/shiban/releases)。

公网地址固定为 `http://154.36.168.192`。手机和桌面默认连这里，不用再填局域网地址。本机端口登记在 `大模型项目/端口登记`，本地进程仍占用 `40400-40499`。

## 服务端

```bash
cd server
cp .env.example .env
# 修改 JWT_SECRET，并按需填写 GROK_API_KEY
node src/server.js
```

本地调试监听 `http://127.0.0.1:40400`。模型密钥也可以登录后写在账号设置里，会随账号保存。

## Android

用 Android Studio 打开 `app/`，或在该目录执行：

```bash
./gradlew assembleDebug
```

真机和模拟器都默认连公网。只有要改用本机服务时，才把地址改成 `http://10.0.2.2:40400` 或电脑的局域网地址。设置里可以检查更新。Android 和 Windows 安装包由 GitHub Actions 发布到 [Releases](https://github.com/uasier/shiban/releases)。探活仍然连接识办服务，用来判断服务端是否正常。

## 桌面端

```bash
cd desktop
npm install
npm run dev
```
