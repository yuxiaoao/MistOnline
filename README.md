# MistOnline

在线时长奖励插件：每小时发金币 + 每日档位 + 每周档位，全部自动领取并只在聊天栏提示，没有菜单。

## 规则

- 每小时：+20 金币
- 每日在线 6 档：1h / 2h / 3h / 4h / 5h / 6h，最高 6 小时 +800 金币
- 每周在线 4 档：10h / 20h / 30h / 50h，最高 50 小时 +5000 金币
- 每小时奖励和档位奖励叠加
- 每日 0 点重置每日档位；每周一 0 点重置每周档位
- 所有奖励到点自动领取，聊天栏提示

## 构建

```bash
mvn package
```

产物：`target/MistOnline-1.0.1.jar`

## 安装

1. 把 jar 放进 `plugins/`
2. 重启服务器
3. 修改 `plugins/MistOnline/config.yml`

## 命令

```
/mistonline               查看自己的每日/每周/每小时进度
/mistonline info          同上
/mistonline help          查看命令帮助
/mistonline reload        重载配置（管理员）
/mistonline reset <玩家>  重置进度（管理员）
```

别名：`/monline`

权限：

- `mistonline.use`：使用查询命令（默认所有玩家）
- `mistonline.admin`：重载和重置（默认 OP）

## 配置

```yaml
timezone: Asia/Shanghai
reward-command: 'money give %player% %amount%'
save-interval-seconds: 300
data-retention-days: 90
command-owner: ''       # 留空 = 按权限；填写玩家名后只有该玩家和控制台能 reload/reset
hourly:
  minutes: 60
  coins: 20
daily:
  tiers:
    - hours: 1
      coins: 50
    # ...
weekly:
  tiers:
    - hours: 10
      coins: 500
    # ...
```

`reward-command` 需要经济插件的控制台命令；默认适配 XConomy 的 `/money give`。

档位支持 `hours`、`minutes` 或 `seconds`，方便正式配置和测试配置切换。

在线时间按真实时间累计，不按 tick 数，服务器掉 TPS 时不会少算。

## 注意

- 不要和其他在线奖励插件（QZOnlineRewards、MistOnlineReward、SpectrumOnlineReward 等）同时启用，否则会重复发奖。
- `command-owner` 只影响 `reload/reset`，普通玩家仍可查看自己的进度。
- 数据按玩家保存在 `plugins/MistOnline/players/<uuid>.yml`，只有变化的玩家会被异步写入。
- 旧的 `plugins/MistOnline/data.yml` 会在启动时迁移到 `players/`，完成后改名为 `data.yml.migrated`。
- 从旧 `plugins/MistOnline` 升级时，启动会自动复制或重命名数据目录。
- 在线时间按真实时间累计；服务器 TPS 下降不会少算。
- 单服独立计时；如果之后上 Velocity 多后端，需要额外做跨服同步。
- 测试配置切换为正式配置后，建议对测试玩家执行一次 `/mistonline reset <玩家>`。
