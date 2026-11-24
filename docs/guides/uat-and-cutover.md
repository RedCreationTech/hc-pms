# UAT, 数据迁移与上线切换演练

> 适用: H20 (工程基线新增 - UAT, 数据迁移与上线切换). 本文的每条命令都在本仓真实执行过,
> 结果写在 `docs/pms/verification.md` 的 H20 章节. 本文不代替业务代表的签收, 也不代替
> 真实生产环境的验收 - 它给出的是**可重复执行的演练剧本**, 让真实 UAT 只需关注业务判断.

## 0. 角色与责任 (先明确, 否则演练无意义)

| 角色 | 承担的事 | 本项目当前状态 |
|---|---|---|
| 业务代表 (UAT 签字人) | 按完整订单闭环判断系统是否可用, 签字/拒收/条件接受 | 待用户方指派 |
| 系统负责人 | 切换窗口, 执行切换与回退, 对 RPO/RTO 数字负责 | 待用户方指派 |
| 数据责任人 | 历史数据范围与对账口径, 确认对账结果可接受 | 待用户方指派 |
| 培训与支持责任 | 用户培训, 上线后 N 天驻场/响应时限 | 待用户方指派 |

**H20 不能仅靠代码完成**: 上表四个角色必须由用户方指派并对结果签字. 本文能保证的是
"演练可重复, 证据可复核", 不能替用户方做出"可以上线"的业务决定.

## 1. 脱敏演示数据集

```bash
node scripts/seed-demo-data.mjs [--base http://localhost:3000]
```

- 生成 `DEMO-SEED-01..06` 六个项目, 覆盖 **草稿 / 立项 / 计划 / 执行 / 收尾 / 关闭**
  六个阶段各一个, 是完整订单闭环 UAT 的数据底座.
- **幂等**: 按 `project_no` 找到项目后按状态补齐, 中断后重跑可续建, 不会重复建项目.
- 验证独立审批: 管理员提交, `qa_seed_2026` (演示评审员) 决定, 满足提交/审批职责分离.
- 实测 (2026-11-24): 全新库跑完约 **493 秒**, 产出 6 个项目, 状态分别为
  `draft / initiated / planning / execution / closing / closed`.

**脱敏要求**: 该脚本只使用虚构的中文项目名与内部客户名, 不含真实客户/合同/联系人与
金额. 上生产前必须确认演示数据集**不与真实数据同库** (见 5.3 的分库检查).

## 2. 完整订单闭环 UAT 剧本

按项目阶段走查, 每一步给出"看什么 / 通过标准". 走查对象是 `DEMO-SEED-01` (草稿)
到 `DEMO-SEED-06` (已关闭), 以及自己新建的一个项目.

### 2.1 主链 (必过)

| # | 步骤 | 看什么 | 通过标准 |
|---|---|---|---|
| 1 | 登录 + 动态菜单 | 左侧菜单 | 菜单按当前用户角色动态出现, 未授权菜单不出现 (AGENTS.md 7.4) |
| 2 | 新建项目 + 章程 | 项目概况, 章程页 | 章程需独立批准, 未批准不能进下一阶段 |
| 3 | 计划与基线 | 计划页, 基线 | 任务树可建, 基线独立批准后才生效 |
| 4 | 需求与追踪 | 需求页, 追踪矩阵 | 需求版本可升, 追踪矩阵能回链 |
| 5 | BOM 与齐套 | 交付页 | 齐套率随物料补齐变化, 缺料有提示 |
| 6 | Gate 检查 | 治理页 | 未满足项阻断, 阻断原因可读 |
| 7 | 工时与成本 | 财务页 | 批准工时生成人工成本, 费用合计与明细一致 |
| 8 | 交付签收 | 交付页 | 签收不早于发运, 前序试验新版本使旧下游资格失效 |
| 9 | 收尾 | 结项与移交 | 五类就绪度检查, 有阻塞项不能关闭 |
| 10 | 归档与重开 | 收尾页 | 归档只读, 重开需批准且保留原关闭记录 |

### 2.2 审批与职责分离 (必过)

- 提交人不能审批自己的提交 (自审必须 403).
- 审批链按配置顺序流转, 非当前节点的人批准必须被拒.
- 财务/质量节点只能由具备对应权限的角色操作.

### 2.3 边界与错误提示 (必过)

- 非法输入返回 400 且消息可读 (不是 500 堆栈).
- 无权限返回 403, 不存在返回 404, 状态冲突返回 409.
- 封账期间的写操作返回 409 且不产生副作用.

### 2.4 UAT 记录表 (由业务代表填写)

| 用例 | 结果 (通过/不通过) | 证据 (截图/接口响应) | 备注 |
|---|---|---|---|
| 2.1-1 登录与动态菜单 | | | |
| ... | | | |

> 不通过项必须记录**复现步骤 + 期望 + 实际**, 否则无法进入缺陷跟踪.

## 3. 历史数据迁移与对账

### 3.1 三个存储都要迁

1. 业务库 (`JDBC_URL`): SQLite 或 MySQL
2. Flowable 引擎库 (`FLOWABLE_JDBC_URL`): H2 文件或 MySQL
3. PMS 证据文件 (`PMS_FILE_DIR`, 默认 `data/pms-files`): 内容寻址, 文件名即 SHA256

业务库与引擎库通过 business-key 关联, **必须同时点迁移**, 只迁一个会出现"业务记录存在但流程
实例丢失".

### 3.2 切换步骤 (SQLite -> MySQL)

```bash
# 1. 停服 (必须: SQLite/H2 运行中复制会得到不一致快照)
./stop_dev.sh

# 2. 备份源库 (三个存储一起)
./scripts/backup-restore.sh backup --out backups/pre-cutover

# 3. 目标库建空库
mysql -h127.0.0.1 -P3308 -uroot -p -e \
  "DROP DATABASE IF EXISTS ruoyi_cutover; CREATE DATABASE ruoyi_cutover CHARACTER SET utf8mb4;"

# 4. 在目标库跑真实迁移 (会建 schema + 种子数据)
JDBC_URL="jdbc:mysql://root:password@127.0.0.1:3308/ruoyi_cutover?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
MIGRATION_DIR=migrations clojure -M:dev -m com.ruoyi.core
# 确认日志出现 "Ending migrations" 后停掉

# 5. 搬业务数据
node scripts/migration-copy.mjs \
  --source sqlite:rouyi.db \
  --target "mysql://root:password@127.0.0.1:3308/ruoyi_cutover"

# 6. 对账 (这一步才是切换是否成功的判据)
node scripts/migration-reconcile.mjs \
  --source sqlite:rouyi.db \
  --target "mysql://root:password@127.0.0.1:3308/ruoyi_cutover" \
  --json reports/h20a/reconcile.json

# 7. 用目标库启动并抽查
JDBC_URL="..." MIGRATION_DIR=migrations clojure -M:dev -m com.ruoyi.core
```

### 3.3 对账脚本及口径

`scripts/migration-reconcile.mjs` 三层对账 (实测 224 项):

| 层 | 内容 | 为什么需要 |
|---|---|---|
| 结构 | 表清单, 每表列集合, `schema_migrations` 已应用集合 | 漏表/漏列/漏迁移是迁移最常见的静默失败 |
| 数据 | 逐表行数 (`sys_online` 等运行态表只记录不判定) | 行数是最直接的完整度信号 |
| 语义 | 项目状态分布, 审批状态与水印, 费用版本, 成员数, BPM 实例状态, 菜单权限行数等 9 组聚合 | **行数相同不代表内容相同**, 聚合能抓出"行数没变但值被截断/归一" |

**必须先用 `--self` 自检**: `node scripts/migration-reconcile.mjs --source sqlite:rouyi.db --self`.
自检把源和目标都指向同一库, 任何非零差异都是脚本自身的 bug 而不是数据问题. 本轮两个
脚本缺陷都是靠 `--self` 抓出来的 (见 3.5).

### 3.4 回退

```bash
./stop_dev.sh
# 方式一: 用备份整体回退 (含引擎库与证据文件)
./scripts/backup-restore.sh restore backups/pre-cutover
# 方式二: 只把 JDBC_URL 改回原库 (切换后目标库的新数据会留在目标库, 不自动回流)
```

**切换窗口内产生的数据**: 一旦在 MySQL 侧写入了真实数据, 改回 `JDBC_URL` 并不会把这些数据
带回来. 所以切换应安排在**无业务写入的窗口**, 或者切换后先只读验证, 确认后再开放写入.

### 3.5 已踩到的坑 (都会让对账结果说假话)

1. **MySQL 默认 `sql_mode` 下 `||` 是逻辑或不是拼接** (缺 `PIPES_AS_CONCAT`).
   `'instance=' || status` 在 MySQL 上被算成 `0`/`1`, 脚本**不报错**, 只是静默给出错误键.
   因此对账 SQL 必须按方言写两份: SQLite 用 `||`, MySQL 用 `CONCAT()`.
2. **`sqlite3 -json` 会把纯数字 id 解析成 JS number**, 而 `mysql -N` 只给文本.
   不统一转字符串, `Set.has` 永远为 false, 会造出"目标缺失全部迁移"这种假差异.
3. **迁移集合真实差异**: `202611200001-repair-current-text-timestamps` 原本只存在于
   `migrations-sqlite/` (它是 SQLite 历史缺陷的数据修复), MySQL 目录没有同名文件.
   已补 MySQL 占位迁移对齐版本号, 保证两侧迁移集合可比对.

## 4. 备份与恢复演练 (RPO / RTO)

```bash
./scripts/backup-restore.sh backup  --out backups/<时间戳>
./scripts/backup-restore.sh verify  backups/<时间戳>
./scripts/backup-restore.sh restore backups/<时间戳>
./scripts/backup-restore.sh list
```

- 三个存储一起备份, 生成 `MANIFEST.txt` (不含连接串) 与 `SHA256SUMS`.
- `restore` 先 `verify`, 再把当前数据另存到 `backups/pre-restore-*` (恢复也能回退).
- **服务运行时脚本会拒绝备份/恢复** (退出码 2): SQLite 在线复制可能抓到不一致快照,
  H2 文件库持有文件锁.

**实测 (2026-11-24, 本机)**

| 项 | 结果 |
|---|---|
| 备份耗时 | 1 秒 (业务库 2.7M + H2 53M + 证据 8K) |
| 恢复耗时 | 1 秒 |
| 恢复后启动到 healthy | 22 秒 |
| 数据完整性 | 破坏前 197 用户/491 实例 -> 破坏后 198/0 -> 恢复后 197/491, 与基线逐项一致 |
| 引擎库可用性 | 恢复后 BPM 待办查询 total=1321, 模型 464, 实例 491 (不只是文件对) |

**RPO / RTO 口径**: 备份是冷备 (`./stop_dev.sh` 后复制), 所以 **RPO = 上次备份到故障发生
之间的写入**. 若要求 RPO 小于一天, 必须增加备份频率或改用 MySQL 的 `--single-transaction`
热备 (脚本已支持 MySQL 侧). 上表的 22 秒是**本地单机**数字, 生产环境受 JVM 预热, 磁盘, 
容器启动影响, 必须以目标环境的演练结果为准.

## 5. 上线切换检查清单

### 5.1 切换前

- [ ] `./scripts/backup-restore.sh backup` 成功, `verify` 通过
- [ ] 目标库 schema 已用 `MIGRATION_DIR=migrations` 真实跑完, 日志有 "Ending migrations"
- [ ] `migration-reconcile.mjs` 对账 **0 差异** (不是"差异可接受")
- [ ] `JWT_SECRET` / `COOKIE_SECRET` 已换成随机值 (默认值在公开源码里)
- [ ] nREPL 仍绑定 `127.0.0.1`, 未对外暴露
- [ ] 切换窗口已确认无业务写入

### 5.2 切换后

- [ ] `GET /api/health` 返回 `app.status = "up"`
- [ ] 登录成功, 动态菜单正常
- [ ] 抽查: BPM 实例数, 待办数, 项目数, 用户数与对账结果一致
- [ ] **真实写入一条** (新建项目/发起流程) 并读回, 证明写路径可用, 不只是只读
- [ ] 查看日志无 `ERROR`

### 5.3 分库检查 (演示数据与真实数据隔离)

- [ ] 演示项目 `DEMO-SEED-*` 不出现在生产库的列表中 (或在 UAT 后删除)
- [ ] 演示账号 `qa_seed_2026` 在生产库中已停用

## 6. 培训与支持责任

| 项 | 内容 | 责任方 |
|---|---|---|
| 培训对象 | 项目经理, 审批人, 财务, 质量, 只读干系人 | 系统负责人组织, 业务代表确定名单 |
| 培训内容 | 6 个阶段主链 + 审批 + 财务 + 收尾; 演示数据集走查 | 系统负责人 |
| 上线后支持 | 首 N 天的响应时限, 升级路径, 问题登记入口 | 待用户方指派 |
| 支持边界 | 外部接口 (ERP/MES/CRM/OA/采购) 需合同与字段定义, 不在本地闭环范围 | 待合同 |

## 7. 本轮未执行 (如实记录)

- **真实生产目标环境的切换**: 本机演练的 22 秒启动, 1 秒备份不能代表生产; 容量, 
  RPO/RTO 必须以目标环境实测为准.
- **真实主机换 jar 与 systemd 回滚**: `bb deploy` / `deploy:rollback` 需要可 root 登录的
  部署主机. 本机 `gpu` 别名可连, 但 `hc-pms` 服务当前为 `inactive` (从未部署),
  因此真实换 jar 与回滚未在本轮演练.
- **业务 UAT 签收**: 需要业务代表按下表实际走查并签字, 代码与脚本不能代替.
- **培训课件更新**: `docs/training/` 仍基于 BPM 之前 (6 月) 的代码, 未随 PMS 更新.
- **浏览器兼容矩阵**: 仅 Chromium 实测, Firefox / WebKit 未跑 (G18 范围).
- **MySQL 主从 / 备份存储异地**: 未做.