# 茶茶日历跨端与云同步架构

## 目标

在不破坏 Android 现有“轻量、离线优先、少后台开销”设计的前提下，增加 Windows、Linux、iOS 客户端和跨设备云同步。

核心约束：

- 日历、农历、重复规则、倒数日、纪念日等计算默认离线完成。
- 空闲状态不维持常驻网络连接，不使用 WebSocket 心跳。
- 不引入 Electron、Flutter 等大运行时到现有 Android 工程。
- 同步采用“启动/回前台/用户主动刷新/本地写入后的短任务”触发。
- 云端不可用时，本地数据库仍完整可用。
- 客户端不把系统日历 ID 当成跨设备数据；它只属于当前设备。
- 桌面和移动端共用同一份业务模型和冲突规则。

## 现有 Android 设计结论

当前代码有几个非常明确的轻量化取向：

1. UI 使用原生 Activity/View 和自绘 View，没有 Compose/Fragment 导航层。
2. EventStore 直接基于 SQLiteOpenHelper，数据路径短，模型和数据库字段一一对应。
3. MonthCalendarView 直接 Canvas 绘制，并复用 Paint/RectF/Map；只有存在跑马灯文字时才按需请求下一帧。
4. 桌面组件使用系统 RemoteViews/时钟能力，不做分钟级后台定时。
5. 提醒交给系统 CalendarProvider 和系统日历通知，不保活自有 Service。
6. 节假日按年缓存并限制刷新频率；农历、节气主要离线计算。
7. GitHub 版本检测使用短生命周期线程、ETag 和本地缓存，不引入网络框架。

这些选择的共同原则是：**把长期任务交给操作系统，把可缓存数据缓存，把计算集中到用户真正需要它的时候。**

## 跨端技术选择

采用：

- 共享业务核心：Rust
- Windows/Linux/iOS UI：Slint
- 本地持久化：SQLite（后续接入）
- 云同步第一后端：WebDAV + ETag 条件请求
- Android：保持现有 Java UI，只增加很薄的同步适配器，不重写 UI

不采用 Electron：空闲内存和安装体积与本项目定位不符。
不优先采用 Flutter：统一 UI 很方便，但会给现有 Android 带入额外运行时和完全不同的渲染体系。
不优先采用 Tauri：比 Electron 轻，但仍依赖 WebView；日历这种高频原生滚动/小组件型应用更适合纯原生/原生绘制路线。

Slint 的优势是 Rust 业务逻辑可直接复用，同时支持 Windows、Linux、iOS，并保持事件驱动 UI；后续也可以在不改核心协议的情况下替换某个平台 UI。

## 目录规划

```
app/                         # 现有 Android 源码，继续保持轻量
cross-platform/
  src/
    main.rs                  # Windows/Linux/iOS 共用入口
    model.rs                 # 跨端事件模型
    sync.rs                  # 同步快照与合并算法
  ui/
    app-window.slint         # 共用 UI
  project.yml                # iOS XcodeGen 工程描述
docs/
  CROSS_PLATFORM_ARCHITECTURE.md
  SYNC_PROTOCOL.md
```

## 性能预算

目标不是跑分，而是稳定限制长期资源占用：

- 空闲 CPU：接近 0%，禁止固定帧率轮询。
- 空闲网络：0 请求。
- 同步：只传一个小 JSON 快照，支持 ETag 304/412。
- UI：只有动画/滚动/跑马灯期间重绘。
- 数据库：事务内批量写入，不做逐帧查询。
- 重复规则：以原始锚点计算，不展开保存未来每一次实例。
- 系统提醒：各平台优先注册到系统日历/通知框架，不维持常驻进程。

## 云同步为什么先选 WebDAV

WebDAV 可以直接工作在 Nextcloud、群晖、坚果云类兼容服务或自建服务器上，不需要为茶茶日历先维护一套账号服务器。

客户端只需要：
1. GET 同步文件并带 If-None-Match。
2. 本地合并。
3. PUT 并带 If-Match。
4. 412 时重新拉取、合并、重试。

这样云端只是“可靠文件存储”，冲突算法完全由客户端控制。

## 数据边界

跨设备字段：
- 全局 UUID
- 标题
- 原始日期
- 类型
- 可见天数
- 重复规则
- 时间
- 完成状态/完成到哪一期
- 农历字段
- 甘特起止
- 同步逻辑时钟

设备本地字段：
- Android systemEventId
- Windows/Linux/iOS 对应的系统日历事件 ID
- 窗口位置、主题、字号等纯设备偏好
- 更新检测缓存

## 同步调度

默认触发：
- 应用启动后延迟一次
- App 从后台回前台且距离上次成功同步超过 5 分钟
- 本地新增/编辑/删除后延迟 1~3 秒合并一次
- 用户手动下拉刷新

不采用：
- 常驻后台服务
- 秒级/分钟级轮询
- WebSocket 心跳
- 为同步单独启动长期线程

## 下一阶段

1. 把 Rust 模型与 Android Event 字段做一一映射测试。
2. 接 SQLite 持久化。
3. 实现 WebDAV HTTPS 传输和系统钥匙串凭据存储。
4. Windows/Linux 加系统托盘与系统日历桥。
5. iOS 加 EventKit/通知桥。
6. Android 增加同协议同步适配器。
7. 建 CI：Linux/Windows 编译，macOS 构建 iOS 模拟器目标。
