# HyalosPlayer 架构

Android 局域网媒体播放器。本文说明各部分的职责边界，以及关键设计决策的**理由**。

内核（协议访问、虚拟文件系统）在独立仓库 [Krystallos](https://github.com/sheepthefather/Krystallos) 中，其架构文档是本文的前置阅读。

---

## 职责边界

| | HyalosPlayer | Krystallos |
|---|---|---|
| 协议访问（SMB 等） | | ✅ |
| 虚拟文件系统抽象 | | ✅ |
| 播放读缓冲 | ✅（`ChunkedReader`） | 有 `ReadAhead`，但 FFI 用不到 |
| 服务器配置与凭据管理 | ✅ | |
| 目录浏览 UI | ✅ | |
| 容器解析（demux） | ✅（交给播放引擎） | |
| 解码与渲染 | ✅ | |
| 字幕 | 暂不做 | |

**内核不依赖 Android**，因此它的全部功能都能在桌面主机上通过 CLI 驱动和测试。这条边界的价值在于：协议层的 bug 不必经过「编译 APK → 装到设备 → 手动操作」这条长回路才能发现。

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

## 仓库与工作树

内核是 submodule，而它自己又内嵌 libsmb2 这个 submodule——**两层嵌套**，所以克隆必须用 `--recursive`。内核保持为**独立仓库**而非合并进来，是为了保住一条边界：它的全部功能都能不经过 Android 验证。

Gradle 构建的是 submodule 的**工作树**，不是某个提交快照——所以在 `vendor/krystallos` 里改代码（哪怕没提交）下一次构建就会生效，而 `git status` 显示指针变「脏」是正常的。要让别人拿到内核改动，得先在 Krystallos 里提交推送，再在这里提交指针变更；这与 Krystallos → libsmb2 的关系完全一致。

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

**Gradle 对 Rust 一无所知**：它只是在一个叫 `vendor/krystallos` 的目录里启动了 `cargo`。两个仓库之间全部的接口就是两个目录——一个装满 `.so`，一个装着生成的 `.kt`，`dependencies { }` 里没有任何一条提到 Krystallos。

任务之间的边有两个来源：

| 边 | 来源 |
|---|---|
| `generateUniffiBindings` → `buildRust` | 手写的 `dependsOn` |
| `mergeDebugJniLibFolders` → `buildRust` | AGP 从 `addGeneratedSourceDirectory` 推断 |
| `compileDebugKotlin` → `generateUniffiBindings` | 同上 |

两处必须这样写，都不是风格偏好：**`addGeneratedSourceDirectory`** 明确告诉 AGP「这个目录由这个任务产出」，依赖边才补得上——用 `addStaticSourceDirectory` 会报「使用了 `buildRust` 的输出但没有声明依赖」，因为 Gradle 知道目录由谁产出、却不知道谁该等它。**`androidComponents.onVariants`** 则是 AGP 9 的硬性要求：旧的 `sourceSets` DSL 会直接拒绝 `Provider`，理由是它无法判断该目录里是生成文件（只读）还是静态文件。

**绑定生成用 `cargo run` 而非全局安装的 uniffi-bindgen**，为的是让生成器版本被 `Cargo.lock` 锁住、不与构建库时的 `uniffi` 版本漂移——版本不匹配会产出「能编译、运行时才炸」的绑定。

---

## 构建约束

改 `app/build.gradle.kts` 或 `libs.versions.toml` 前必读。五条都是踩过的坑，**报错信息都不指向真正的原因**。

**1. 不要加 `org.jetbrains.kotlin.android` 插件。** AGP 9 内置 Kotlin 支持且默认启用，而独立的 Kotlin Android 插件与它的新 DSL 不兼容——加上去直接构建失败。

**2. JNA 必须用 `aar` 变体**，不是默认的 jar：

```kotlin
implementation(variantOf(libs.jna) { artifactType("aar") })
```

生成的绑定通过 JNA 的 direct mapping 加载 Rust 库，而 jar 里没有 `libjnidispatch.so`，用它会得到一个运行时的 `UnsatisfiedLinkError`——错误信息既不提 JNA 也不提变体问题。

**3. `cargo ndk` 的 `-P 29` 不能省。** 它默认按 API 21 构建，低于本项目 minSdk，产出的库会链接到更老的 libc。

**4. release 构建不要 `strip = true`。** UniFFI 的元数据符号是只读数据、不在 `.dynsym` 里，完整 strip 会连 `.symtab` 一起移除，于是 `uniffi-bindgen` 在**真正要发布的产物**上报 `No UniFFI metadata found`，而 debug 构建却正常。内核的 release profile 用 `strip = "debuginfo"`。

**5. 用 R8 时必须保留 JNA 的类**（见 `app/proguard-rules.pro`）。JNA 是反射加载的，R8 看不到调用图，会把需要的类剥掉或改名——症状是只在 release 构建里出现的 `UnsatisfiedLinkError`。

---

## 应用层

### 包结构（`app/src/main/java/com/hyalos/player/`）

| 包 | 职责 |
|---|---|
| 根 | `HyalosApp` 持有 `AppContainer`：手写的依赖图，进程级单例 |
| `data/` | 服务器列表（`ServerRepository`）、加密凭据（`CredentialStore`）、DataStore 的 JSON 序列化器 |
| `kernel/` | 对 UniFFI 绑定的薄封装：会话管理、路径拼接、关闭辅助 |
| `playback/` | Media3 数据源（见「播放数据面」） |
| `files/` | 重命名、删除、复制、剪切/粘贴（`FileOperations`），部分失败如实报告（见「文件操作」） |
| `thumbnails/` | 视频缩略图：取帧、磁盘缓存与淘汰（见「缩略图」） |
| `ui/` | Compose 界面，按屏分子包，每屏一个 ViewModel（`common/`、`theme/` 除外） |

**依赖注入是手写的，不用 Hilt**：依赖图只有几个对象，而 Hilt 需要 KSP，KSP 与 AGP 9 内置 Kotlin 的配合是又一个可能出错的点——这个构建里已经有一整条 Rust 工具链了。

### 服务器配置

`ServerConfig` 以 JSON 存在 `files/datastore/servers.json`，**允许备份**，密码不在里面。四条容易踩的：

- **URI 原样拼接，不做百分号编码**：内核按原始字符串的 `/`、`;`、`@` 切分且不解码，编码会让服务器收到字面的 `%20`。
- **端口写进 URI**（`smb://host:1445/share`）——libsmb2 没有单独设置端口的接口，它自己从服务器字符串里拆 `host:port`、缺省 445。表单用独立的「端口」字段；地址里写 `host:port` 会被明确拒绝而不是猜（与 IPv6 字面量歧义），IPv6 字面量则自动补方括号。
- **用户名与域不进 URI**，走 `ConnectRequest` 的独立字段——URI 会出现在日志和错误信息里。
- **「测试连接」会报告协商结果**：SMB 方言与协商到的最大读写块（如「SMB 3.1.1 · 读取 8 MiB / 写入 8 MiB」）。方言由内核在连接时读一次存下，界面不必为此多一次往返。**协商后的加密与签名状态查不到**——libsmb2 的公开接口没有对应查询，所以只能报告「请求了什么」而非「谈成了什么」；界面上因此不显示加密状态，而不是显示一个读不到的值。方言是数字而非名字：编号是协议的，措辞与语言是调用方的。
- **起始目录是应用层概念**：内核的会话根永远是共享根，所以「从 `/movies` 开始浏览」由 App 记录。

### 凭据存储

密码用 **Tink AEAD（AES-256-GCM）** 加密，keyset 由 Android Keystore 里的主密钥包裹，密文存在 `files/datastore/credentials.json`（androidx 的 `EncryptedSharedPreferences` 已弃用，Tink 是官方替代）。密文以服务器 id 作为关联数据，被挪到别的条目下会解密失败，而不是去别的服务器登录。

**keyset 与密文都排除出备份**：Keystore 的密钥从不随备份或迁移走，恢复出来的密文只是无法解密的噪音。效果是换机后服务器列表还在、只需重输密码。

**keyset 失效时整体重建**：Keystore 被清空后 Tink 会**抛异常而不是重新生成**，只捕获不重建就是永远静默地登录失败。

`ConnectRequest` 是 data class，`toString()` 含明文密码，**不可记日志**。

### 会话模型：浏览与播放分开

| | 所有者 | 生命周期 |
|---|---|---|
| **浏览会话** | `SessionManager`，每服务器缓存一个 | 进程内复用；设置变更或删除服务器时作废 |
| **播放会话** | 播放器，经 `connectDedicated` 取得 | 与播放器同生共死 |

**分开的原因是内核会话串行执行**：一条 actor 线程、一次只做一个操作、超时 20 秒。共用会让一次卡在弱网上的播放读把目录浏览一起冻住。

- **`ConnectionLost` 后丢弃重连**，且**只有幂等操作（列举、stat）自动重试一次**——其余交给调用方，避免做两次。
- **每服务器一把 `Mutex` 守连接**，两个界面同时请求时只认证一次；**作废时不等断开**（`disconnect` 排在它正在做的事后面，调用方没必要等）。
- **取消只是「不再等待」**：UniFFI 不把 Kotlin 的取消传到 Rust。

### 浏览

- **每进一层目录就压一个 `Route.Browse(serverId, path)` entry**：返回键、每层列表缓存与滚动位置都由 Nav3 提供，不必自建目录栈。路由只含 id 与路径，进程被杀后恢复时重新加载，而不是复活陈旧数据。
- **面包屑跳转**：目标已在栈上则弹回去，否则替换本服务器的目录段——只改动有差异的尾部，不重建未变的 entry。
- **排序**：名称 / 日期 / 大小 / 类型，各可正序倒序，偏好存进设置。三条约束：**改排序不重新请求服务器**——ViewModel 保留服务器返回的原始条目并本地重排，目录没变，变的只是看法；**文件夹在任何排序下都置顶**，否则按大小排会把文件夹埋在大文件之间；**缺失的日期或大小排在最后**，SMB 把无时间戳报成 0，当成 1970 会让它们散落在列表中间。各组内独立排序，同值回退到名称序，否则同尺寸的一批文件会在每次列举时换位置。名称比较用 ICU `Collator`（简体中文）开启 `numericCollation`，一次解决中文排序与「第2集 < 第10集」；比较器外部注入，JVM 单测里换成普通比较器（`android.icu` 在 JVM 上不存在）。
- **列表与网格两种视图**，工具栏按钮切换，偏好存进设置（全局而非按服务器——这是「习惯怎么看」，不是某台 NAS 的属性）。网格用 `GridCells.Adaptive(150.dp)`：同一份代码在竖握手机上给两列、平板上给五列，不必去问屏幕宽度。两种视图共用同一个缩略图组件，所以「只给可见项取帧」这条规则只有一处实现。
- **重连是自动的，用户不该看到重试按钮。** 错误分成两类：**关于路径的答案**（找不到、无权限、已存在……）重连也不会改变，直接报错；**可能是连接问题**的，应用自己处理。判定在 `KernelException.describesThePath`，有单测钉住哪些算前者。三层递进：浏览会话内部丢弃死会话并重试一次；列表加载先快速重试两次（1 秒、2 秒退避），仍失败才显示错误；**显示错误之后仍每 10 秒静默重试**，直到成功或离开这个界面——服务器回来了列表应该自己出现，而不是等着人来点一下。
  这条策略存在的原因值得记下来：内核把 `ConnectionLost` 定义得很清楚（会话已死），但它**并不总能分辨**。`smb2_opendir` 这类 API 用空指针报失败，**没有返回码**可判，于是服务端关掉 socket 到达应用时是通用的 `Backend` 错误，只在文本里写着 `smb2_service: POLLHUP, socket error`。内核要认出它只能去匹配散文，而非文本的信号又不存在——`smb2_get_fd` 本可回答，但 libsmb2 在 POLLHUP 分支只设错误、不关 fd。所以应用取了安全的读法：**凡不是关于路径的陈述，都当作可能是连接**。这比匹配 C 库的错误措辞稳，也覆盖同类的其它表现。
- **名称原样使用**，拼路径不做任何规范化（见内核文档「Unicode 规范化」）。
- **可播放判定按扩展名**，WMV / RMVB 刻意不列入——ExoPlayer 没有对应的 extractor，标成可播放只会通向错误页。

---

## 播放数据面

```
ExoPlayer ─ ProgressiveMediaSource
              └─ KrystallosDataSource        (每个 load 一个实例，loader 线程上调用)
                   ├─ ChunkedReader          1 MiB 窗口：命中同步返回，未命中才进内核
                   └─ ReaderSource
                        └─ PlaybackConnection  每个播放器一份：专属会话 + 已打开的文件
                             └─ RemoteFile.readAt  (UniFFI → Rust)
```

不用 libVLC（其 SMB 支持仅到 SMBv1，而现代 NAS 大多已关闭 SMB1），也**不在 Rust 里起本地 HTTP server**——少一层 IPC、无端口占用、无 127.0.0.1 鉴权与后台被杀的问题。媒体以 `krystallos://<serverId>/<path>` 命名，用 `Uri.Builder` 构造、`Uri.getPath()` 读回，`#`、`?`、中文都能正确往返。

**1 MiB 窗口必须在 Kotlin 侧。** 绝不能把 ExoPlayer 的 `readLength` 透传到内核：extractor 探测容器时会以 1 字节为粒度读几十万次，每次跨 FFI 再跨网络就是灾难。内核的 `ReadAhead` 帮不上忙——FFI 没有用它，而即便用了，每次读仍要跨一次 FFI，所以 `ChunkedReader` 是 Android 端唯一的缓冲。命中与未命中是两个调用：`tryRead` 是普通同步代码，只有未命中才用 `runBlocking` 进协程。

**会话与文件跨 seek 保留。** ExoPlayer 每次 seek 都会 `close()` 再在新位置 `open()`，若每次 open 都重新认证，拖一次进度条就是一次 SMB 握手。所以 DataSource 的 `close()` 只清位置，会话与文件由 `PlaybackConnection` 持有到播放器退出；它的 Mutex **只保护获取、不保护读取**——一次卡住的读最多 20 秒，`close()` 不能排在它后面。

**中断与重试。** ExoPlayer 取消 load（seek、release）时会中断 loader 线程，`runBlocking` 把中断转为取消并抛出，于是**停止等待**；内核侧的读照常跑到超时（UniFFI 不传递取消，UI 上的「取消」一律是「不再等待」而非「停止工作」）。失败映射成 Media3 错误码，由后者的默认策略决定重试：

| 内核错误 | Media3 错误码 | 结果 |
|---|---|---|
| `NotFound` | `IO_FILE_NOT_FOUND`（cause 为 `FileNotFoundException`） | 默认策略识别为致命，不重试 |
| `Auth` / `PermissionDenied` | `IO_NO_PERMISSION` | 重试后失败 |
| `ConnectionLost` | `IO_NETWORK_CONNECTION_FAILED` | **先丢弃会话**；重试即在原位置重新 open，NAS 短暂掉线可自愈 |
| 其他 | `IO_UNSPECIFIED` | 重试 |

**用 `PlayerView` 而非 Compose 版 `Player`**：后者在 Media3 1.11 仍是 `@ExperimentalApi`，且没有控制条自动隐藏、缓冲指示器与音轨选择——NAS 上的电影常有多条音轨，缺音轨选择直接影响使用。`ExoPlayer` 放在 ViewModel 里，旋转屏幕不重建也不重连。

**测试**继承 Media3 官方的 `DataSourceContractTest`（22 项契约），内核换成内存里的假 `ReaderSource`——DataSource 依赖接口而非 `Session`，正是为此。

### 播放器的三个行为

**进入即播放，且不在旋转时中断。** 这一条曾经是坏的，且坏法值得记住：`PlayerScreen` 为宽屏视频设置 `SENSOR_LANDSCAPE`，而清单当时没有声明 `configChanges`，于是横屏被当作配置变更、**Activity 重建**，旧界面的 `ON_STOP { player.pause() }` 触发——而 player 活在 ViewModel 里、配置变更不重建它。结果是视频打开、第一帧也在、然后停在那里：日志显示它只播了 24 毫秒。修法是清单声明 `configChanges`（顺带消除旋转时 SurfaceView 被拆掉重建的黑屏闪烁），再加上 `ON_STOP` 里跳过 `isChangingConfigurations`——后者在清单修好后不会触发，但别的重建原因（语言、分屏）会以完全相同的方式失败。

**「下一个」先播起来再补列表。** 进播放页立刻播被点的那一项：一次目录列举的往返在慢链路上是几秒的空白。列举回来后用 `addMediaItems` 把同目录的其他可播放项插到当前项前后——Media3 的插入不打断当前播放，索引跟着平移，而不是用 `setMediaItems` 重来。「自动播放下一个」这个设置就是「列表里有没有别的条目」：关掉时不添加，列表只有一项，播完即停，上一/下一按钮也没有目标。

**播放列表的顺序与浏览页一致**，因为它复用同一个 `EntrySorting.playableInOrder`（排序键、方向、中文与数字排序、可播放判定都是同一份）。两边不一致的话，用户看到的顺序与实际播放的顺序会不同——那种 bug 只在连播时暴露，且看起来像「跳了一集」。

**画面比例**（适应 / 拉伸 / 裁切）对应 `PlayerView.resizeMode` 的 FIT / FILL / ZOOM。默认「适应」：另两个一个会畸变、一个会裁掉画面，应该是用户主动选的，而不是打开影片就撞上的。它应用在 `AndroidView` 的 `update` 里而不是 `factory`——`factory` 只跑一次，之后改设置就再也到不了那个 View。

### 文件操作

重命名、删除、复制、剪切/粘贴。长按进入选择模式：长按即选中该项（工具栏就是它的菜单），再点其他项即多选。重命名只在恰好选中一项时可用——那是「对单项操作」与「批量操作」真正分岔的地方，一个模式因此覆盖两种用法。

**递归在应用侧。** 内核只做单项：`remove_dir` 拒绝非空目录、`copy` 只复制一个文件。这不是遗漏而是刻意的——让调用方决定「做了一半」意味着什么。`FileOperations` 承担这件事：显式的工作队列（不是递归调用，深目录不会爆栈）、逐项继续而不是遇到失败就停。

**部分失败必须如实报告。** 二十部片里三部删不掉，既不是成功也不是失败：说成功会掩盖那三部还在，说失败会让人以为什么都没发生。`OperationResult` 记录成功数、失败项与原因、以及因同名被跳过的项，界面照实显示。

**剪切在同共享内是 `rename`**，服务端 O(1)——52 MB 的文件也是瞬时（实测 mtime 保持不变，证明没搬字节）。跨服务器才退化为复制 + 删除，且**只有复制成功的项才删源**：树的任何一处失败都保留源，因为源是唯一的副本。

**粘贴不覆盖。** 目标同名时跳过并报告，既不覆盖也不自动改名：覆盖是破坏性的，自动改名会产生用户没预期的名字，两者都该由用户先决定。

**删除前先统计。** 「删除 47 项」与「删除《第三季》」是不同的决定，所以确认框里的数字是走一遍目录数出来的（上限 500，避免巨大目录卡住对话框）。

**每次操作一条专属会话**（`connectDedicated`）。内核会话串行且超时 20 秒，共用浏览会话会让一次复制把目录列表冻在后面。

---

## 缩略图

浏览列表为视频显示一帧画面。取帧用 Media3 的 `FrameExtractor`（`media3-inspector-frame`），
它的 `Builder` 接受一个 `MediaSource.Factory`，所以直接复用 `KrystallosDataSource`——
**没有新的协议代码**，和播放走同一条数据通路。

**难点是成本，不是取帧本身。** 一帧要读容器索引（MP4 的 `moov` 常在文件尾、MKV 的 Cues
同理）再读一帧数据，所以：

- **读窗口 256 KiB，而不是播放用的 1 MiB。** 取帧只读索引加一帧，用播放的窗口会让每个
  缩略图的网络流量翻四倍。
- **只给可见行取。** 行的 `produceState` 在滚入时启动、滚出时取消。取消只停止*等待*；
  已经开始的取帧跑完并落缓存——那些字节已经拉回来了，丢掉只会让下次再付一遍。
- **先缩放到 320 宽再存。** `getThumbnail()` 返回的是**全分辨率帧**：原样存会让一张缩略图
  占约 120 KB，内存里更是 8 MiB 的位图，几张就撑满内存缓存。缩放后约 10–20 KB。
- **失败分两类。** 格式或解码类（容器/编解码器超出设备能力）记入负缓存，不再重试；
  网络类（`ConnectionLost`、超时、`NotFound`）**不记**——记了会让一次掉线把整个目录的缩略图
  永久毁掉。**默认是不记**，只有能明确归咎于文件的才写下来。

**并发 2 条 lane，每条一个 `HandlerThread`。** `FrameExtractor` 要求单实例单线程访问，而其
内部会构造 `ExoPlayer` 并触及 Looper——`HandlerThread` 两种情况都满足。这一点在写任何设计
之前先用一个 spike 验证过（`FrameExtractorSpikeTest`）。每条 lane 有**自己的会话**
（`ThumbnailSource`），且**取完即关文件**：与 `PlaybackConnection` 恰好相反——那个类为播放
保留句柄是必要的（每次 seek 都要重开），而缩略图是一部片读一次，在 NAS 上留几百个句柄毫无好处。

**缓存的图片，不是视频数据。** 磁盘存每部片一张 JPEG，内存存解码后的位图。淘汰规则：
总量在内存里增量维护（不每次扫盘）、超限淘汰到上限的 **90%** 而非刚好不超（否则下一次写入
又要淘汰，每次写入都退化成一次删除）、以文件 mtime 作「最后使用时间」（读时打点，所以
体现的是真实使用顺序而非写入顺序）、负缓存标记按 1 KB 名义计入（它们实际 0 字节但占 inode，
不计就永不淘汰）。上限为 0 表示**关闭缩略图并清空缓存**。

设置页可调上限（默认 100 MB，滑杆 0–1000 MB，更大的值手动输入）。两个控件都**只在用户结束
操作时写入**——存值会触发淘汰、淘汰要遍历缓存目录，一次拖动写几十次是不可接受的。

---

## 前向约束

**升到 targetSdk 37 时需要本地网络权限。** 36 下 `INTERNET` 隐式涵盖局域网访问；升到 37 后需要运行时申请 `ACCESS_LOCAL_NETWORK`，**且对内核的原生 socket 同样生效**，不加会直接连接失败。

**已确认的产品决策**：

- **不做 ASS/SSA 特效字幕。** Media3 不使用 libass，且这是产品级取舍而非能靠工程绕过的；因此也不引入 libmpv 副引擎——它虽能解决字幕，但在 Android 上无法输出 HDR，且需自维护四个 ABI 的 `.so`。
- **目标设备为手机/平板**，不做 Android TV，因此无焦点导航与 Leanback 需求。
- **尚未做**：后台播放 / MediaSession、续播位置、局域网发现、字幕。

---

## 已知风险

| 风险 | 说明 |
|---|---|
| **构建链依赖三样外部工具** | JDK、NDK、CMake，缺一不可。CMake 是因为内核构建 libsmb2 需要它生成平台相关的 `config.h`。 |
| **release 构建的 R8 与 JNA 冲突** | JNA 反射加载，R8 看不到调用图；`proguard-rules.pro` 里的 keep 规则不是可选的。 |
| **播放会话的读取是串行的** | 一次读卡住最多 20 秒，其间 seek 排在它后面。浏览用另一个会话，不受影响。 |
| **MKV 里的 AC3 / DTS / TrueHD 音轨** | 很多设备没有对应的硬件解码器，结果是有画面没声音。Jellyfin 预编译的 `media3-ffmpeg-decoder` 只到 1.9.0、跟不上 Media3 1.11，本轮作为已知限制。 |
| **每个 1 MiB 块都新分配一次** | 4K 码流约每秒 12 次大对象分配，目前可接受；真机上若出现 GC 卡顿，再改成复用缓冲。 |
| **模拟器需关掉 `HardwareDecoder` 才能播视频** | 模拟器把 goldfish H.264 解码器声明为硬件解码器，而播放器都按惯例优先选硬件解码器——它的输出在模拟器的 host 端 YUV→RGB 环节丢色度，画面全绿。**启动加 `-feature -HardwareDecoder`** 让播放器回退到 AOSP 软件解码器即可。这是已知上游问题（[Google #192401724](https://issuetracker.google.com/issues/192401724)、[androidx/media#2461](https://github.com/androidx/media/issues/2461)），真机不受影响。 |
