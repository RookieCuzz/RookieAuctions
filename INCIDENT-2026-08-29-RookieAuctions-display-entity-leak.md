# RookieAuctions 展示实体泄漏事故报告

## 1. 事故摘要

| 项目 | 内容 |
|---|---|
| 事故时间 | 2026-08-29 15:28:45（玩家登录）至 15:31 左右（服务失去可用性） |
| 影响环境 | `/home/cuzz/towncraft/server-dev/spawn`，Paper 1.21.4 |
| 影响世界 | `spawn` |
| 严重度 | 高：服务器主线程失去响应，玩家无法正常进入；世界实体数据被大量污染 |
| 根因插件 | RookieAuctions 沉浸式拍卖场景展示控制器 |
| 根因置信度 | 高；日志、世界 NBT、线上配置、部署 JAR 字节码和 Git 历史相互印证 |
| 当前状态 | 已修复并恢复在线；2026-08-29 16:25:25 完成最终启动，公网端口 `50011` 可连接 |

**结论：** RookieAuctions 把本应短生命周期的场景展示实体设置为持久化实体，却只用三个内存中的 Bukkit `Entity` 引用管理它们。场地区块卸载后，旧引用失效；清理方法因 `isValid() == false` 不再移除旧实体，随后又生成一组新的持久化实体。每 tick 的旋转任务会不断重试这一流程，而原有孤儿清理只能看见已加载实体，最终在单个实体区块中累积了 **331,509 个 RookieAuctions Display 实体**。玩家登录后该实体区块被加载，Paper 主线程开始逐个反序列化 33 万个实体，导致内存压力、Swap 抖动和 watchdog 长时间无响应。现已通过非持久化展示、区块票据、实体加载时收敛、限速恢复和离线 NBT 外科清理闭合该故障链。

这不是单个 TextDisplay 文本损坏，也不是 RookieBrewery、FAWE 或 Paper 自身生成了这些实体。

## 2. 用户影响

- 玩家 `test2` 登录后约 8 秒出现高内存告警，约 17 秒后服务器主线程连续被 watchdog 判定为无响应。
- 服务表现为“进去一会就关服”，实际首先发生的是主线程被实体区块加载长期占用；后续停止流程又留下了 Java 僵尸进程和过期 PID 文件。
- 异常实体区块原始 NBT 达 319,686,953 字节。仅压缩文件较小并不代表影响小；加载时必须展开并创建 331,509 个 NMS/Bukkit 实体对象。
- 未发现玩家经济、拍卖数据库或物品数据丢失证据。已确认并修复的持久化污染位于 `spawn` 世界实体区块 `(9,36)`；本次没有对该世界所有实体 Region 做全量 PDC 扫描，后续仍建议做一次离线全盘 PDC 审计。

## 3. 关键时间线

| 时间（Asia/Shanghai） | 事件 |
|---|---|
| 2026-08-26 16:46:42 | Git 提交 `df85f9b` 引入沉浸式拍卖展示；同时引入 `setPersistent(true)`、失效引用替换和仅扫描已加载实体的清理设计。 |
| 2026-08-27 23:28:54 | Git 提交 `ea28102` 把展示旋转任务由每 1 秒执行改为每 1 tick 执行，失效状态下的重建检查频率最高放大约 20 倍。 |
| 2026-08-28 11:19 | 线上 `RookieAuctions.jar` 文件时间；部署字节码已包含每 tick 重试和第三个 `item-label` 实体。 |
| 2026-08-28 11:23:32 | Git 提交 `43cf2e9` 正式记录 `item-label`，每次重建从 2 个实体增加到 3 个。线上 JAR 时间早于提交时间，说明它来自提交前的同一工作树，而非可追溯的正式版本。 |
| 2026-08-28 16:18:18 | 异常外部实体文件 `c.9.36.mcc` 最后修改；说明污染最晚此时已持久化到世界。 |
| 2026-08-29 15:25 | Paper 启动，部署插件版本仍为 `0.0.0-SNAPSHOT`。 |
| 2026-08-29 15:28:45 | `test2` 在 `spawn` 世界登录，位置 `(-22.8690, 47.0, 544.6337)`。 |
| 2026-08-29 15:28:53 / 15:28:56 | FAWE 的通知线程两次报告系统处于高内存使用状态；FAWE 是告警来源，不是实体来源。 |
| 2026-08-29 15:29:02 | Paper watchdog 报告主线程 10 秒无响应，栈停在 `Display$ItemDisplay` 构造和实体区块读取。 |
| 2026-08-29 15:29:07 | 15 秒无响应，栈停在 `Display$TextDisplay.readAdditionalSaveData`。 |
| 2026-08-29 15:29:12 | 21 秒无响应，栈停在 TextDisplay 组件 JSON 反序列化；调用链仍是实体区块加载。 |
| 2026-08-29 15:31:44 起 | 创建只读事故备份，随后补齐异常外部块、日志、部署 JAR、配置和启动脚本并核对 SHA-256。 |
| 2026-08-29 16:15:45 | 原子替换清洗后的实体块：删除 331,509 个 RookieAuctions 实体，保留 1 个无关实体；同时部署 RookieAuctions `0.0.1`。 |
| 2026-08-29 16:18:58 | 第一次烟雾启动；Paper 于 16:20:04 完成加载，RookieAuctions 正常启用。 |
| 2026-08-29 16:20:57 | 优雅停服并保存世界；Paper 将修复后的实体块从外置 `.mcc` 自动收回到正常 Region 内联存储。 |
| 2026-08-29 16:24:22 | 最终启动；16:25:25 进入 `Done`，16:25:27 拍卖 NPC/模型反馈初始化完成。 |

## 4. 取证结果

### 4.1 异常区块

- 世界：`spawn`，UUID `43fc745b-f230-48ed-b513-e4d19e8aab26`
- 实体区块：`(9,36)`
- 实体 Region：`spawn/entities/r.0.1.mca`
- Region 中该条目的压缩类型字节为 `0x82`，表示 zlib 压缩且数据存放于外部文件。
- 外部文件：`spawn/entities/c.9.36.mcc`
- 压缩大小：8,492,050 字节
- 解压后 NBT：319,686,953 字节
- 压缩比：约 37.65 倍

线上配置的物品展示点为 `(155.39198005624172, 54.0, 580.4776926676427)`，其区块坐标正是 `(9,36)`；信息展示点与物品标签也位于同一区块。

### 4.2 实体归属和数量

对外部 NBT 解压后进行只读字节级计数，结果如下：

| 标记或实体类型 | 数量 |
|---|---:|
| `rookieauctions:immersive-venue-display` | 331,509 |
| 角色 `item` | 110,503 |
| 角色 `info` | 110,503 |
| 角色 `item-label` | 110,503 |
| `minecraft:item_display` | 110,503 |
| `minecraft:text_display` | 221,006 |

三种角色数量完全相等，且 `110,503 × 3 = 331,509`。这不是模糊相关性，而是 RookieAuctions 的 `ensureDisplays()` 被重复执行 **110,503 轮**后留下的精确实体形状：每轮 1 个 ItemDisplay 加 2 个 TextDisplay。

若按新代码每 tick 最多生成一组计算，110,503 轮理论上约需 92 分 5 秒；若仍按旧版每秒一次则需约 30 小时 42 分。该数量级与 8 月 27 日把任务改为每 tick 后的快速放大一致。

异常块中另有 1 个 `rookieairdrops:airdrop_type` 标记，因此恢复时不能粗暴删除整个区块或整个外部文件，必须按 RookieAuctions 专属 PDC key 做外科式过滤。

### 4.3 watchdog 证据

三个相隔 5 秒的主线程采样位置不同，但都位于：

`EntityType.loadEntityRecursive → ChunkEntitySlices.readEntities → NewChunkHolder.loadInEntityChunk → ChunkFullTask.run`

采样依次落在 ItemDisplay 构造、TextDisplay NBT 读取、TextDisplay 组件解码。这表明主线程在缓慢推进一个极大的实体列表，而不是永久卡在一条畸形 JSON 上。

### 4.4 部署二进制确认

部署文件：`plugins/RookieAuctions.jar`

- 插件声明版本：`0.0.0-SNAPSHOT`
- SHA-256：`502c76a2e5ea467009733fd5ccc1cf0ef2e528d0600973ec597ea7221e5a163e`
- `javap` 反编译确认线上字节码包含：
  - `runSyncRepeatingTickTask(..., 1L, 1L)`；
  - 三种角色 `item`、`info`、`item-label`；
  - `Entity.setPersistent(true)`；
  - `usableAt()` 的 `isValid()` 判断；
  - `remove()` 仅在 `isValid()` 为 true 时执行；
  - 仅通过 `World.getEntities()` 扫描孤儿。

因此根因不是仅存在于本地源码、而线上没有部署的代码路径。

## 5. 根因分析

### 5.1 直接根因

`src/main/java/me/elian/ezauctions/immersive/VenueDisplayController.java` 中的实体生命周期策略不闭合：

1. `configureBase()` 在第 347–353 行把所有三个展示实体设为持久化：`entity.setPersistent(true)`。
2. `usableAt()` 在第 512–515 行把区块卸载后的失效包装对象判定为不可用。
3. `ensureDisplays()` 在第 281–310 行遇到不可用对象时先调用 `remove(old)`，再立即 `world.spawn(...)` 新对象。
4. `remove()` 在第 553–557 行只处理 `entity.isValid()` 的对象。旧引用一旦失效，清理成为静默 no-op；但旧实体因为被设置为 persistent，已经或将被写入实体区块。
5. `rotateNow()` 在第 371–383 行检测到引用失效便再次调用 `renderNow()`；该方法由第 88 行的每 tick 任务驱动。

完整故障链为：

`场地区块卸载/实体引用失效 → rotateNow 每 tick 检测 → renderNow/ensureDisplays → remove 因 invalid 不执行 → 生成 3 个新 persistent Display → 再次卸载并落盘 → 循环 → 下次加载一次性反序列化 331,509 个实体`

### 5.2 清理保护为何失效

- `cleanupOrphansNow()`（第 529–541 行）遍历 `world.getEntities()`，只能看见当前已经加载的实体，无法清除未加载区块中的持久化孤儿。
- `shutdown()` 和 `removeOwnedDisplaysNow()` 只持有并尝试删除当前三个引用，无法覆盖历史生成的实体。
- `onChunkLoad()`（第 174–188 行）试图在 `ChunkLoadEvent` 中删除孤儿，但没有在实体真正可用后执行一次强一致的“按 PDC 角色收敛为唯一实体”。Paper 1.21.4 的区块和实体加载分阶段进行；生产结果已经证明该事件路径没有清掉落盘实体。具体事件时序应通过 Paper 集成测试确认，不能继续把正确性寄托在这一监听器上。
- 生成前没有查询目标区块中已存在的 PDC 实体，也没有数量上限、退避、熔断或监控。

### 5.3 放大因素

- `ea28102` 将轮询由每秒改为每 tick，理论重建频率提高约 20 倍。
- `43cf2e9` 增加 `item-label`，每轮泄漏由 2 个实体增至 3 个。
- 服务器 JVM 为 `-Xms2G -Xmx4G`。事故后主机 21 GiB 内存中约 14 GiB 已用，8 GiB Swap 中约 7.3 GiB 已用；同机还有其他 Java 服务。它加重了停顿，但不是实体被生成的根因。
- 插件版本固定为 `0.0.0-SNAPSHOT`，线上 JAR 时间又早于对应 Git 提交，降低了构建可追溯性。
- 启动脚本的 `is_running()` 只使用 `kill -0`。PID 31495 已是 `Z` 状态的 Java 僵尸进程，仍会被判断为在线，造成状态误报和重启困难。

## 6. 已排除项

- **RookieBrewery：** 异常块中 `rookiebrewery` 标记为 0。另一个正常实体块中的 3 个 RookieBrewery TextDisplay 文本 JSON 合法、数量正常。
- **单条 TextDisplay JSON 损坏：** watchdog 的执行位置在多个实体之间持续变化；证据支持“数量巨大”，不支持“单条文本死循环”。
- **FAWE：** 仅由通知线程报告高内存使用；异常实体 PDC 明确指向 RookieAuctions。
- **Paper：** Paper 正在按世界数据加载实体；导致不可接受工作量的是插件持久化写入的 331,509 个实体。
- **玩家数据：** 玩家登录是异常区块进入加载链的触发或加速条件，不是实体的创建来源。

## 7. 测试缺口

事故发生时，`src/test/java/me/elian/ezauctions/immersive/ImmersiveVenueTest.java` 的 8 项测试只覆盖文本格式、预览窗口和旋转周期，没有覆盖：

- 区块卸载与重新加载；
- `Entity.isValid() == false` 后的行为；
- 插件重载、关闭和崩溃恢复；
- PDC 实体在磁盘 NBT 中的数量；
- 重复 `update()` / `rotateNow()` 的幂等性；
- `ChunkLoadEvent` 与实体加载时序；
- 展示实体数量上限。

修复后增加了“失效展示恢复必须限速”的 10,000 ticks 回归测试，并确认恢复门每 20 ticks 最多触发一次、健康 tick 后可复位。验证结果：

- `mvn -Dtest=ImmersiveVenueTest test`：9 项通过。
- 排除 3 个受本机 Java loopback 限制的 MockBukkit 初始化用例后，其余 80 项测试全部通过。
- 被排除的 `RookieAuctionsTest`、`AuctionGuiAnvilTest`、`AuctionBroadcastTest` 均因 `Unable to establish loopback connection` 报环境错误，与本次改动无关。
- 离线 NBT 修复工具自测通过；最终 `mvn -DskipTests package` 构建成功。

## 8. 整改建议

### P0：本次恢复执行项（已完成）

1. 部署修复版 RookieAuctions 后才重新启动，避免只清世界而立即复发。
2. 离线解析 `c.9.36.mcc`，只删除 PDC key 为 `rookieauctions:immersive-venue-display` 的 331,509 个实体，保留 `rookieairdrops` 等无关实体。
3. 使用临时文件、完整 NBT round-trip 校验、原子替换和 SHA-256 记录；保留可直接回滚的只读备份。
4. 完成一次启动—优雅保存—离线复检闭环，确认 `(9,36)` 的 RookieAuctions 标记数为 0 后再最终启动。
5. 修正 `start.sh` 的在线检测：读取 `/proc/<pid>/stat` 并拒绝 `Z` 状态；所有停止路径统一清理主 PID 与 watchdog PID。

### P1：代码根治

1. 场景 Display 默认使用 `setPersistent(false)`，将其视为可重建的临时投影。
2. 明确区块所有权，二选一：
   - 展示激活期间持有插件 chunk ticket，关闭时释放；或
   - 仅在目标区块已加载且有观看需求时渲染，绝不通过每 tick 的 `world.spawn()` 隐式加载区块。
3. 在实体加载完成事件中按 PDC role 做幂等 reconciliation：每个角色最多保留一个，能接管则接管，重复则删除；不要只依赖三个旧 Java 引用。
4. 把“引用失效后的重建”从每 tick 热路径移出，增加有限重试、退避和熔断。同一场景若检测到超过 3 个自有实体，应停止生成并发出高优先级告警。
5. `shutdown()`、reload、world unload 和配置坐标变化均统一走同一个幂等生命周期状态机。
6. 发布 JAR 写入真实版本、Git commit 和构建时间，禁止生产/共享环境继续使用无来源的 `0.0.0-SNAPSHOT`。

### P1：回归与验收

- 连续 10,000 ticks、100 次区块卸载/加载后，任一时刻自有展示实体不超过 3 个。
- 插件 disable/enable、服务器正常重启和模拟崩溃后，磁盘中不出现重复 PDC 实体。
- 验证实体先于/后于 `ChunkLoadEvent` 可见的两种时序，优先使用 Paper 的实体加载事件完成收敛。
- 无玩家时场地区块不应被每 tick 隐式加载；若采用 chunk ticket，应能在生命周期结束时可靠释放。
- 人工制造 invalid 引用后，插件不得每 tick 生成新实体。
- 启动脚本对运行、停止、僵尸和过期 PID 四种状态给出正确结果。

## 9. 当前恢复状态与证据保全

服务器已恢复在线。最终 Java 进程由修正后的 `start.sh` 管理，Paper 已进入 `Done`，监听 `*:50011`；从外部对 `43.248.184.250:50011` 的 TCP 连接测试成功。原 Java PID 31495 的僵尸状态和过期主 PID/watchdog 文件已清理，启动脚本现在会读取 `/proc/<pid>/stat` 并拒绝把 `Z` 状态判断为在线。

证据目录：

`/home/cuzz/towncraft/backup/spawn-textdisplay-fix-20260829-153144`

部署前快照：

`/home/cuzz/towncraft/backup/rookieauctions-entity-fix-20260829-1615`

主要 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `c.9.36.mcc` | `1782ba83b3f3a9cb0e4efd2e97d578b53d85bc8765adc43be2acb5f4f4d71062` |
| `r.0.1.mca` | `e4506ea9a45813aad1da3adb39d4da837967663b20708ada5a779f70569663de` |
| `r.-1.1.mca` | `046165f243122eaa3d425dedf5ae42e06449975de4f945ce1b3cd8df356d1ba9` |
| `latest.log` | `755c6f13bd6406df493774c2627108e4ed5bbf753debace82d5178a6ff320a54` |
| `RookieAuctions.jar` | `502c76a2e5ea467009733fd5ccc1cf0ef2e528d0600973ec597ea7221e5a163e` |
| `RookieAuctions-config.yml` | `6734ba085f124169ed7ff58026ca8ea2c474c72acfd911695874bae3510ba198` |
| `start.sh` | `b5218aa87014df11c89c8286b5956387fbc3456d97a3ef947a21f4f2a232a14d` |
| 玩家数据 | `de2229b5011417e479e973f5bab039d9cfd58e15999e535c0430ff0c97cf9037` |

恢复产物 SHA-256：

| 文件 | SHA-256 |
|---|---|
| 清洗后的 `c.9.36.mcc` 候选 | `a630425eb6c3105cbf825943a2e1eb5038b2fd8f70b4c1c02e820231d4bfc5d8` |
| 部署后的 `RookieAuctions.jar` | `e5cf01e69a2927a6065edcac8c41acc9071f75154beaa4c395448ba947635c7f` |
| 部署后的 `start.sh` | `e5c11dceacd98592feab54dcf5bddd9d74386903dce65f0fb7db8986338317dd` |

## 10. 最终判定

- **根本原因：** RookieAuctions 的 persistent Display 与失效引用重建策略组合形成实体泄漏。
- **故障触发：** 异常实体区块被请求加载，主线程必须创建并反序列化 331,509 个展示实体。
- **主要放大器：** 每 tick 重建检查、第三个标签实体、缺少实体上限和实体加载后的强一致清理。
- **恢复动作：** 同时完成代码生命周期修复与按 PDC 的离线实体清洗；没有整块删除，保留了 1 个其他插件实体。
- **验收结果：** 烟雾启动后优雅保存，区块 `(9,36)` 为单扇区内联数据，实体总数 1、RookieAuctions 标记数 0；最终重启成功并对外监听。
- **残余事项：** 建议后续在维护窗口对 `spawn` 全部实体 Region 做一次离线 PDC 审计，并补充真实 Paper 区块卸载/加载集成测试。

## 11. 修复实现与部署验收

### 11.1 代码修复

- 展示实体改为 `setPersistent(false)`，不再写入世界持久化数据。
- 展示激活期间对目标区块持有插件 chunk ticket，坐标变化、清理、世界卸载和插件关闭时释放。
- 使用实体加载事件对插件 PDC 实体做角色级收敛，删除非当前规范实例和历史重复实例。
- 展示引用不完整或失效时采用 20 ticks 恢复门；不再由每 tick 热路径无限重建。
- 删除方法对所有非空引用调用 `remove()`，不再因 Bukkit 包装对象 `isValid() == false` 静默跳过。
- 插件版本从不可追溯的 `0.0.0-SNAPSHOT` 提升为 `0.0.1`。

### 11.2 世界修复

流式 NBT 修复工具解析了 319,686,953 字节原始 NBT，得到：

| 指标 | 数量 |
|---|---:|
| 原始实体 | 331,510 |
| 删除的 RookieAuctions 实体 | 331,509 |
| 保留的无关实体 | 1 |

工具在原子替换前完成重压缩、解压 round-trip、原始数据 SHA-256 和标记清零验证。第一次启动并优雅保存后，Paper 删除了不再需要的外置 `c.9.36.mcc`，将该区块以内联形式写回 `r.0.1.mca`。针对区块 `(9,36)` 的直接解析结果为：`entities=1`、`marker_entities=0`、`storage=inline`、`sector_count=1`。

### 11.3 运行验收

- 第一次启动：`Done (64.933s)`；RookieAuctions `0.0.1` 正常启用。
- 优雅停服：Paper 完成所有世界和实体区块保存，没有强制终止。
- 保存后复检：没有 RookieAuctions 持久化 Display 回写。
- 最终启动：`Done (62.344s)`；拍卖 NPC `auctioneer`、模型 `auction`、动画 `deal` 初始化成功。
- 服务状态：Java 进程非僵尸，Minecraft 端口 `50011` 正常监听且公网 TCP 可达。
