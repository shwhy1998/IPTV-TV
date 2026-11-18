# IPTV TV

给 Android TV 和旧款小米电视使用的遥控器直播播放器。输入自己的 HTTP(S) M3U 地址，即可浏览频道、查看节目单和切换线路。应用不内置直播源。

## 安装与配置

1. 在 [GitHub Releases](https://github.com/shwhy1998/IPTV-TV/releases) 下载 `IPTV-TV.apk`。应用支持 Android 6.0（API 23）及以上版本。
2. 通过电视的应用安装功能或 Android SDK 的 `adb install -r IPTV-TV.apk` 安装。
3. 首次启动没有直播源时，按左键打开添加表单。也可以按菜单键进入「设置 → 直播源」，添加电视能访问的 M3U 地址。

新版 Release 使用项目专用签名。如果电视此前安装的是本地调试版，需要先卸载旧版，再安装 Release 并重新添加直播源。之后可在同一签名下覆盖升级。

## 遥控器操作

| 按键 | 全屏播放时 |
| --- | --- |
| OK | 显示或隐藏直播信息栏 |
| 左键 | 打开快速换台。栏内用左右键切分组、上下键选频道，按 OK 换台 |
| 上下键 | 直接切到相邻频道 |
| 菜单 | 打开线路与设置 |
| 返回 | 关闭浮层，或在全屏时弹出退出确认 |

快速换台显示透明台标和当前节目。全屏信息栏显示当前及下一档节目。线路标签来自播放清单，例如一条频道记录下多个带 `$高清`、`$超高清` 后缀的 HTTP 地址。EPG 从 M3U 头部的 `x-tvg-url` 或 `url-tvg` 读取，节目与频道通过 `tvg-id` 对应。

## 设置与播放架构

- **直播源**支持添加、查看、编辑、删除和切换。
- **定时刷新**在应用处于前台时按所设间隔刷新当前直播源与 EPG。启动刷新默认开启，设置中也可以立即刷新。
- **立即刷新**在直播源和 EPG 请求结束后提示成功，或说明哪一项失败。
- **检查更新**在用户操作后查询 jsDelivr，并核实 GitHub 正式 Release。下载时显示进度，校验 SHA-256、版本和签名后自动进入系统安装确认。升级保留直播源和设置。Android 8.0 及以上可能需要允许 IPTV TV 作为安装来源。
- 播放使用 Media3 ExoPlayer 和系统硬件视频解码。本地只缓存当前频道清单与节目数据，视频不会写入存储。
- 如果运营商使用组播和 FCC，请将 M3U 接入 [rtp2httpd](https://rtp2httpd.com/)，再把 rtp2httpd 输出的 HTTP 播放清单交给电视。FCC 请求由网关处理，电视直接播放网关输出。
- 频道台标只取自播放清单的 `tvg-logo`。

## 本地编译与发布

用 Android Studio 打开仓库根目录。命令行构建需 JDK 17 和 Android SDK 36。

```sh
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。调试 APK 位于 `app/build/outputs/apk/debug/`。

每次推送分支或更新 PR 都会触发 [GitHub Actions 构建](https://github.com/shwhy1998/IPTV-TV/actions)。合并到 `main` 后，工作流会签名 APK，校验签名并将 APK 和 SHA-256 文件发布到 Release。

正式版号来自 `VERSION`。发布新正式版时，同时提交对应的 `release-notes/v<版本>.md`。同版本后续的 `main` 构建会自动创建带构建号的预发布版，更新说明来自该次推送的提交记录。正式版仍保留在 Releases 中。
