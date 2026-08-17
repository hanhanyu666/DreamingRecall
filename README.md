# DreamingRecall

DreamingRecall 是面向 Minecraft 1.21.1 NeoForge 的服务器全局回放模组。专用服务器与单人游戏的集成服务器共用同一套录制、档案和未来播放架构；安装后默认不录制，必须由单人玩家或服务器管理员明确开启。

当前版本是 `0.1.0-alpha.1`。它已经形成从服务器录制、归档，到客户端浏览、隔离 3D 世界状态重建、时间轴播放和基础导演编辑的可运行纵向切片；高级编辑能力和大规模性能验收仍在继续。

## 开发环境

- Minecraft 1.21.1
- NeoForge 21.1.228
- ModDevGradle 2.0.141
- Gradle 9.2.1
- Java 21
- Mod ID：`dreamingrecall`
- Java 包：`com.hhy.dreamingrecall`

构建版本和运行任务参考了 `DreamingFishCore-1.21.1` 的环境配置。DreamingRecall 不复用 DreamingFishCore 的业务代码、模组身份或资源。

## 当前已实现

- 专用服务器和单人集成服务器使用相同的全局录制入口。
- 录制默认关闭，可手动开启，也可配置下次服务器启动时自动开启。
- 统一记录所有维度、玩家加入/离开与状态、变化实体、区块基线、方块与方块实体变化/移除、光照、天气、世界边界、普通游戏声音和实际送达各玩家聊天框的标准消息。
- 区块基线使用稳定资源 ID、方块属性、紧凑调色板、群系、方块实体 NBT 与权威光照；单个异常方块实体会局部降级，不会使整个区块基线失败。
- 有界内存队列与独立写入线程负责哈希、压缩、校验和磁盘写入，服务器 tick 线程不等待持久化。
- 过载时先丢弃可选增强；核心数据无法入队时写入明确缺口，并要求重新建立基线。
- 独立压缩段、CRC32C、原子提交、完成标记与 `.partial` 尾段恢复。
- 区块基线进入 SHA-256 内容寻址存储，重复内容去重；播放器数据源会透明解析引用。
- 严格有界的便携数据解码器可重建不可变的维度、区块、方块覆盖、实体、玩家、聊天、缺口与诊断状态；缺失或损坏内容会局部降级为带原资源 ID 的占位记录。
- 分段边界状态检查点支持随机跳转；本地异步档案数据源会取消被新拖动请求取代的读取，并严格要求 Minecraft 版本相同。
- 录制端后台线程会持久化格式 `1.1` 状态检查点；长档案打开时无需先扫描完整时间线，跳转按需读取最近检查点并缓存工作集，损坏检查点会回退到更早状态。既有 `1.0` 档案继续使用兼容的临时索引路径。
- 连续正向播放复用当前已解码分段，不会把每一帧都实现为一次完整随机跳转。
- 独立 `.drdirector` 导演项目支持相机关键帧新增、移动、删除与路径采样，包含位置、yaw、pitch、roll、FOV、线性/平滑插值、最短角旋转和跨维度硬切。
- 可选高精度第一人称相机轨道由玩家和服务端双重 opt-in 控制，默认关闭，并带服务端校验与采样限速。
- 专用服务器档案可同时包含全局语义轨道与按玩家 UUID 隔离的客户端精确轨道。装有 DreamingRecall 的玩家会在服务端录制期间上传其实际收到的有界客户端包流和玩家视觉样本；档案仍默认以全局语义视角打开，选择对应玩家后才切换到该玩家的精确轨道。
- 客户端精确轨道具有独立的序列、内存和后台写入上限。连接引导缺失、上传断流、服务端积压或关键增强记录丢失都会把该玩家轨道标记为残缺，播放器随后只对该玩家回退到全局语义回放，不会尝试硬播损坏包流，也不会影响其他玩家轨道或整个档案。
- 客户端档案库会发现客户端目录与单人存档中的本地档案；标题界面 `Replays` 按钮或可配置的 `F8` 键可打开档案库与时间轴。
- 时间轴原型支持播放、暂停、倍速与拖动跳转；拖动会暂时暂停，释放后从目标位置恢复此前的播放状态，并显示重建状态数量和诊断。
- 回放世界只在客户端隔离创建；服务端只保存档案，不承载回放世界，也不提供档案浏览、远程读取或下载协议。管理员通过 SSH、SFTP、面板或其他已有文件渠道手动复制档案到客户端的 `dreamingrecall/replays` 目录，这是专用服务器档案进入播放器的唯一流程。
- 自动录制档案支持配额与保留空间保护；手动录制档案不会被自动轮换删除。
- 资源包附件使用内容哈希去重并执行大小上限，明确拒绝模组 JAR；播放时仍优先使用客户端已有的兼容模组资源。
- Distant Horizons、Voxy 等远景渲染器具有无硬链接的可选适配器边界，单个适配器连续失败只会隔离该适配器，不会终止核心回放。
- 版本化回放扩展 API、负载上限及按播放会话隔离的扩展故障边界。
- `/dreamingrecall status` 显示队列、丢弃、分段以及采集 p95/p99 耗时。

## 尚未实现

- 贝塞尔切线、曲线图、多轨道等高级导演编辑能力。
- 完整的未知方块、物品与实体占位渲染器。
- 服务器资源包的自动发现，以及 Distant Horizons 与 Voxy 的具体公开 API 适配实现；当前只有稳定的软依赖边界。
- 内置视频导出；第一版明确暂不实现。
- 大型模组包 20 玩家 A/B MSPT、两小时连续播放及 10,000 次随机跳转验收。

这些项目的设计基线见 [docs/MVP-ARCHITECTURE.md](docs/MVP-ARCHITECTURE.md)。当前性能数据只证明开发环境中的录制路径工作正常，不代表大型模组服性能目标已经完成验收。

## 管理命令

专用服务器要求权限等级 2；单人集成服务器可直接使用。

```text
/dreamingrecall record start
/dreamingrecall record stop
/dreamingrecall record status
/dreamingrecall status
/dreamingrecall archives
/dreamingrecall config announce <true|false>
/dreamingrecall config autoRecording <true|false>
/dreamingrecall config captureChat <true|false>
/dreamingrecall config clientCameraTracks <true|false>
/dreamingrecall config automaticQuotaMiB <整数>
```

其余性能选项位于世界的 `serverconfig/dreamingrecall-server.toml`。聊天记录默认开启，高精度相机轨道在玩家端和服务端均默认关闭：管理员先允许上传后，玩家可用客户端按键（默认 `F9`）自行开启或关闭。

服务端档案位于：

```text
<world>/dreamingrecall/replays/<archive-id>/
```

手动复制到客户端后，客户端默认扫描：

```text
<minecraft>/dreamingrecall/replays/<archive-id>/
```

DreamingRecall 不会连接远程服务器列目录或下载这些文件，也不会向普通玩家发送档案。远程服务器上的文件获取完全交给管理员现有的文件管理方式。

## 三种录制模式

1. 单人游戏或仅客户端安装：按普通客户端回放方式保存本机实际收到的包流，并叠加本地玩家视觉状态；档案直接在本机播放。
2. 多人游戏仅服务端安装：保存服务端可观察到的全局区块、实体、玩家状态、聊天、声音和插值数据。这是重构后的全局语义回放，覆盖范围和稳定性优于早期版本，但没有客户端实际收到的完整包流、精确本地玩家动作结果、精确第一人称相机以及部分纯客户端效果。
3. 多人游戏双端安装：服务端档案保留第 2 项的全局语义轨道，同时为每个安装客户端模组且轨道完整的玩家保存精确轨道。播放器默认进入全局视角；选择该玩家时使用其原版客户端包流和高频玩家视觉样本，选择没有完整精确轨道的玩家时继续使用语义回放。

精确轨道不会执行任意未知模组网络负载。Minecraft 原版包直接进入隔离回放连接；模组资源优先使用本地已有内容，模组自定义行为需要版本化回放扩展明确适配。高精度相机采样仍是独立的双重 opt-in，关闭它不会关闭客户端包流和玩家动作精确轨道。

## 构建与测试

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runServer
.\gradlew.bat runClient
```

完整校验某个档案：

```powershell
.\gradlew.bat inspectArchive "-Parchive=D:\path\to\archive"
.\gradlew.bat inspectReplayState "-Parchive=D:\path\to\archive"
```

开发服务器手测流程：

1. 执行 `.\gradlew.bat runServer`，等待 `Done`。
2. 输入 `dreamingrecall record start`。
3. 进入服务器，跨维度移动、加载区块、放置和破坏方块、生成实体并发送公开/私聊/系统消息。
4. 多次执行 `dreamingrecall status`，确认队列不会持续增长，`dropped core` 保持为 0，并记录 p95/p99。
5. 输入 `dreamingrecall record stop`，等待收尾后再输入 `dreamingrecall archives`。
6. 停服，对最新目录运行 `inspectArchive`，预期 `Healthy: true` 且存在 `completion.json`、`segments/*.drseg`、`checkpoints/*.drseg` 与 `content/**/*.drcontent`。

## 设计边界

同一 Minecraft 版本是硬要求，模组列表和模组版本不是硬要求。未来播放时，本地存在兼容模组就优先使用其资源和渲染；缺失或不兼容的内容应按原资源 ID 局部占位并给出诊断，而不是拒绝打开整个档案。

DreamingRecall 记录可观察的回放状态，不是服务器备份、回滚工具或模组逻辑的确定性重执行器。未知自定义网络包不会被无条件归档。

## 许可证

LGPL-3.0-or-later。
