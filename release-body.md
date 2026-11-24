MistOnline 1.0.3

性能优化：在线时长结算从「每秒一次」放宽到「每 5 秒一次」，并缓存日期/周 key。

## 变化

- 新增配置 `tick-seconds`（默认 5，范围 1~300）
- 结算仍然按时间戳差累计，放宽间隔**不会少算**在线时长
- 日期与周 key 缓存 60 秒，跨天/跨周最多晚 1 分钟重置
- 命令、权限、数据文件（`players/<uuid>.yml`）格式全部不变，直接替换 jar 即可

## 实测依据

同一份 spark 报告（Leaf 26.3，1 人在线，71 秒 / 1339 tick）：

| | 累计 | 占服务器线程实际运算 | 每 tick |
| --- | --- | --- | --- |
| 1.0.2（每秒结算） | 112 ms | 0.95% | ~84 µs |
| 1.0.3（5 秒 + 缓存） | 预计 6~8 ms | 约 0.07% | ~6 µs |

其中 60 ms 花在 `weekKey()` 的 `LocalDate.get(WeekFields...)`、20 ms 花在 `LocalDate.now()`，
这两部分在新版里一天只算一次。

## 构建

无 Maven 环境时用附带脚本（javac + paper-api）：

```powershell
powershell -NoProfile -File build-mistonline.ps1
```

产物：`target/MistOnline-1.0.3.jar`

## 升级验证建议

1. 上线后挂机 3 分钟，`/mistonline` 看每日累计是否 ≈ 180 秒（误差 < 10 秒）
2. 时薪到账时间点会落在 5 秒刻度上（原来整秒），属正常现象
3. 想确认收益，用同一份 spark 口径再采一次，对比 MistOnline 的毫秒数
