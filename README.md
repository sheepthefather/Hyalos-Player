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
| 自建播放列表（每台服务器一个，跨目录按加入顺序连播） | ✅ |
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

### 同时开发内核时

Gradle 构建的是 submodule 的**工作树**，不是某个提交快照，所以在 `vendor/krystallos` 里改代码（哪怕没提交）下一次构建就会生效。若内核在别处另有一份检出：

```bash
./gradlew assembleDebug -Pkrystallos.dir=/path/to/Krystallos
```

## 改构建配置前

`app/build.gradle.kts` 与 `libs.versions.toml` 里有五处**不能按直觉改**的地方（Kotlin 插件、JNA 变体、`cargo ndk -P 29`、release 的 strip、R8 keep 规则）。它们各自的报错信息都不会指向真正的原因，所以动手前请先读 [ARCHITECTURE.md 的「构建约束」](ARCHITECTURE.md#构建约束)。

## 许可证

GPL-3.0-or-later。见 [LICENSE](LICENSE)。

依赖的 [Krystallos](https://github.com/sheepthefather/Krystallos) 同为 GPL-3.0，其中 vendored 的 libsmb2 是 LGPL-2.1-or-later。
