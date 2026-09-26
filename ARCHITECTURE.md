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
| `data/` | 服务器列表（`ServerRepository`）、自建播放列表（`PlaylistRepository`）、加密凭据（`CredentialStore`）、DataStore 的 JSON 序列化器 |
| `kernel/` | 对 UniFFI 绑定的薄封装：会话管理、路径拼接、关闭辅助 |
| `playback/` | Media3 数据源（见「播放数据面」） |
| `files/` | 重命名、删除、复制、剪切/粘贴（`FileOperations`），部分失败如实报告（见「文件操作」） |
| `thumbnails/` | 视频缩略图：取帧、磁盘缓存与淘汰（见「缩略图」） |
| `ui/` | Compose 界面，按屏分子包，每屏一个 ViewModel（`theme/`、无状态的「关于」除外）。`common/` 放跨屏的零件：列表与网格（`EntryList`）、选择模式（`Selection`）、错误态 |

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
- **列表与网格两种视图**，工具栏按钮切换，偏好存进设置（全局而非按服务器——这是「习惯怎么看」，不是某台 NAS 的属性；浏览页与播放列表页也共用它，所以一处切换两处同步）。网格用 `GridCells.Adaptive(110.dp)`：同一份代码在 412dp 的手机上给三列、800dp 的平板上给六列，不必去问屏幕宽度。这个值改过一次——原为 150.dp，理由是竖握手机上的三列会「小到读不清」，但那条理由对文字成立、对缩略图不成立：121dp 的一帧仍看得出是哪部片。两种视图共用同一个缩略图组件，所以「只给可见项取帧」这条规则只有一处实现。
- **重连是自动的，用户不该看到重试按钮。** 错误分成两类：**关于路径的答案**（找不到、无权限、已存在……）重连也不会改变，直接报错；**可能是连接问题**的，应用自己处理。判定在 `KernelException.describesThePath`，有单测钉住哪些算前者。三层递进：浏览会话内部丢弃死会话并重试一次；列表加载先快速重试两次（1 秒、2 秒退避），仍失败才显示错误；**显示错误之后仍每 10 秒静默重试**，直到成功或离开这个界面——服务器回来了列表应该自己出现，而不是等着人来点一下。
  这条策略存在的原因值得记下来：内核把 `ConnectionLost` 定义得很清楚（会话已死），但它**并不总能分辨**。`smb2_opendir` 这类 API 用空指针报失败，**没有返回码**可判，于是服务端关掉 socket 到达应用时是通用的 `Backend` 错误，只在文本里写着 `smb2_service: POLLHUP, socket error`。内核要认出它只能去匹配散文，而非文本的信号又不存在——`smb2_get_fd` 本可回答，但 libsmb2 在 POLLHUP 分支只设错误、不关 fd。所以应用取了安全的读法：**凡不是关于路径的陈述，都当作可能是连接**。这比匹配 C 库的错误措辞稳，也覆盖同类的其它表现。
- **名称原样使用**，拼路径不做任何规范化（见内核文档「Unicode 规范化」）。
- **可播放判定按扩展名**，WMV / RMVB 刻意不列入——ExoPlayer 没有对应的 extractor，标成可播放只会通向错误页。

### 设置

设置分两级：一级页只放入口（播放 / 存储 / 关于），每行用副标题写回当前值；控件本身在二级页。**写回当前值是这套结构成立的条件**——只写「播放」的话，从一级页看不出应用现在是什么状态，用户得逐个点进去才知道，那分成多页就是纯损失。

三条不显然的决定：

- **副标题只报设置，不报缓存用量。** 用量要读一次磁盘，而一级页在二级页改动它时不会重建（每个 entry 有自己的 ViewModelStore），数字会在最需要准的时候过期。用量留在「存储」页里实时显示。
- **一级页用 `SharingStarted.Eagerly` 收集设置流，不用 `WhileSubscribed`。** 后者在从二级页返回时重新订阅，首帧先发默认值，副标题会闪一下错的。
- **浏览的偏好（列表/网格、排序）不进设置页，留在浏览页工具栏。** 判据是「改的时候人在哪」，不是「它属于哪个类别」：在目录里换排序是一次点击，收进设置页则要先退出目录、改完再回来。

「关于」页没有 ViewModel——版本是编译期常量，页面无状态，没有东西需要它持有。

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

**控制条布局是抄来的**（`res/layout/player_controller.xml`）。Media3 没有移动中间那组按钮的 API——`controller_layout_id` 是个 **styleable**，`PlayerControlView` 只有读它的构造参数、没有 setter——所以想改布局只能把 `exo_player_control_view.xml` 复制进来自己改，`player_view.xml` 存在的唯一理由就是把这份布局交给 `PlayerView`。三处改动：

- **那组「上一个 / 后退 / 播放暂停 / 前进 / 下一个」搬进了 `exo_bottom_bar`**，与左边的时间、右边的设置同一行，`layout_gravity="center"` 居中。**组 id 必须留着 `exo_center_controls`**：`PlayerControlViewLayoutManager` 靠这个 id 做淡入淡出。它改成 `wrap_content`，且只在左右留 padding——底栏 60dp、这些按钮 52dp，原来四周 24dp 的 padding 在里面没有位置，`match_parent` 也会让下面那个测量读到整屏的宽度。
- 设置按钮换成 `ic_tune`，因为右上角已经有了应用自己的「播放设置」齿轮，同一块屏幕上的两颗齿轮会被当成同一个控件。
- **底栏最右端加了我们自己的横竖屏按钮**（`@id/player_orientation`，在一个独立的组里）。**它不能放进 `exo_basic_controls`**：`PlayerControlViewLayoutManager` 运行时会用 `removeViewAt` / `addView` 在那组和 `exo_extra_controls` 之间搬运子 View（溢出切换就是这么做的），放进去会被连人带行李搬走。作为补偿，Media3 那两个贴右端的组各加了 `layout_marginEnd="@dimen/exo_small_icon_width"` 给它让位——这一条安全，因为布局管理器全文只有一处 `setLayoutParams`，作用在 `timeBar` 上，对这两组只做 `setVisibility` / `setAlpha`。

**这个 id 还参与一处容易踩的测量。** `useMinimalMode()` 拿这组的宽度（`getWidth() + margins − 自身的 paddingLeft/Right`）与「时间 + 溢出按钮」的宽度取 `max`，再和可用宽度比：不够就切「最小化模式」，那时 `exo_bottom_bar` 整个 `GONE`，屏幕上只剩一个全屏按钮。搬这组按钮之前值得知道的两件事，都是从字节码里读出来的：`shouldHideInMinimalMode` **按 id 逐个判断**（`exo_bottom_bar`、`exo_prev`、`exo_next`、`exo_rew`、`exo_rew_with_amount`、`exo_ffwd`…），不看父子关系，所以嵌进底栏不影响它；而 `PlayerControlViewLayoutManager` 对这组只做 `setAlpha` 和上面那个测量，**不碰布局参数**，所以换了父容器也不会被改回去。

**竖屏装不下这一行，所以竖屏少放三个东西。** 实测（1080px 宽、密度 2.625）：时间 317px、那组按钮 941px、推子 136px、旋转按钮 136px，合计 1530px 对 1080px；把时间整个去掉、内边距清零仍差 7px。于是竖屏时 `exo_prev`、`exo_next` 与 `exo_time` 设成 `GONE`（在 `AndroidView` 的 `update` 里按方向设），剩 ↺ / 播放暂停 / ↻ 与右端两个图标，约 887px，宽裕。队列本身不受影响——影片照旧连播，只是这两个按钮不画。**这与 Media3 的「最小化模式」不是一回事**：那套按 id 隐藏 `exo_bottom_bar` 等，判据是它自己量出来的宽高，管不到我们新加的按钮。

空出来的那一格没有浪费，放的是**剩余时间**（`-12:34`）：`@id/player_remaining`，同样是竖屏才显示，横屏不显示——那里本来就有完整的「位置 · 时长」，再来一个倒计时是重复。它得自己刷新，Media3 只按 id 绑定它认识的那两个时间 View，这个 id 它从没见过，所以由 `PlayerScreen` 里一个 500ms 的 ticker 写进去（比 Media3 自己的 1 秒快一倍：竖屏下这是屏幕上唯一在动的东西，慢一秒会让人以为暂停没生效）。**负号不是装饰**：它占的是「已播时间」通常所在的位置，只写 `12:34` 会被读成位置，正好说反。格式与边界在 `RemainingTime.kt`，是纯函数，`RemainingTimeTest` 钉着——包括「不足一秒截断而不是进位」和「时长未知时返回 null 而不是 `-0:00`」。

代价要记住：Media3 的升级不会再进到这个文件，而 `PlayerControlView` 是按 id 逐个查找控件、**找不到就静默不接线**——改漏一个 id 不会报错，只会有一个按钮没反应。

顺带一条给下次换图标的：控制条上的图标必须**自带颜色**。`ExoStyledControls.Button` 只设 background、scaleType 和 margin，Media3 的样式里没有任何 `android:tint`，所以每个 `exo_styled_controls_*` 自己填 `#FFFFFFFF`。照 Compose 的习惯写成黑色 path，会得到一枚黑底上的黑图标。

**测试**继承 Media3 官方的 `DataSourceContractTest`（22 项契约），内核换成内存里的假 `ReaderSource`——DataSource 依赖接口而非 `Session`，正是为此。

### 播放器的三个行为

**进入即播放，且不在旋转时中断。** 这一条曾经是坏的，且坏法值得记住：`PlayerScreen` 为宽屏视频设置 `SENSOR_LANDSCAPE`，而清单当时没有声明 `configChanges`，于是横屏被当作配置变更、**Activity 重建**，旧界面的 `ON_STOP { player.pause() }` 触发——而 player 活在 ViewModel 里、配置变更不重建它。结果是视频打开、第一帧也在、然后停在那里：日志显示它只播了 24 毫秒。修法是清单声明 `configChanges`（顺带消除旋转时 SurfaceView 被拆掉重建的黑屏闪烁），再加上 `ON_STOP` 里跳过 `isChangingConfigurations`——后者在清单修好后不会触发，但别的重建原因（语言、分屏）会以完全相同的方式失败。

**方向由设置决定，不再看视频的形状。** 上面那条说的「为宽屏视频设置 `SENSOR_LANDSCAPE`」已经作废：在一个有「初始方向」设置的播放器里，那条规则要么多余要么打架——竖着拍的视频被强行横屏，而设置里写的是竖屏。现在设置就是答案，「跟随视频」这个选项随之取消；影片与设置不符时，用底栏最右那个按钮切。

**那个按钮的选择只影响本次播放，而且只能放在 ViewModel 里。** 放 `remember` 会丢：播放器被设置页盖住时组合会被销毁（`dumpsys activity top` 看过，PlayerView 与 SurfaceView 都不在视图树里），回来就退回设置的方向。ViewModel 熬得过这一程，而出栈时被清掉——正好就是「本次播放」。按钮点击时读的是**那一刻**设备的方向（`context.resources.configuration`），不是捕获进来的值：`AndroidView` 的 factory 只跑一次，捕获的值会永远是影片刚打开时的方向。

**进播放设置会暂停影片，这是留着的。** 不在 `PlayerScreen` 里为它单开例外：暂停写在同一处 `ON_STOP`，而被应用内页面盖住时播放器 entry 的生命周期同样走到 STOPPED——一条规则「离开播放页就暂停」比两条各自成立的规则更难写错。代价可以接受，因为播放设置页是不透明的，画面本来也看不见。真要说有什么损失，是改「画面比例」的人回来要再按一次播放。若哪天要改成「回来接着播」，判据是 **Activity 是否真的不可见**：被自家页面盖住时它仍是 STARTED/RESUMED，离开应用时才是 CREATED，用 `activity.lifecycle.currentState.isAtLeast(STARTED)` 就能分开——但别改成只在 `PlayerScreen` 组合期内注册的观察者，播放器被设置页盖住后那个组合会被销毁，从此离开应用就再也没人暂停它，影片会在后台一直响。

**被盖住再回来，播完的影片要倒回开头才有画面。** 上上条说的那次销毁把 surface 也带走了。影片播到一半没事——解码器手里还攥着当前帧，新 surface 会把它画出来，所以中途进设置回来画面正常。**播完的不行**：解码器走到流末尾就把那一帧放了，重新挂一个 surface 不会让它再产一帧，于是画面全黑，一直到有东西播放为止，而这时什么都不会播。`redrawIfFinished()` 用一次 seek 让它重新解码并绘制；位置取**开头**，因为那正是这个播放器接下来会播的地方——播完的 ExoPlayer 按 play 就是从 0 重播。这也是唯一不破坏这一点的选法：seek 到「离末尾差一点」会让它不再算「播完」，播放键按下去只播最后那一瞬，看起来就是个死键。

**「下一个」先播起来再补列表。** 进播放页立刻播被点的那一项：一次目录列举的往返在慢链路上是几秒的空白。列举回来后用 `addMediaItems` 把同目录的其他可播放项插到当前项前后——Media3 的插入不打断当前播放，索引跟着平移，而不是用 `setMediaItems` 重来。「自动播放下一个」这个设置就是「列表里有没有别的条目」：关掉时不添加，列表只有一项，播完即停，上一/下一按钮也没有目标。

**播放列表的顺序与浏览页一致**，因为它复用同一个 `EntrySorting.playableInOrder`（排序键、方向、中文与数字排序、可播放判定都是同一份）。两边不一致的话，用户看到的顺序与实际播放的顺序会不同——那种 bug 只在连播时暴露，且看起来像「跳了一集」。

**画面比例**（适应 / 拉伸 / 裁切）对应 `PlayerView.resizeMode` 的 FIT / FILL / ZOOM。默认「适应」：另两个一个会畸变、一个会裁掉画面，应该是用户主动选的，而不是打开影片就撞上的。它应用在 `AndroidView` 的 `update` 里而不是 `factory`——`factory` 只跑一次，之后改设置就再也到不了那个 View。

### 播放列表

每台服务器一个自建列表，条目是路径，按加入顺序连播。它与「目录连播」是两条**独立的队列来源**：从列表点播时队列就是列表，`PlayerViewModel` 不去列举父目录——`extendPlaylist` 里一个分支的分岔就是这两种模式的全部区别。

**为什么按服务器分，而不是全局一个。** 播放层一个播放器只持有一条专属会话：`PlaybackConnection` 的 `connect` 是 `suspend () -> Session`，serverId 在 `PlayerViewModel` 构造时就被闭包吃掉了；`ReaderSource.reader` 只收 path；`KrystallosDataSource` 拿到了完整的 `dataSpec.uri` 却从不读 `uri.authority`。于是跨服务器的条目会被拿去问**当前**服务器——路径恰好也存在的话**静默播错文件**，只有不存在时才报 `NotFound`。前者才是危险的，而且不留痕迹。按服务器分列表从根上排除了这种条目；缩略图早就因为同一条约束做了「每服务器一个 source」。

**从列表播放不理会「自动播放下一个」。** 那个设置管的是「从目录点一个文件播放」的场景；用户从列表里点播，本身就是要求按这个列表连着播。让同一个开关在两处含义不同，比让它只管一处更难解释。

**条目只存路径**，文件名与所在目录都现算。存下来的名字会在文件被改名之后继续说谎，而它旁边的路径已经指向别处。

**加入顺序就是播放顺序**，不排序——这正是它与目录连播的差别。

**只有能播的条目进得去**：文件夹、文本文件都不行。这个列表存在的意义就是从头播到尾，放不进播放器的条目只会变成一条通向错误页的行。重复的与不能播的都被跳过，且**跳过的数量如实报告**——同名文件的失败要报，这里同样。

### 底部栏与两个 tab

底部栏两个 tab：**服务器**与**播放列表**。播放列表 tab 的根是选服务器（每行只写「已加入视频」或「还没有加入视频」，地址不重复——那是服务器 tab 的事），进去才是那台服务器的列表。

**底部栏只在两个 tab 的根页面显示**，进目录、进列表、播放时都全屏。这条约束顺带省掉一个本来要处理的问题：既然只有根页面才切得了 tab，**离开一个 tab 之前必须先退回它的根**，于是非活动的那一侧永远停在根上——两个 tab 各持一条返回栈，不会积累出「另一侧还停在深层」的状态，进程死亡后要恢复的也只有当前那一侧。

**两个列表页共用同一套列表/网格/选择模式**（`ui/common/EntryList.kt`），差别只在每一行的来源与能做的事：列表页的副标题是所在目录而不是大小与日期，动作只有移除。抽出来时顺手修掉一处隐患——LazyColumn 的 key 原本是名字，而播放列表里不同目录可以有同名文件，**重复 key 会让惰性列表错位**，所以改成每行自己的标识（浏览页用名字，列表页用路径）。

**缩略图会存两份，这是知情的代价。** `ThumbnailKey` 的 hash 含文件大小与修改时间（为的是文件被替换后换一帧），而播放列表只有路径，只能传 `null`，于是同一部影片在两个页面算出的 hash 不同，磁盘与内存各存一份。另外两条路都更贵：逐项 `stat` 补元数据，是 `DirEntry` 文档明确警告过的「每项一次往返」；按目录分组列举，会把一个**本来纯本地的页面**变成依赖网络、会失败的页面。传 `null` 换来的是**播放列表页离线也能打开**——名字与所在目录都在，只有缩略图退化成图标。

### 文件操作

重命名、删除、复制、剪切/粘贴，以及**信息**。长按进入选择模式：长按即选中该项（工具栏就是它的菜单），再点其他项即多选。重命名与信息只在恰好选中一项时可用——那是「对单项操作」与「批量操作」真正分岔的地方，一个模式因此覆盖两种用法；信息还多一个条件：那一项得**可播放**，「这个文件是什么编码」只对媒体成立。

**复制与剪切合成一个「剪贴板」菜单。** 两者本来是一件事，各占一个图标也不划算：竖屏下 5 个图标时标题「已选 N 项」是一行，加到 6 个就折成两行——宽度是量出来的，不是感觉。菜单挂在同一个图标下，两项就是原来的两个动作。

**递归在应用侧。** 内核只做单项：`remove_dir` 拒绝非空目录、`copy` 只复制一个文件。这不是遗漏而是刻意的——让调用方决定「做了一半」意味着什么。`FileOperations` 承担这件事：显式的工作队列（不是递归调用，深目录不会爆栈）、逐项继续而不是遇到失败就停。

**部分失败必须如实报告。** 二十部片里三部删不掉，既不是成功也不是失败：说成功会掩盖那三部还在，说失败会让人以为什么都没发生。`OperationResult` 记录成功数、失败项与原因、以及因同名被跳过的项，界面照实显示。

**剪切在同共享内是 `rename`**，服务端 O(1)——52 MB 的文件也是瞬时（实测 mtime 保持不变，证明没搬字节）。跨服务器才退化为复制 + 删除，且**只有复制成功的项才删源**：树的任何一处失败都保留源，因为源是唯一的副本。

**粘贴不覆盖。** 目标同名时跳过并报告，既不覆盖也不自动改名：覆盖是破坏性的，自动改名会产生用户没预期的名字，两者都该由用户先决定。

**删除前先统计。** 「删除 47 项」与「删除《第三季》」是不同的决定，所以确认框里的数字是走一遍目录数出来的（上限 500，避免巨大目录卡住对话框）。

**每次操作一条专属会话**（`connectDedicated`）。内核会话串行且超时 20 秒，共用浏览会话会让一次复制把目录列表冻在后面。

**文件信息**（长按单个可播放文件 →「信息」）走的是 `androidx.media3.inspector.MetadataRetriever`：一次拿到每个 track 的 `Format`（编码、分辨率、帧率、声道、采样率、语言、容器）与时长，**不解码、也不建播放器**。选它而不是自己包 `media3-extractor` 的理由很实在——它的 `Builder` 吃一个 `MediaSource.Factory`，而缩略图那套已经在 `KrystallosDataSource` 上建好了 `ProgressiveMediaSource.Factory`，直接复用；自己写 `ExtractorOutput` 收 `Format` 是几百行换同样的结果。两条约束：**这个依赖不是传递的**（`media3-inspector-frame` 不带它，只有 `media3-test-utils` 在 androidTest 里 runtime 依赖），要显式引入；以及它和 `FrameExtractor` 一样**必须从同一个线程构建、使用、关闭**，所以探测自带一条用完即弃的 `HandlerThread`。探测用**专属会话**，理由同文件操作——一次读文件头不该让目录列表排队。

**总码率是估算，所以带着 `≈`。** 容器不写「整个文件多少码率」，只有每条轨道各自声明的（而且常常是 `NO_VALUE`）。界面上的总码率一律是 `文件大小 × 8 ÷ 时长`，用 `≈` 标明；每条轨道那一行则**只在容器确实写了的时候**才出现——把估算值重复到每条轨道上，等于假装它被测量过。

**「读不出来」不是空对话框。** 大小、三个时间、路径这些都来自目录列表，不花一次往返、也永远为真，所以读文件头失败时它们照常显示，失败提示与「重试」加在上面。这也是为什么状态里 `failed` 与 `sections` 并存，而不是一个 `Failed` 状态把内容清空。

**创建时间、访问时间、只读是免费的**——`EntryMetadata` 本来就有这三个字段，一直是 `EntrySorting.DirEntry.toItem()` 把它们丢掉的。**不要**为了显示它们去逐项 `stat`：`DirEntry` 的文档写着那是「让文件浏览器在大量目录下显得坏掉的最快方式」，每项一次往返。

### 播放器里的「视频信息」

顶栏第二个图标，**排版共用、外观不共用**，而且**问的是另一个问题**：浏览器那份说「这个文件是什么」，这份说「播放器正在怎么放它」。`ui/common/InfoDialog.kt` 共用的是结构与文字（标签列、值换行、分组标题、空分组＝无），颜色由调用方给：浏览器用主题化的对话框，播放器用**居中的半透明黑底圆角面板**浮在画面上——浅色的 Material 表面出现在影片中间，会有一瞬间像换了个应用，而面板是播放器自己的家具，与控制条同源。尺寸是屏幕的 60%×64%，四周都留着画面，这本身就是「影片还在、这只是盖在上面」的提示。**点面板外或按返回关闭**：点画面让盖在它上面的东西消失，是每个播放器都有、每个人都会的那一个手势，所以没有按钮也没有提示。面板内部的点击被自己吃掉，点标签不会误关。

第一次做错的地方值得记：铺满全屏的那版把 `PlayerInfoOverlay` 画在 `AndroidView` **之前**，整个被 `SurfaceView` 盖住、什么都不显示；位置对（Box 里的最后一个）但顺序错，症状是「暂停生效了、面板没出来」。所以数据来源不同——轨道取自**正在跑的播放器**（`player.getCurrentTracks()` → `Group.getTrackFormat(i)`），不重新探一次文件：省一次往返，而且它描述的是**实际在播的那条流**，还包括「这台设备能不能解这条轨道」（`Group.getTrackSupport(i)`，文件自己答不了这个）。另加一组「解码」。

**解码器名字只能靠回调拿。** Media3 没有任何同步查询 API——没有 `getCurrentDecoder()`——名字只在 `AnalyticsListener.onVideoDecoderInitialized` 里出现一次，所以必须在 ViewModel 里挂 listener、一路缓存；mime 另从 `onVideoInputFormatChanged` 取，解码器回调不带它。listener 在构造时就挂上（解码器只在开头初始化一次），且**移除必须传同一个实例**（Media3 按身份认），所以它是字段而不是 inline 对象。

**软/硬解不要自己判前缀。** `MediaCodecInfo.hardwareAccelerated` 就是平台 `isHardwareAccelerated()`（API 29+）的封装，而 **API<29 时 Media3 自己回退到前缀判断**（`c2.android.`/`omx.google.`/`omx.ffmpeg.`/`omx.sec.*.sw.` → 软件，`arc.` → 硬，音频一律软件）。自己再写一套既是重复它的代码，又会跟它不一致。做法是按名字在 `MediaCodecUtil.getDecoderInfos(mime, false, false)` 里匹配；**匹配不到就只显示名字、不标软硬**——和「认不出的 mime 原样显示」同一条规矩。实测：模拟器上显示 `c2.android.avc.decoder` + 软件解码，与那条模拟器约束（`-feature -HardwareDecoder`）正好对上。

**信息描述的必须是「正在播的那个文件」，不是「当初点的那个」。** 这条踩过：对话框里「名称」取自跟随队列的标题、「路径」取自路由参数，于是自动连播切集之后两个字段描述的是**不同文件**（名称是下一集、路径是上一集）。现在都从 `playingPath` 来——它在 `onMediaItemTransition` 里更新，与标题同源。名称还要**带扩展名**：标题为屏幕好看会去掉它，信息里去掉就与路径对不上了。

**打开即暂停，关掉保持暂停**——与「进播放设置就暂停」同一条规则。两条暂停规则里一条会自动恢复、另一条不会，是没人记得住的东西。轨道与解码是打开那一刻的快照：影片此时暂停，没有活的东西需要刷新。

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
| **播放器页面上 `uiautomator dump` 不可用** | 在播放中/暂停的播放器页面，`adb shell uiautomator dump` 报 `ERROR: could not get idle state`，**不出快照**——而它失败时**不会删掉上一次的 `/sdcard/*.xml`**，于是脚本读到的是一份陈旧层级，判定全是假的。这一轮里它先后骗出过两次「点面板内部会误关」的结论，实际上代码没问题。播放器页面的判定改用 `adb exec-out screencap` 截图；若要用 dump，先 `rm -f` 那个文件。 |
| **模拟器需关掉 `HardwareDecoder` 才能播视频** | 模拟器把 goldfish H.264 解码器声明为硬件解码器，而播放器都按惯例优先选硬件解码器——它的输出在模拟器的 host 端 YUV→RGB 环节丢色度，画面全绿。**启动加 `-feature -HardwareDecoder`** 让播放器回退到 AOSP 软件解码器即可。这是已知上游问题（[Google #192401724](https://issuetracker.google.com/issues/192401724)、[androidx/media#2461](https://github.com/androidx/media/issues/2461)），真机不受影响。 |
