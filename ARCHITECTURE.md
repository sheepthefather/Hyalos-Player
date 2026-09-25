# HyalosPlayer 架构

Android 局域网媒体播放器。本文说明各部分的职责、边界、关键设计决策及其理由，以及尚未实现部分的前向约束。

内核（协议访问、虚拟文件系统）在独立仓库 [Krystallos](https://github.com/sheepthefather/Krystallos) 中，其架构文档是本文的前置阅读。

---

## 职责边界

| | HyalosPlayer | Krystallos |
|---|---|---|
| 协议访问（SMB 等） | | ✅ |
| 虚拟文件系统抽象 | | ✅ |
| 播放读缓冲 | ✅（`ChunkedReader`） | 有 `ReadAhead`，但 FFI 未使用，见「播放数据面」 |
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
│  播放层                Media3 ExoPlayer + PlayerView │
│                        + KrystallosDataSource        │  playback/
├─────────────────────────────────────────────────────┤
│  内核门面              krystallos_ffi.kt (UniFFI 生成)│
│                        + SessionManager 等薄封装      │  kernel/
└───────────────────────────┬─────────────────────────┘
                            │ JNA direct mapping
┌───────────────────────────┴─────────────────────────┐
│  Krystallos (Rust, libkrystallos_ffi.so)            │
│  Kernel · Session · RemoteFile                       │
└─────────────────────────────────────────────────────┘
```

---

## 仓库结构

```
Hyalos-Player/                    Android 播放器
└── vendor/krystallos/            ← git submodule，Rust 内核
    └── vendor/libsmb2/           ← 嵌套 submodule，内核的 SMB 库
```

**两层嵌套**，所以克隆必须用 `git clone --recursive`。

内核仍然是**独立仓库**，有自己完整的测试（165 个）和桌面 CLI。把它作为 submodule 而不是合并进来，是为了保住一条边界：**内核的全部功能都能在 Windows 上不经过 Android 验证**。协议层的 bug 不需要走「编译 APK → 装设备 → 手动操作」这条长回路。

### 开发时的工作树与提交

Gradle 构建的是 submodule 的**工作树**，不是某个提交快照。所以：

- 在 `vendor/krystallos` 里改代码（**哪怕没提交**），下一次构建就会生效
- 但 `git status` 会显示 submodule 指针变「脏」，这是正常的
- 要让别人拿到你的内核改动，得先在 Krystallos 里提交并推送，再在这里提交指针变更

这条与 Krystallos → libsmb2 的关系完全一致。

---

## 构建链路

```
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 29
    -o app/build/rustJniLibs build --release -p krystallos-ffi
                            │            (cwd = vendor/krystallos)
                            ▼
            app/build/rustJniLibs/<abi>/libkrystallos_ffi.so
                            │
uniffi-bindgen generate --library <上面的 .so> --language kotlin
                            │
                            ▼
   app/build/generated/uniffi/uniffi/krystallos_ffi/krystallos_ffi.kt
```

**Gradle 对 Rust 一无所知。** 它只是启动了一个叫 `cargo` 的外部程序，并把工作目录设成了 `vendor/krystallos`。两个仓库之间的全部接口就是两个目录：一个装满 `.so`，一个装着生成的 `.kt`。`dependencies { }` 里没有任何一条提到 Krystallos。

任务之间的边有两个来源：

| 边 | 来源 |
|---|---|
| `generateUniffiBindings` → `buildRust` | 手写的 `dependsOn` |
| `mergeDebugJniLibFolders` → `buildRust` | AGP 从 `addGeneratedSourceDirectory` 推断 |
| `compileDebugKotlin` → `generateUniffiBindings` | 同上 |

第二条值得说明：最早用 `addStaticSourceDirectory` 时构建失败，报的是「`mergeDebugJniLibFolders` 使用了 `buildRust` 的输出但没有声明依赖」。**Gradle 知道那个目录是 `buildRust` 产出的，但不知道谁该等它。** 换成 `addGeneratedSourceDirectory` 就是明确告诉 AGP「这是生成目录，生产者是这个任务」，边就补上了。

如果 Gradle 真有「语言级依赖」的概念，这个错误根本不会出现。

### 为什么源码目录走 Variant API

`app/build.gradle.kts` 用 `androidComponents.onVariants` 而不是旧的 `android.sourceSets` DSL。

**这不是风格偏好，是 AGP 9 的硬性要求**：旧 API 会直接拒绝 `Provider`，理由是它无法判断该目录存放的是生成文件（只读）还是静态文件（可读写），因而拒绝猜测。Variant API 把「静态」与「生成」区分成两个方法，而且**生成目录的任务依赖由 AGP 自动接上**——旧 API 不会。

### 绑定生成为什么用 `cargo run`

而不是全局安装的 `uniffi-bindgen`：**为了让生成器版本被 `Cargo.lock` 锁住**，不会与构建库时的 `uniffi` 版本漂移。版本不匹配会产出「能编译、运行时才炸」的绑定。

已验证绑定与平台无关——从 Android `.so` 与主机 `.dll` 生成的 `.kt` 文件 SHA-256 完全相同。

---

## 应用层

### 包结构（`app/src/main/java/com/hyalos/player/`）

| 包 | 职责 |
|---|---|
| 根 | `HyalosApp`（Application）持有 `AppContainer`：手写的依赖图，进程级单例 |
| `data/` | 服务器列表（`ServerRepository`）、加密凭据（`CredentialStore`）、DataStore 的 JSON 序列化器 |
| `kernel/` | 对 UniFFI 绑定的薄封装：会话管理、路径拼接、关闭辅助 |
| `playback/` | Media3 数据源（见下文「播放数据面」） |
| `ui/` | Compose 界面，按屏分子包，每屏一个 ViewModel |

**依赖注入是手写的，不用 Hilt。** 依赖图只有几个对象；Hilt 需要 KSP，而 KSP 与 AGP 9 内置 Kotlin 的配合是又一个可能出错的点，这个构建里已经有一整条 Rust 工具链了。

### 服务器配置

`ServerConfig` 以 JSON 存在 `files/datastore/servers.json`，**允许备份**。密码不在里面。

- **URI 原样拼接为 `smb://host/share`，不做百分号编码。** 内核按原始字符串的 `/`、`;`、`@` 切分且不解码（`ParsedEndpoint::parse`），编码会让服务器收到字面的 `%20`。所以校验层禁止 host 含这些字符，share 不得含 `/`。
- **用户名和域不进 URI**，走 `ConnectRequest` 的独立字段——URI 会出现在日志和错误信息里。
- **起始目录是应用层概念。** 内核的会话根永远是共享根（URI 里 share 之后的部分被忽略），所以「从 `/movies` 开始浏览」由 App 记录并在浏览时使用。

### 凭据存储

密码用 **Tink AEAD（AES-256-GCM）** 加密，keyset 由 Android Keystore 里的主密钥包裹，密文存在 `files/datastore/credentials.json`。androidx 的 `EncryptedSharedPreferences` 已弃用，Tink 是官方替代。

- **每条密文以服务器 id 作为关联数据（AAD）。** 被挪到另一条目下的密文解密失败，而不是去别的服务器登录。
- **keyset 与密文都排除出备份**（`res/xml/backup_rules.xml` 管 API 29–30，`data_extraction_rules.xml` 管 31+，后者的云备份与设备迁移两段都要写）。Keystore 的密钥从不随备份或迁移走，恢复出来的密文只是无法解密的噪音。效果是：换机后服务器列表还在，只需重输密码。
- **keyset 失效时整体重建。** Keystore 被清空后 Tink 的 `AndroidKeysetManager.build()` 会**抛异常而不是重新生成**。`CredentialStore` 捕获后删除 keyset 与全部密文、重建 keyset；单条密文解密失败同样删除该条。宁可让用户重输密码，也不要永远静默地登录失败。
- **`ConnectRequest` 不可记日志。** 它是 data class，`toString()` 含明文密码。

### 会话模型：浏览与播放分开

`SessionManager` 管两种会话：

| | 所有者 | 生命周期 |
|---|---|---|
| **浏览会话** | `SessionManager`，每服务器缓存一个 | 进程内复用；设置变更或删除服务器时作废 |
| **播放会话** | 播放器，经 `connectDedicated` 取得 | 与播放器同生共死 |

**分开的原因是内核会话串行执行。** 每个会话是一条 actor 线程，一次只做一个操作，超时 20 秒。若共用一个会话，播放时一次卡在弱网上的读会把目录浏览一起冻住。

- **`ConnectionLost` 后丢弃并重连。** 它意味着会话已死、后续调用必然失败。**只有幂等操作（列举、stat）自动重试一次**；其余操作把错误交给调用方，避免做两次。
- **每服务器一把 `Mutex` 守连接。** 两个界面同时请求时只认证一次，不会泄漏一个会话。
- **作废不等待断开。** `disconnect` 要排在该会话正在做的事后面，调用方没必要等，所以在 `appScope` 上异步执行。
- **取消只是「不再等待」。** UniFFI 不把 Kotlin 的取消传到 Rust。连接中途取消时 Rust 侧的 future 被丢弃，actor 线程随发送端关闭而退出，不会泄漏。

### 浏览

- **每进一层目录就在 Navigation 3 的回退栈上压一个 `Route.Browse(serverId, path)`。** 返回键、每层独立的 ViewModel（列表缓存）与滚动位置都由 Nav3 提供，不需要自建目录栈。路由只含 id 与路径，进程被杀后恢复时重新加载，而不是复活陈旧数据。
- **面包屑跳转**（`BackStackOps.jumpTo`）：目标已在栈上则弹回去；否则（浏览从起始目录开始，上级从未入栈）用目标替换本服务器的目录段。`replaceWith` 只改动有差异的尾部，不重建未变的 entry。
- **排序**用 ICU `Collator`（简体中文）开启 `numericCollation`，一次解决中文排序与「第2集 < 第10集」，不手写自然排序。比较器由外部注入，JVM 单测里换成普通比较器。
- **名称原样使用**，拼路径时不做任何规范化（见内核文档「Unicode 规范化」）。
- **隐藏**：`.` 开头的名字、`$RECYCLE.BIN`、`System Volume Information`、群晖的 `#recycle` / `@eaDir` 等。
- **可播放判定按扩展名。** WMV / RMVB 刻意不列入——ExoPlayer 没有对应的 extractor，标成可播放只会通向错误页。

---

### 播放数据面

```
ExoPlayer ─ ProgressiveMediaSource
              └─ KrystallosDataSource        (每个 load 一个实例，loader 线程上调用)
                   ├─ ChunkedReader          1 MiB 窗口：命中同步返回，未命中才进内核
                   └─ ReaderSource
                        └─ PlaybackConnection  每个播放器一份：专属会话 + 已打开的文件
                             └─ RemoteFile.readAt  (UniFFI → Rust)
```

**播放引擎是 Media3 1.11.x**（Apache-2.0）。不用 libVLC——它的 SMB 支持仅到 SMBv1（基于 libdsm），而现代 NAS 大多已关闭 SMB1。

**直接 DataSource，不在 Rust 里起本地 HTTP server。** 少一层 IPC、无端口占用、无 127.0.0.1 鉴权问题、无后台被杀风险。媒体以 `krystallos://<serverId>/<path>` 命名，用 `Uri.Builder` 构造、`Uri.getPath()` 读回，`#`、`?`、中文都能正确往返。

#### 1 MiB 窗口，而且必须在 Kotlin 侧

**绝不能把 ExoPlayer 的 `readLength` 透传到内核。** 这是 SMB 场景的头号性能杀手：社区实测有自定义 DataSource 被以 `readLength == 1` 连续调用 60 万次以上，初始化耗时数分钟。`ChunkedReaderTest` 断言 100 万次 1 字节读只取数 1 次。

内核的 `krystallos-cache` 有 `ReadAhead`，但 **FFI 并没有用它**，而且即使用了也帮不上：每次 ExoPlayer 读仍要跨一次 FFI 才能到达它。所以 `ChunkedReader` 是 Android 端**唯一的**缓冲。

**命中与未命中是两个调用。** `tryRead` 是普通同步代码，只有 `fill` 挂起；DataSource 只在未命中时才用 `runBlocking` 进入协程，而不是每次 1 字节读都起一个事件循环。窗口从请求位置开始（ExoPlayer 从 open 的位置往后顺序读），返回的 `ByteArray` 直接作为窗口，不再多拷一次。

#### 会话与文件跨 seek 保留

**ExoPlayer 每次 seek 都会 `close()` 再在新位置 `open()`。** 如果每次 open 都重新连接认证，每拖一次进度条就是一次 SMB 握手。所以 DataSource 的 `close()` 只清位置，会话与文件由 `PlaybackConnection` 持有，直到 `PlayerViewModel.onCleared` 才释放。它的 Mutex **只保护获取、不保护读取**——一次卡住的读最多 20 秒，`close()` 不能排在它后面。

#### 中断与重试

- **loader 线程被中断即停止等待。** ExoPlayer 取消 load（seek、release）时会中断 loader 线程，`runBlocking` 把中断转为取消并抛出，DataSource 转成 `InterruptedIOException`。内核侧的读照常跑到超时——UniFFI 不传递取消，UI 上的「取消」一律是「不再等待」而非「停止工作」；若将来需要真正的取消，得在内核侧做协作式标志。
- **错误码决定 ExoPlayer 默认重试策略的行为：**

  | 内核错误 | Media3 错误码 | 结果 |
  |---|---|---|
  | `NotFound` | `IO_FILE_NOT_FOUND`，cause 为 `FileNotFoundException` | 默认策略识别为致命，不重试 |
  | `Auth` / `PermissionDenied` | `IO_NO_PERMISSION` | 重试后失败 |
  | `ConnectionLost` | `IO_NETWORK_CONNECTION_FAILED` | **先丢弃会话**；重试在原位置重新 open 即自动重连，NAS 短暂掉线可自愈 |
  | 其他 | `IO_UNSPECIFIED` | 重试 |

#### 数据面是 async 而非同步 `readInto(ByteBuffer)`

原计划是同步的 `readInto(offset, ByteBuffer)`，基于「UniFFI 的 `&[u8]` 零拷贝能用在参数上」这个判断。**这个判断有一半是错的**：UniFFI 文档明确 `&[u8]` 只能「Kotlin → Rust」单向传递，且**不存在 `&mut [u8]` 对应物**——UniFFI 无法让 Rust 写进调用方的缓冲区。所以读操作返回 `ByteRange { offset, data: Vec<u8> }`，边界上必然有一次拷贝。100 Mbps 4K 约 12 MiB/s，按 1 MiB 分块是每秒约 12 次拷贝与分配，而它们背后的网络往返以毫秒计。

#### 为什么用 `PlayerView` 而不是 Compose 版 `Player`

`media3-ui-compose-material3` 1.11 的 `Player` composable 仍是 `@ExperimentalApi`，而且**没有控制条自动隐藏、缓冲指示器、音轨选择、倍速**——NAS 上的电影常有多条音轨，缺音轨选择直接影响使用。`PlayerView` 全都自带且是稳定 API，经 `AndroidView` 嵌入。Compose 版稳定后可考虑迁移。

`ExoPlayer` 放在 `PlayerViewModel` 里，旋转屏幕不重建也不重连。`onCleared` 先 `player.release()`（中断 loader），再在 `appScope` 上关闭连接。播放页进入沉浸模式；视频宽 ≥ 高时切到横屏，离开时恢复；`ON_STOP` 时暂停（尚无后台播放）。

#### 测试

`KrystallosDataSourceContractTest` 继承 **Media3 官方的 `DataSourceContractTest`**（22 项：位置、长度、结束、transfer listener、关闭后 `getUri` 等），内核换成内存里的假 `ReaderSource`——DataSource 依赖接口而不是 `Session`，正是为此。需要在设备上跑，因为 `DataSpec` 用了 `android.net.Uri`。

它要 mock `TransferListener`，所以 androidTest 依赖 `mockito-android`，**且必须是 5.x**：`media3-test-utils` 带进来的 3.12.4 把生成的类写成可写 dex，Android 14+ 拒绝加载（`Writable dex file ... is not allowed`）。

---

## 前向约束

### targetSdk 升到 37 时需要本地网络权限

targetSdk 36 下，`INTERNET` 权限隐式涵盖局域网访问。**升到 37 后，访问局域网需要运行时申请 `ACCESS_LOCAL_NETWORK`，且对 Rust 内核的原生 socket 同样生效。** 升级 targetSdk 时必须同时加上权限申请流程，否则连接会直接失败。

### 已确认的产品决策

- **不做 ASS/SSA 特效字幕。** Media3 不使用 libass，且 Google 工程师已明确表态「持续不考虑对用户生成内容使用 native code」。这是产品级取舍，不是能靠工程绕过的。因此**也不引入 libmpv 副引擎**——它虽能解决字幕，但在 Android 上无法输出 HDR，且需自维护四个 ABI 的 `.so`。
- **目标设备为手机/平板**，不做 Android TV——因此无焦点导航与 Leanback 需求。
- **本轮不做**：后台播放 / MediaSession（`media3-session` 在版本目录里保留但未依赖）、续播位置、局域网发现、字幕。

---

## 已知风险

| 风险 | 说明 |
|---|---|
| **构建链依赖三样外部工具** | JDK、NDK、CMake，缺一不可。CMake 是因为 Krystallos 构建 libsmb2 需要它生成平台相关的 `config.h`。 |
| **SMB3 加密代价约 96 倍吞吐** | 内核侧默认关闭，按连接可选开启。在不受信任的网络上打开它是对的取舍。详见 Krystallos 的架构文档。 |
| **release 构建的 R8 与 JNA 冲突** | JNA 是反射加载的，R8 看不到调用图。`proguard-rules.pro` 里的 keep 规则不是可选的。 |
| **播放会话的读取是串行的** | 一次读卡住最多 20 秒，期间 seek 排在它后面。浏览用另一个会话，不受影响。 |
| **MKV 里的 AC3 / DTS / TrueHD 音轨** | 很多设备没有对应的硬件解码器，结果是有画面没声音。Jellyfin 预编译的 `media3-ffmpeg-decoder` 最新只到 1.9.0，跟不上 Media3 1.11，本轮作为已知限制。 |
| ~~内核可能把「端口被拒绝」报成认证失败~~ **已实测排除** | 曾在选型阶段担心：内核把 `ECONNREFUSED` 映射为 `Auth`，NAS 关了 SMB 时会显示「密码错误」。模拟器实测（连一台没有 SMB 监听的主机）显示的是**「无法连接到服务器」**，映射正确。原因是 TCP 连接被拒时 libsmb2 的 `wait_for_reply` 返回**裸 `-1`**，而内核刻意不把它喂给 errno 表（见 Krystallos 架构文档「错误分类」第 2 条），于是落到 `ConnectionLost`。`ECONNREFUSED`→`Auth` 只在 libsmb2 真的把 LOGON_FAILURE 映成该 errno 时触发，即密码错误时，那是正确的。 |
| **每个 1 MiB 块都新分配一次** | 4K 码流约每秒 12 次大对象分配，目前可接受。真机上若出现 GC 卡顿，再改成复用缓冲。 |
| **模拟器无法验证画面** | 本机模拟器（Pixel8_API36）把**任何**视频都渲染成纯绿色（YUV 全 0 的典型表现），**系统自带 Google Photos 播同一文件结果相同**。已排除 `-feature -HostComposition`（去掉无效）与 `-gpu host`（换 SwiftShader 无效）。因此模拟器上只能验证解码启动、时长解析、位置推进与 seek，**画面正确性必须在真机上确认**。数据本身是可信的：内核经 SMB 读出的字节与源文件 SHA-256 一致（500 KB 与 26 MB 两个文件各验一次）。 |
