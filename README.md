# HyalosPlayer

Android 局域网媒体播放器。把 NAS 上的影片当作本地文件来浏览和播放。

网络访问能力由独立的 Rust 内核 [Krystallos](https://github.com/sheepthefather/Krystallos) 提供，首个支持的协议是 SMB2/3。

## 当前状态

**首个可用版本**：添加服务器 → 浏览共享目录 → 播放影片的完整闭环已经打通，并在一台真实的 SMB 共享上端到端验证过。

| 部分 | 状态 |
|---|---|
| Gradle 工程 + Rust 构建链路 | ✅ |
| Kotlin 绑定生成 | ✅ |
| 服务器配置与连接（含密码加密存储、协商信息诊断） | ✅ |
| 目录浏览（列表/网格、四种排序） | ✅ |
| 文件操作（重命名、删除、复制、剪切粘贴） | ✅ |
| 视频缩略图（含磁盘缓存与设置页） | ✅ |
| 播放（Media3 + `KrystallosDataSource`、连播、画面比例） | ✅ |
| 自建播放列表（每台服务器一个，底部栏独立一栏，跨目录按加入顺序连播） | ✅ |
| 后台播放 / MediaSession、续播、字幕、局域网发现 | ⬜ |

**在模拟器上跑要注意**：AVD 需以 `-feature -HardwareDecoder` 启动，否则所有视频都渲染成纯绿色。原因与验证方式见 [ARCHITECTURE.md](ARCHITECTURE.md#已知风险)。

## 前置条件

- **JDK 17+**（本机用 21）
- **Android SDK**，含 NDK。`ANDROID_HOME` 指向 SDK 根目录
- **Rust** 与三个 Android target：
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```
- **CMake 3.x / 4.x** —— Krystallos 构建 libsmb2 需要它生成平台相关的 `config.h`

## 构建

```bash
git clone --recursive https://github.com/sheepthefather/Hyalos-Player.git
cd Hyalos-Player
./gradlew assembleDebug
```

**`--recursive` 不能省。** 内核 Krystallos 是一个 submodule，而它自己又内嵌 libsmb2 这个 submodule——**两层嵌套**。已经克隆过但忘了的话：

```bash
git submodule update --init --recursive
```

### 测试

```bash
./gradlew test                       # JVM 单测，不需要设备
./gradlew connectedDebugAndroidTest  # 仪器测试，需要连上设备或模拟器
```

仪器测试里有 Compose UI 测试（`app/src/androidTest/java/com/hyalos/player/ui/`）。它们按文字与内容描述找控件而不是按坐标，所以改布局不会让它们悄悄点到别的东西上；失败时还会打出语义树。需要数据的用例把 JSON 直接写进 DataStore——应用没有注入假容器的缝。

**CI 只跑 JVM 那一半**，仪器测试要自己记得跑。

### 同时开发内核时

Gradle 构建的是 submodule 的**工作树**，不是某个提交快照，所以在 `vendor/krystallos` 里改代码（哪怕没提交）下一次构建就会生效。若内核在别处另有一份检出：

```bash
./gradlew assembleDebug -Pkrystallos.dir=/path/to/Krystallos
```

## 改构建配置前

`app/build.gradle.kts` 与 `libs.versions.toml` 里有五处**不能按直觉改**的地方（Kotlin 插件、JNA 变体、`cargo ndk -P 29`、release 的 strip、R8 keep 规则）。它们各自的报错信息都不会指向真正的原因，所以动手前请先读 [ARCHITECTURE.md 的「构建约束」](ARCHITECTURE.md#构建约束)。

## 发布

打一个 `v*` 标签，GitHub Actions 会构建并开一个 **draft release**（`.github/workflows/release.yml`）。它产出的是**未签名**的 APK——签名在本地做，所以密钥既不进仓库也不进 CI，而 release 里也只会出现已签名的包。

```bash
# 只需一次：生成密钥。之后务必备份——Android 靠它认定「同一个应用」，
# 换 key 意味着老用户装不上新版。
keytool -genkeypair -v -keystore ~/hyalos-release.jks -alias hyalos \
  -keyalg RSA -keysize 4096 -validity 10000

# 每次发版：从那次 Actions 运行的 artifacts 里下载 app-release-unsigned.apk
"$ANDROID_HOME/build-tools/37.0.0/apksigner" sign \
  --ks ~/hyalos-release.jks --out app-release.apk app-release-unsigned.apk

# 传进 draft release，再到网页上发布
gh release upload v0.1.0 app-release.apk
```

**先手动触发一次**（Actions 页面上的 `workflow_dispatch`）确认工具链跑得通，再打标签——首次运行大概率要调一两次，见下。

## 许可证

GPL-3.0-or-later。见 [LICENSE](LICENSE)。

依赖的 [Krystallos](https://github.com/sheepthefather/Krystallos) 同为 GPL-3.0，其中 vendored 的 libsmb2 是 LGPL-2.1-or-later。
