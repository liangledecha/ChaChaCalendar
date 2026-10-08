# ChaCha Sync v1

## 文件

默认路径：

```
/ChaChaCalendar/chacha-sync-v1.json
```

服务器只需要支持 HTTPS WebDAV 的 GET/PUT 和 ETag。

## 快照结构

```json
{
  "schema": 1,
  "updated_by": "device-id",
  "events": [
    {
      "id": "018f4f4f-...",
      "clock": { "counter": 12, "device": "device-id" },
      "deleted": false,
      "title": "结婚纪念日",
      "event_date": "2026-09-18",
      "type": "纪念日",
      "visibility_days": -1,
      "repeat_rule": "YEARLY",
      "event_time": null,
      "completed": false,
      "completed_through": null,
      "date_system": "SOLAR",
      "lunar_month": 0,
      "lunar_day": 0,
      "lunar_leap": false,
      "planned_start": null,
      "planned_end": null
    }
  ]
}
```

## 逻辑时钟

每台设备保存一个本地 counter。

发生本地修改时：

```
counter = max(local_counter, max_observed_counter) + 1
record.clock = { counter, device_id }
```

记录比较：

1. counter 大者更新；
2. counter 相同时 device_id 字典序较大者更新；
3. 完全相同则视为同一版本。

这避免依赖系统时间，防止电脑/手机时钟不准导致错误覆盖。

## 删除

删除使用 tombstone：

```json
{
  "id": "...",
  "clock": { "counter": 33, "device": "..." },
  "deleted": true
}
```

删除记录至少保留 90 天，避免长期离线设备重新上传已经删除的事项。

## WebDAV 同步流程

1. GET 文件，附带上次 ETag 的 `If-None-Match`。
2. 304：无需下载或合并。
3. 200：按事件 UUID 合并远端与本地记录。
4. 生成新快照。
5. PUT 时附带 `If-Match: <etag>`。
6. 200/204：保存新 ETag。
7. 412：远端在本轮期间发生变化；重新 GET、合并后再 PUT。
8. 单轮最多自动重试 3 次，随后交给下一次前台同步。

## 冲突原则

v1 使用记录级逻辑时钟，目标是确定性和低成本。

如果需要避免“同一事项在两台设备同时编辑不同字段时其中一方覆盖”，v2 可升级为字段级 clock；协议中的 `schema` 字段为此预留。

## 安全

- 强制 HTTPS。
- WebDAV 凭据不得写入同步 JSON。
- iOS 存 Keychain。
- Windows 存 Credential Manager。
- Linux 优先存 Secret Service/libsecret；不可用时必须明确提示用户而不是明文落盘。
- Android 存 Keystore 加密后的凭据。
