# Mineflayer + Paper 冒烟测试

这套夹具包含快速冒烟测试和一轮可重复的投稿/竞价/结算矩阵，用于确认 RookieAuctions 能在 Paper 1.21.4 上加载、Vault/Economy 能用、管理员场地命令可执行，以及 `/auction` GUI 能被 Mineflayer 打开。矩阵也覆盖沉浸模式的一页投稿流程。它使用压缩后的时长和 3 个名额，不替代生产环境的 16 件长时验收。

## 运行

在仓库根目录执行：

```powershell
mvn -q -DskipTests package
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\integration\run-smoke.ps1 -Reset
```

脚本会：

1. 从 Paper Fill API 下载并校验 Paper 1.21.4 build 232；
2. 复制当前 `target/RookieAuctions.jar`、本地 Vault 和 EssentialsX 经济插件；
3. 生成离线模式测试服与管理员 `TestAdmin`；
4. 启动 Paper，Mineflayer 连接后执行场地设置、校验、预览、启用、场次状态和 `/auction`；
5. 通过 Paper 标准输入发送 `stop`，正常关闭测试服。

首次运行会在 `integration/node_modules` 安装 Mineflayer，并在 `integration/server` 生成世界、配置和 SQLite 数据库。这两个目录已加入 `.gitignore`。`-Reset` 只会删除这个明确的测试服目录。

## 完整矩阵

需要覆盖投稿限制、撤稿补位、公开/密封出价、一口价和领奖结算时，执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\integration\run-smoke.ps1 -FullMatrix
```

`-FullMatrix` 会重建测试服，将第一场安排在当前时间约 3 分钟后，并使用 3 个名额、30 秒单件时长，方便在本地完成一轮真实拍卖。完整脚本会连接 2 个卖家和 2 个买家，并验证：一页投稿、每卖家 2 件限制、满场拒绝、撤稿补位、第一场满额时第二场仍可投稿（容量按场次隔离）、公开/密封出价、一口价、强制传送和领奖箱结算；整轮约需 3–6 分钟。价格铁砧输入建议再用真实客户端手动验收，Mineflayer 不模拟 Paper 虚拟铁砧的结果槽。

## 常用参数

```powershell
$env:MC_PORT = '25565'
$env:SMOKE_TIMEOUT_MS = '45000'
node .\integration\bot-smoke-test.js
```

如果只想手动启动服务器，可运行 `prepare-test-server.ps1` 后在 `integration/server` 执行：

```powershell
java -Xms1G -Xmx1G -jar paper-1.21.4-232.jar --nogui
```

测试服使用 `online-mode=false`，仅限本机开发测试；不要把它暴露到公网。
