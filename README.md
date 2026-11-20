# 远程签到（Remote Sign）

给朋友代签「我去图书馆」（`web.traceint.com`，高校图书馆座位预约系统）签到码的极简 Android 客户端。

## 用法

1. 预约人在自己的 gotolibrary / 浏览器里拿到登录凭证（JWT，`Authorization=eyJ...`），发给在图书馆现场的朋友。
2. 朋友打开本 App，粘贴凭证 → 自动拉取当前预约的签到二维码并出示给签到设备。
3. 二维码每 5 秒自动轮询刷新（官方机制：码会轮换，截图转发无效），签到成功后 App 会明确提示。

## 原理

- 鉴权只靠 Cookie 里的 `Authorization=<JWT>`，不绑设备，所以凭证在谁的手机上都能用。
- 签到码内容就是当前预约的 `reserve.token`，接口为
  `POST https://wechat.v2.traceint.com/index.php/graphql/`（`app-version: 2.2.5`），
  轮询 `getQrCodeStatus`：code=10 换码、code=1/55 签到成功、code=40001 凭证失效。
- JWT 有效期只有 2 小时，过期后让对方重新发送即可；这也意味着凭证泄露的影响被限制在 2 小时内。

## 注意

- 凭证等同于账号在这 2 小时内的完整权限（可查预约、可退座等），只发给信得过的人。
- 本 App 只做「出示签到码」一件事：不选座、不预约、无后台服务。凭证只存在本机 SharedPreferences，签到完成或手动清除后删除。
- 代签是否符合学校图书馆规定由使用者自行判断，违规记录记在预约人账号上。

## 构建

需要 JDK 17 + Android SDK 35。

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名从仓库根目录 `keystore.properties` 读取（不入库）；缺失时回退 debug 签名。
