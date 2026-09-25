# HyalosPlayer

Android 局域网媒体播放器。把 NAS 上的影片当作本地文件来浏览和播放。

网络访问能力由独立的 Rust 内核 [Krystallos](https://github.com/sheepthefather/Krystallos) 提供，首个支持的协议是 SMB2/3。

## 当前状态

**首个可用版本**：添加服务器 → 浏览共享目录 → 播放影片的完整闭环已经打通，并在一台真实的 SMB 共享上端到端验证过。

| 部分 | 状态 |
|---|---|
| Gradle 工程 + Rust 构建链路 | ✅ |
| Kotlin 绑定生成 | ✅ |
| 服务器配置与连接（含密码加密存储） | ✅ |
| 目录浏览 | ✅ |
| 播放（Media3 + `KrystallosDataSource`） | ✅ |
| 后台播放 / MediaSession、续播、字幕、局域网发现 | ⬜ |

**模拟器上验证不了画面。** 本机模拟器把任何视频都渲染成纯绿色（系统自带播放器同样如此），所以画面正确性需要在真机上确认；模拟器能验证的是解码启动、时长解析、位置推进与 seek。详见 [ARCHITECTURE.md](ARCHITECTURE.md#已知风险)。

## 前置条件

- **JDK 17+**（本机用 21）
- **Android SDK**，含 NDK。`ANDROID_HOME` 指向 SDK 根目录
- **Rust** 与三个 Android target：
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```
- **CMake 3.x / 4.x** —— Krystallos 构建 libsmb2 需要

## 构建

```bash
git clone --recursive https://github.com/sheepthefather/Hyalos-Player.git
cd Hyalos-Player
./gradlew assembleDebug
```

**`--recursive` 不能省。** 内核 Krystallos 是一个 submodule，而它自己又内嵌 libsmb2 这个 submodule——**两层嵌套**，所以要递归拉取。已经克隆过但忘了 `--recursive` 的话：

```bash
git submodule update --init --recursive
```

两个 submodule 分别在：

| 路径 | 内容 |
|---|---|
| `vendor/krystallos` | Rust 内核 |
| `vendor/krystallos/vendor/libsmb2` | 内核依赖的 SMB 库 |

### 同时开发内核时

默认构建的是 submodule 的**工作树**，不是某个提交快照。所以在 `vendor/krystallos` 里改代码（哪怕没提交）下一次构建就会生效。

若你在别处有一份独立的 Krystallos 检出：

```bash
./gradlew assembleDebug -Pkrystallos.dir=/path/to/Krystallos
```

## 构建链路

```
Krystallos (Rust)                     HyalosPlayer (Kotlin)
─────────────────                     ─────────────────────
cargo ndk -t arm64-v8a ...   ──┐
                               │
                    app/build/rustJniLibs/<abi>/libkrystallos_ffi.so
                               │
uniffi-bindgen generate        │
       │                       │
       └──> app/build/generated/uniffi/uniffi/krystallos_ffi/krystallos_ffi.kt
                               │
                        AGP 打包 APK
```

内核源码在 `vendor/krystallos`（submodule）。Gradle 只是**在它的目录里启动 `cargo`**——Gradle 本身对 Rust 一无所知，`dependencies { }` 里没有任何一条提到 Krystallos。

两个 task 由 `assembleDebug` 自动触发，依赖是显式连起来的：

- `buildRust` —— 交叉编译内核到三个 ABI。`generateUniffiBindings` 通过 `dependsOn` 显式依赖它。
- `generateUniffiBindings` —— 从编译好的 `.so` 生成 Kotlin 绑定。

而这两个 task 与 Android 构建之间的边是 **AGP 推断的**：`addGeneratedSourceDirectory` 告诉 AGP「这个目录由这个任务产出」，于是 `mergeDebugJniLibFolders` 和 `compileDebugKotlin` 会自动等它们。

源码目录通过 **Variant API** 贡献给 AGP（`androidComponents.onVariants`），不是旧的 `android.sourceSets` DSL。AGP 9 会直接拒绝后者——它无法判断 `Provider` 指向的是生成文件还是静态文件，因而拒绝猜测。Variant API 让这个区别显式，而且生成目录的任务依赖由 AGP 自动接上。

## 几个必须知道的约束

这些是踩过的坑，改动构建配置前请先读：

**1. 不要加 `org.jetbrains.kotlin.android` 插件。**

AGP 9 内置 Kotlin 支持且默认启用，而独立的 Kotlin Android 插件与它的新 DSL **不兼容**——加上去会直接让构建失败。Kotlin 编译现在是 AGP 的一部分。

**2. JNA 必须用 `aar` 变体，不是默认的 jar。**

```kotlin
implementation(variantOf(libs.jna) { artifactType("aar") })
```

生成的 UniFFI 绑定通过 JNA 的 direct mapping 加载 Rust 库。jar 里没有 `libjnidispatch.so`，用它会得到一个运行时的 `UnsatisfiedLinkError`——而且错误信息既不提 JNA 也不提变体问题。

**3. `cargo ndk` 的 `-P 29` 不能省。**

cargo-ndk 默认按 API 21 构建，低于本项目的 minSdk，产出的库会链接到更老的 libc。

**4. release 构建不要 `strip = true`。**

UniFFI 的元数据符号是只读数据，不在 `.dynsym` 里。完整 strip 会移除 `.symtab`，于是 `uniffi-bindgen` 在**真正要发布的产物**上报 `No UniFFI metadata found`，而 debug 构建却正常。Krystallos 的 release profile 用 `strip = "debuginfo"`。

**5. 用 R8 时必须保留 JNA 的类。**

见 `app/proguard-rules.pro`。JNA 是反射加载的，R8 看不到调用图，会把需要的类剥掉或改名——症状是只在 release 构建里出现的 `UnsatisfiedLinkError`。

## 许可证

GPL-3.0-or-later。见 [LICENSE](LICENSE)。

依赖的 [Krystallos](https://github.com/sheepthefather/Krystallos) 同为 GPL-3.0，其中 vendored 的 libsmb2 是 LGPL-2.1-or-later。
