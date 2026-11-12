# 茶茶日历跨端客户端

该目录用于 Windows、Linux、iOS 新客户端。

## 当前阶段

已经落地：

- Rust 跨端数据模型
- 同步逻辑时钟
- 基于 UUID 的确定性合并
- tombstone 协议设计
- Slint 三端 UI 入口骨架

尚未在本分支完成：

- SQLite 持久化
- WebDAV HTTP 传输
- 系统钥匙串
- Windows/Linux 系统日历桥
- iOS EventKit
- 与 Android 本地数据库的云同步适配

## Windows / Linux

安装 Rust 后：

```bash
cd cross-platform
cargo run
```

## iOS

iOS 构建需要 macOS、Xcode、XcodeGen 和 Rust iOS target。Slint iOS 使用 Rust 应用入口。

后续会在此目录补齐 `project.yml`、Xcode 构建脚本和签名设置。

## 设计原则

不要为了“跨平台”把 Android 改写成同一 UI 框架。共享的是模型、规则和同步协议；各平台仍可利用自己的系统通知、日历和凭据存储。
