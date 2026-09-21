# HyalosPlayer 架构

Android 局域网媒体播放器。本文说明各部分的职责、边界，以及若干**尚未实现但接口形状已经为其定好**的约束。

内核（协议访问、虚拟文件系统）在独立仓库 [Krystallos](https://github.com/sheepthefather/Krystallos) 中，其架构文档是本文的前置阅读。

---

## 职责边界

| | HyalosPlayer | Krystallos |
|---|---|---|
| 协议访问（SMB 等） | | ✅ |
| 虚拟文件系统抽象 | | ✅ |
| 预读缓冲 | | ✅ |
| 服务器配置与凭据管理 | ✅ | |
| 目录浏览 UI | ✅ | |
| 容器解析（demux） | ✅（交给播放引擎） | |
| 解码与渲染 | ✅ | |
| 字幕 | 暂不做 | |

**内核不依赖 Android**，因此它的全部功能都能在桌面主机上通过 CLI 驱动和测试。这条边界的价值在于：协议层的 bug 不需要经过「编译 APK → 装到设备 → 手动操作」这条长回路才能发现。

---

## 分层

```
┌─────────────────────────────────────────────────────┐
│  UI (Compose)          服务器列表 · 文件浏览 · 播放器 │
├─────────────────────────────────────────────────────┤
│  播放层                Media3 ExoPlayer              │
│                        + KrystallosDataSource        │  ← 未实现
├─────────────────────────────────────────────────────┤
│  内核门面              krystallos_ffi.kt (UniFFI 生成)│
│                        + 薄封装：会话生命周期/错误映射│  ← 未实现
└───────────────────────────┬─────────────────────────┘
                            │ JNA direct mapping
┌───────────────────────────┴─────────────────────────┐
│  Krystallos (Rust, libkrystallos_ffi.so)            │
│  Kernel · Session · RemoteFile                       │
└─────────────────────────────────────────────────────┘
```

---

## 构建链路

```
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 29
    -o app/build/rustJniLibs build --release -p krystallos-ffi
                            │
                            ▼
            app/build/rustJniLibs/<abi>/libkrystallos_ffi.so
                            │
uniffi-bindgen generate --library <上面的 .so> --language kotlin
                            │
                            ▼
   app/build/generated/uniffi/uniffi/krystallos_ffi/krystallos_ffi.kt
```

两个 Gradle task（`buildRust`、`generateUniffiBindings`）串起这条链，由 `assembleDebug` 自动触发。

### 为什么源码目录走 Variant API

`app/build.gradle.kts` 用 `androidComponents.onVariants` 而不是旧的 `android.sourceSets` DSL 贡献 `jniLibs` 与生成的 Kotlin 目录。

**这不是风格偏好，是 AGP 9 的硬性要求**：旧 API 会直接拒绝 `Provider`，理由是它无法判断该目录存放的是生成文件（只读）还是静态文件（可读写），因而拒绝猜测。Variant API 把「静态」与「生成」区分成两个方法，而且**生成目录的任务依赖由 AGP 自动接上**——旧 API 不会。

### 绑定生成为什么用 `cargo run`

而不是全局安装的 `uniffi-bindgen`：**为了让生成器版本被 `Cargo.lock` 锁住**，不会与构建库时的 `uniffi` 版本漂移。版本不匹配会产出「能编译、运行时才炸」的绑定。

已验证绑定与平台无关——从 Android `.so` 与主机 `.dll` 生成的 `.kt` 文件 SHA-256 完全相同。

---

## 前向约束

以下来自完整的选型调研，**尚未实现**，但改动数据层前请先读。

### 播放引擎：Media3

选 **AndroidX Media3 1.11.x**（Apache-2.0）。不用 libVLC——它的 SMB 支持仅到 SMBv1（基于 libdsm），而现代 NAS 大多已关闭 SMB1。

### 数据面：直接 DataSource，不用本地 HTTP server

Rust 层的数据通过 `KrystallosDataSource`（实现 Media3 的 `DataSource`）喂给播放器，**不在 Rust 里起本地 HTTP server**。理由：少一层 IPC、无端口占用、无 127.0.0.1 鉴权问题、无后台被杀风险。

### `DataSource` 内部必须做 MB 级预读缓冲

**绝不能把调用方的 `readLength` 透传到内核层。** 这是 SMB 场景的头号性能杀手：社区实测有自定义 DataSource 被以 `readLength == 1` 连续调用 60 万次以上，初始化耗时数分钟。

内核的 `krystallos-cache` 提供了 `ReadAhead`，但它包的是 `FileHandle`；`DataSource` 这一层还需要自己的缓冲，因为 ExoPlayer 的调用模式与内核看到的不同。

### UniFFI 不支持取消

Kotlin 的 `Job.cancel()` **不会传到 Rust**。目前可接受，因为每个操作都受 libsmb2 的超时约束（Krystallos 的 `DEFAULT_TIMEOUT_SECS`），但 UI 上的「取消」实际含义是「不再等待」而非「停止工作」。若将来需要真正的取消，得在内核侧做协作式标志。

### 数据面是 async 而非同步 `readInto(ByteBuffer)`

原计划是同步的 `readInto(offset, ByteBuffer)`，基于「UniFFI 的 `&[u8]` 零拷贝能用在参数上」这个判断。**这个判断有一半是错的**：UniFFI 文档明确 `&[u8]` 只能「Kotlin → Rust」单向传递，且**不存在 `&mut [u8]` 对应物**——UniFFI 无法让 Rust 写进调用方的缓冲区。

所以读操作返回 `ByteRange { offset, data: Vec<u8> }`，边界上必然有一次拷贝。代价小到不值得绕开：100 Mbps 4K 约 12 MiB/s，按 1 MiB 分块是每秒约 12 次拷贝，而它们背后的网络往返以毫秒计。

### 已确认的产品决策

- **不做 ASS/SSA 特效字幕。** Media3 不使用 libass，且 Google 工程师已明确表态「持续不考虑对用户生成内容使用 native code」。这是产品级取舍，不是能靠工程绕过的。因此**也不引入 libmpv 副引擎**——它虽能解决字幕，但在 Android 上无法输出 HDR，且需自维护四个 ABI 的 `.so`。
- **目标设备为手机/平板**，不做 Android TV——因此无焦点导航与 Leanback 需求。

---

## 已知风险

| 风险 | 说明 |
|---|---|
| **构建链依赖三样外部工具** | JDK、NDK、CMake，缺一不可。CMake 是因为 Krystallos 构建 libsmb2 需要它生成平台相关的 `config.h`。 |
| **SMB3 加密代价约 96 倍吞吐** | 内核侧默认关闭，按连接可选开启。在不受信任的网络上打开它是对的取舍。详见 Krystallos 的架构文档。 |
| **release 构建的 R8 与 JNA 冲突** | JNA 是反射加载的，R8 看不到调用图。`proguard-rules.pro` 里的 keep 规则不是可选的。 |
