# HC-PMS 完整本地业务闭环验证记录

日期: 2026-09-22. 本次范围为新增PMS计划,治理,交付,财务,接口运维及正式收尾工作台,并保留原项目中心回归. 这是当前实现的工程验证,不表示106项PPT/标准补全功能全部完成或生产业务签收. 原Batch 1结果保留在[历史记录](verification-batch1.md).

## 已完成验证

| 验证 | 实际结果 | 范围 |
|---|---|---|
| 本地全新SQLite后端 | 62 tests / 423 assertions,0失败/错误 | 7个测试命名空间,真实迁移/身份/事务/业务服务,包含实际本地HTTP回执 |
| CI SQLite / MySQL 8 | SQLite 62 tests / 423 assertions; MySQL 62 tests / 392 assertions,两库0失败/错误 | 修复提交cfe4b15,独立空库全量PMS套件,包括真实并发 |
| 本地Chrome真实浏览器 | 8 passed,2.7分钟,0 flaky/跳过/pageerrors | 原2例+工作台5例+受控延迟刷新1例,主操作者与独立审核者分别登录 |
| Linux CI Chrome浏览器 | 8 passed,5.1分钟,均首次通过 | 8次执行,无重试/失败/flaky;包含真实完整交付/关闭/重开和受控延迟刷新 |
| 生产前端 | 4026 files / 19 compiled / 0 warnings,22.94s | shadow-cljs release app,与当前工作台完整源代码一致 |
| 后端发布包 | 构建通过,约99MiB | clojure -T:build all,生产静态文件已打包 |
| 独立发布包启动/关闭 | 通过 | 隔离业务库与Flowable库,health=up,项目页HTTP200,未登录API HTTP401,实际登录及项目创建/读取通过,引擎与HTTP正常关闭 |
| SQLite新增迁移往返 | 34项断言全部通过 | 隔离库全量up -> 009..004逐项down -> 再up,启用外键,保留上游和001-003数据 |
| 代码规模与差异 | 通过 | 69个PMS源码文件,1207个函数形式,33个SQL/迁移文件;最大namespace266行/函数39行/SQL131行 |

本轮双库结果来自[cfe4b15的CI运行](https://github.com/RedCreationTech/hc-pms/actions/runs/35682432061),不是旧Batch 1结果. 该运行SQLite/MySQL/web三项全部success. Linux浏览器8个用例首次通过,共5.1分钟,无重试或flaky;本地生产构建的同组8例2.7分钟通过.

分项后端结果,并发套件最后一行列出两库差异:

| 模块 | 测试/断言 |
|---|---|
| 原项目中心 | 10 / 75 |
| 计划排程/资源/基线 | 11 / 84 |
| 治理/风险复审/问题重开 | 10 / 92 |
| 交付执行 | 8 / 45 |
| 财务/关闭/受控重开 | 11 / 52 |
| 接口消息/真实HTTP回执 | 6 / 33 |
| 写锁等待/连接恢复/版本并发 | SQLite 6 / 42; MySQL 6 / 11 |

新增并发套件中31条断言验证SQLite驱动特有行为,其余11条也在MySQL执行;两库最终断言总数按各自CI实际结果记录.

## 可复现命令

```bash
clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'
# 必须指向一次性独立空库;六个模块使用互不冲突的合成用户编号
PMS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3306/hc_pms_test?user=USER&password=PASSWORD&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'
BASE_URL=http://127.0.0.1:3100 pnpm exec playwright test tests/e2e/pms.spec.js tests/e2e/pms-workbench.spec.js --project=chromium
pnpm exec shadow-cljs release app
clojure -T:build all
```

独立发布包冒烟在临时目录使用以下配置启动,轮询`/api/health`后核对`/pms/project`和未登录`/api/pms/projects`;再使用隔离库的模板演示账号实际登录,创建并读取一个合成草稿项目,确认version=1/status=draft. 最后终止该进程并检查正常关闭日志:

```bash
PORT=3101 HTTP_HOST=127.0.0.1 NREPL_PORT=0 FLOWABLE_ASYNC=false \
  JDBC_URL=jdbc:sqlite:/ABSOLUTE/TEMP/jar-smoke.db MIGRATION_DIR=migrations-sqlite \
  FLOWABLE_JDBC_URL='jdbc:h2:mem:pms_release_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
  java -jar target/rouyi-standalone.jar
```

`/ABSOLUTE/TEMP`需替换为独立临时目录. `DB_CLOSE_ON_EXIT=FALSE`避免H2的JVM关闭钩子先于Flowable关闭而删除内存表;当前演示服务及其数据库不参与该检查.

[迁移往返与规模检查完整复现](migration-verification.md). 测试使用合成项目和本地HTTP接收方,未连接企业生产系统或真实客户资料.

## 增量验证: A08 项目成员任命书 (2026-09-22 本轮)

范围为矩阵 A08 (PPT 第17页): 团队维护后生成受控任命书, 内容与当时团队快照一致, 再任命保留旧版. 实现采用独立表 `pms_appointment` (SQLite 与 MySQL 迁移文件已同步), 领域命名空间 `com.ruoyi.domain.pms.governance.appointment`, 治理读模型新增 `appointments` 数组, HTTP 新增 `POST /appointments` 及 `GET /appointments/:rid/content` 与 `.../download`, 前端在"需求与治理"工作台新增"成员任命"页签, 提供签发弹窗, 不可变版本列表, 正文预览与本地下载.

设计保证: 团队快照, SHA256 摘要, 正文和人数全部由服务器读取 `pms_member` 派生, 客户端白名单仅接受 `issued_on` 与 `note`, 无法伪造内容; 再次任命按 `MAX(revision)+1` 生成新不可变版本, `UNIQUE(project_id, code, revision)` 保留旧版; 权限, 乐观版本, 暂停/终态阻断和 `appointment.issued` 审计全部经 `kernel/mutate!` 在同一事务内保证; 记录按项目作用域隔离, 跨项目读取 404.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 13 tests / 125 assertions, 0 失败/错误 | 新增 3 个任命书用例: 快照与团队一致且不可变, 权限/版本/输入与项目隔离, HTTP 合同与下载 |
| 全量 PMS 回归 SQLite | 65 tests / 456 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 3 files compiled / 0 warnings | `npx shadow-cljs compile app` |
| 冷启动迁移 | 通过 | 重启本项目后端 (HTTP 3100 / nREPL 55153), 启动自动应用 `202609220010-appointment` |
| 现场 HTTP (真实 JWT, 端口3100) | 通过 | 未鉴权 POST 401, viewer 403, 签发 V1/V2 不可变链, content 的 snapshot_sha256 与 download 的 X-Content-SHA256 一致, 正文由快照派生 |
| Chrome 浏览器 (Playwright) | 1 passed | `tests/e2e/pms-appointment.spec.js`: 界面签发→两不可变版本→预览→下载, 无未捕获 JS 错误 |

本轮未执行 (如实记录): MySQL 回归. 本地无可用 MySQL 实例 (3306/3308 未监听), 因此新模块 MySQL 测试未运行. 双库迁移文件已逐条同步并复核, MySQL 版按项目既有约定将业务日期列对齐为 `VARCHAR(10)`, 大文本用 `LONGTEXT`; 待有 MySQL 环境时以 `PMS_TEST_JDBC_URL=... clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 补跑.

边界: 不提供电子签名/法律签章, 不接入外部文书模板系统; 快照来源仅为项目成员表, 不含外部 HR 事实. A08 达到 `implemented / local`, 不等于矩阵整行所有复合规则或生产签收完成.

## 增量验证: H02 干系人, RACI与沟通计划 (2026-09-22 本轮)

范围为矩阵 H02 (工程基线新增): 识别利益相关者及职责, 明确知会/参与/批准关系, 沟通节奏可执行并有调整记录. 实现采用领域命名空间 `com.ruoyi.domain.pms.governance.stakeholders`, 复用通用治理存储 `pms_gov_record` 并为其新增 `stakeholder`, `raci`, `comm-plan` 三种 kind (SQLite 与 MySQL 迁移 `202609220005-governance` 的 CHECK 约束已同步扩展 kind 与 `active`/`assigned` 状态). 治理读模型新增 `stakeholders`, `raci`, `comm_plans` 数组与派生的 `raci_conflicts`; HTTP 新增 `POST /stakeholders(+/:rid/revisions)`, `/raci`, `/comm-plans(+/:rid/revisions)` 与 `/comm-plans/:rid/meeting`.

设计保证: 干系人与沟通计划为不可变版本记录, 修订以 `previous_id` 回指且 `code` 不得改变 (改码 400, 陈旧版本 409); RACI 按活动强制同干系人不重复指派且同活动至多一个负责(A)角色 (违反 409), 读模型逐活动汇总缺 A/缺 R 冲突但不阻断登记; 沟通计划受众限 1..50 个不重复同项目有效干系人, 生成会议仅允许最新版本并从受众已绑定项目成员去重派生参会人 (无成员 409), 回写 `last_meeting_id` 形成沟通计划到会议闭环. 全部命令经 `kernel/mutate!` 在同一事务内做权限, 乐观版本, 暂停/终态阻断与审计, 记录按项目作用域隔离.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 15 tests / 155 assertions, 0 失败/错误 | 新增 2 个 H02 用例: 干系人/RACI 冲突与沟通计划到会议闭环 + HTTP 合同; 覆盖越权403, 重码409, 改码400, RACI重复指派与双A 409, 陈旧版本409, 空受众400, 跨项目404 |
| 全量 PMS 回归 SQLite | 67 tests / 486 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 迁移 CHECK 双库同步 | 一致 | SQLite 与 MySQL 两处 `202609220005-governance` 的 kind/status CHECK 同步扩展, 空库经真实迁移建表后约束验证通过 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app`, 治理工作台"干系人与沟通"页签及 `antd/alert` 包装随产物一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `e2e-h02.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 关闭), 自动应用 H02 治理迁移并建表 |
| 现场 HTTP (真实 JWT, 端口3100) | 通过 | 经 `POST /stakeholders`, `/raci`, `/comm-plans`, `/comm-plans/:rid/meeting` 真实调用, `GET ""` 读模型回显 `raci_conflicts` 缺A, 生成会议回写 `last_meeting_id` 且参会人取自绑定成员 |
| Chrome 浏览器 (Playwright) | 1 passed, 11.8s | `tests/e2e/pms-h02.spec.js`: 界面登记干系人 SH-B→RACI缺A冲突提示可见→沟通计划生成会议闭环并回写 `last_meeting_id`, 无未捕获 JS 错误 |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例 (3306/3308 未监听), 待有环境时以 `PMS_TEST_JDBC_URL=... clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 补跑. 外部通知/消息渠道自动提醒与定时提醒任务本轮未接通 (生成会议为手动受控操作, 不含按沟通节奏自动派发).

边界: 干系人责任人须为项目成员, 参会人由绑定成员派生, 不含外部 HR 事实. 冲突提示与沟通计划生成会议已在治理工作台"干系人与沟通"页签落地并经浏览器验证. H02 达到 `implemented / local`, 不等于矩阵整行所有复合规则, 自动提醒/消息渠道推送或生产签收完成.

## 增量验证: C07 会议行动完成与独立核验 (2026-09-22 本轮)

范围为矩阵 C07 (PPT 第4,17-18页): 会后行动项除转 WBS 任务外, 还需可追踪地"完成"并经验证关闭, 逾期未处理可见. 本轮在既有会议/行动/转任务链路上补齐行动闭环, 复用通用治理存储 `pms_gov_record` 的 `action` kind (其状态 CHECK 已含 `in_review`/`closed`/`rejected`, 无需新增迁移). 领域新增 `collaboration/complete-action!`, `collaboration/verify-action!` 与派生读模型 `collaboration/action-read-model`; 治理命令表新增 `[:actions :complete]`, `[:actions :verify]`; HTTP 新增 `POST /actions/:rid/complete` 与 `POST /actions/:rid/verify`; 前端"会议行动"页签新增逾期标记列, 完成说明列, 提交完成弹窗与核验人批准/驳回入口.

设计保证: 提交完成要求结果说明, 至少一份同项目不可变证据版本和一个独立审核人 (审核人不得为提交人 409, 须具 `pms:quality:approve` 且对项目有访问权否则 403, 缺字段 400, 无证据 409), 状态 open/rejected -> in_review 并记录 `submitted_by` 与 `review_action action_closure`. 核验关闭仅允许指定审核人操作 (非指定人 403, 自行核验本人提交 409), approved -> closed / rejected -> rejected, 关闭前再次校验证据仍绑定. 读模型对每条行动按服务器当前日期计算派生 `action_overdue` (有到期日且状态非 closed/converted 且到期日不晚于今日为 true), 关闭后自动转 false. 全部命令经 `kernel/mutate!` 在同一事务内做权限, 乐观版本, 暂停/终态阻断与审计.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 16 tests / 169 assertions, 0 失败/错误 | 新增用例 `meeting-action-completion-verifies-independently-and-flags-overdue`: 逾期初值为真, 无证据 409, 审核人为本人 409, 无权限审核人 403, 缺审核人 400, 提交后 in_review 且记录 submitted_by/reviewer_id, 非指定人核验 403, 冒名核验 403, 驳回->rejected 后重提再批准->closed, 关闭后逾期转 false |
| 全量 PMS 回归 SQLite | 68 tests / 500 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files, 10 compiled), 逾期标记列/完成说明列/提交完成弹窗/核验入口一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c07-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| 现场 HTTP (真实 JWT, 端口3100) | 通过 | 经 `/governance/documents`, `/meetings`, `/meetings/:rid/actions`, `/actions/:rid/complete`, `/actions/:rid/verify` 真实调用, `GET ""` 读模型回显 `action_overdue` 与状态流转 |
| Chrome 浏览器 (Playwright) | 1 passed, 20.6s | `tests/e2e/pms-c07.spec.js`: 双真实上下文 (提交人 admin 与独立核验人) 走完 逾期标记可见->界面提交完成(选证据+独立审批人)->核验人登录见批准关闭->批准关闭后逾期标记消失, 核验人上下文侧栏仅见受限菜单佐证 RBAC, 无未捕获 JS 错误; 4 张真实截图存 `reports/c07/` |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 行动到期为读取时派生计算, 未接通定时任务主动提醒/消息推送 (矩阵 C11 通知预警仍 planned), 会前资料包自动关联仍待补齐.

边界: 完成与核验为职责分离的受控命令, 不提供任意状态改写; 逾期为读取时计算字段, 不写入存储. C07 达成 `partial` (行动完成+独立核验+逾期可见子集已 `implemented / local`), 不等于整行含自动提醒与会前资料包的全部复合规则或生产签收完成.

## 增量验证: C09 问题责任人转派 (2026-09-22 本轮)

范围为矩阵 C09 (PPT 第18页): 问题/风险处理责任人可受控转派, 转派保留原责任人与原因供审计, 新责任人须为当前项目成员. 本轮复用通用治理存储 `pms_gov_record` 的 `issue` kind (状态 CHECK 已含 `open`/`rejected`/`closed`, 无需新增迁移). 领域新增 `collaboration/reassign-issue!`; 治理命令表新增 `[:issues :reassign]`; HTTP 新增 `POST /issues/:rid/reassign`; 前端"风险与问题"页签问题行新增"转派"入口与转派弹窗.

设计保证: 转派仅允许状态 open/rejected 的问题 (关闭后转派 409), 新责任人经 `kernel/user!` 校验为项目有效成员否则 400, 转派原因必填 (空 400), 编辑权限与项目访问经 `kernel/mutate!` 保证 (越权 403); 转派不改变问题状态, 只在 payload 写入 `reassigned_from` (原责任人), `reassign_reason`, `reassigned_by` (操作人) 三个审计字段并更新 `owner_id` 列, 由 `store/change!` 合并保留历史. 全部命令在同一事务内做权限, 乐观版本, 暂停/终态阻断与 `issue.reassigned` 审计, 记录按项目作用域隔离.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 17 tests / 181 assertions, 0 失败/错误 | 新增用例 `issue-reassign-changes-owner-with-audit-and-guards-membership`: 非成员新责任人 400, 空原因 400, 越权转派 403, 转派后 owner 变更且 `reassigned_from`/`reassign_reason`/`reassigned_by` 留痕且状态不变 open, 关闭后转派 409 |
| 全量 PMS 回归 SQLite | 69 tests / 512 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 转派入口与转派弹窗一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c09-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| 现场 HTTP (真实 JWT, 端口3100) | 通过 | 经 `/governance/issues`, `/governance/issues/:rid/reassign` 真实调用, 非成员 999999 转派回 400, 空原因回 400, 有效成员转派回 200 且读模型回显审计字段 |
| Chrome 浏览器 (Playwright) | 1 passed, 14.2s | `tests/e2e/pms-c09.spec.js`: 界面登记问题 (责任人 admin)->风险与问题页签见"转派"入口->转派弹窗选新责任人 (项目成员) 并填原因->保存后 owner 变更且 `reassigned_from`=admin, `reassign_reason`, `reassigned_by`=admin 留痕, 状态仍 open, 转派后仍可再次转派; 非成员/缺原因经真实 HTTP fetch 断言 400; 3 张真实截图存 `reports/c09/` |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 转派为手动受控命令, 未接通自动升级/逾期改派或消息推送 (矩阵 C11 通知预警仍 planned).

边界: 转派保留完整审计链 (原责任人/操作人/原因), 不提供任意责任人字段改写或跨项目指派; 新责任人硬性限定为当前项目有效成员. C09 达成 `partial` (问题责任人受控转派+审计留痕子集已 `implemented / local`), 不等于整行含自动升级/提醒的全部复合规则或生产签收完成.

## 增量验证: C05 证据文档批量下载 (2026-09-22 本轮)

范围为矩阵 C05 (PPT 证据/文档相关页): 同一项目内多份确定版本的证据文档可一次性打包批量下载, 下载保留每份原文与校验摘要供离线核对. 本轮复用通用治理存储 `pms_gov_record` 的 `document` kind (不可变版本, 正文与 SHA256 存于 payload, 无需新增迁移). 领域新增 `evidence/batch-content` 与 `governance/document-batch`; HTTP 新增 `POST /documents/batch-download`; 前端"证据版本"页签新增"批量下载"入口.

设计保证: 批量读取经 `kernel/read!` 执行 `pms:project:query` 权限与项目作用域校验 (越权 403); 每个 record_id 复用 `store/record!` 按项目+kind+id 精确取回, 缺失或类型不符 404, 不泄露跨项目对象; 入参 `record_ids` 须为 1 到 50 个不重复 ID 否则 400, 非法 body 键经 `rules/object!` 白名单拒绝 (400). HTTP 层将每份正文写为 ZIP 条目 (文件名前缀 record_id 前 8 位防冲突), 附 `MANIFEST.tsv` (record_id/code/revision/entry/sha256/byte_size), 响应 `application/zip` 并回 `X-Batch-Count`; 只读命令不改变任何记录状态或版本.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 18 tests / 202 assertions, 0 失败/错误 | 新增用例 `document-batch-download-packages-authorized-versions-and-rejects-invalid`: 域层返回 2 份正文且 sha256 匹配且无 payload 泄露, 非成员 403, 缺失/跨项目 404, 空/重复/超 50/非法键 400, HTTP 200 返回 application/zip, 解压后逐条目正文 sha256 与登记一致且含 MANIFEST |
| 全量 PMS 回归 SQLite | 70 tests / 533 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app`, 批量下载入口与 blob 下载一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c05-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed, 11.9s | `tests/e2e/pms-c05.spec.js`: 界面登记两份证据文档 (含首尾空格正文)->证据版本页签见两行与"批量下载"入口->空 `record_ids` 经真实 HTTP fetch 断言 400->点击批量下载捕获 download 事件, 文件名 `证据文档.zip`, 落盘 ZIP 首 2 字节 `PK`, 解压 2 份正文+MANIFEST 且 sha256 与界面摘要列一致; 2 张真实截图存 `reports/c05/` |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 批量下载为同项目文本证据的只读打包, 未接通按密级过滤, 二进制附件流式打包或超大 ZIP 分卷 (矩阵相关复合规则仍 partial/planned).

边界: 批量下载严格限定同一项目内 1..50 个确定文档版本, 任一引用非法整体失败且不泄露跨项目对象; 只读不改状态. C05 达成 `partial` (同项目多版本证据批量打包下载子集已 `implemented / local`), 不等于整行含密级过滤/二进制附件的全部能力或生产签收完成.

## 增量验证: C04 文档密级与阶段归集 (2026-09-22 本轮)

范围为矩阵 C04 (PPT 第4,58页): 证据文档可按阶段/结构/密级归集, 文档编号/版本/状态/创建人/密级可追踪. 本轮复用通用治理存储 `pms_gov_record` 的 `document` kind, 在既有真实文本不可变版本 (SHA256/创建人已在 C01/C05 覆盖) 基础上, 为登记与修订新增三个可选归集字段, 无需新增迁移.

设计保证: `document!` 白名单扩展 `classification`/`stage`/`structure_node`. `classification` 为枚举 `public|internal|confidential`, 缺省记为 `internal`, 非法取值经 `s/enum!` 返回 400; `stage` 与 `structure_node` 为至多 100 字符的可选文本 (`s/optional-text!`), 留空记为空串. 字段随不可变版本存入 payload 并进入 workspace 读模型, 修订保持编号不可改的既有规则. 这些字段仅用于项目内归集与追踪, 不替代项目授权, 本轮不据密级过滤下载或访问 (密级过滤属 `待规则`, 需用户密级来源). 批量下载 `MANIFEST.tsv` 相应新增 `classification/stage/structure_node` 三列.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 19 tests / 216 assertions, 0 失败/错误 | 新增用例 `document-classification-stage-and-structure-are-traceable`: 缺省密级为 internal, 显式 confidential 及 stage/structure_node 持久化, 非法密级枚举 400, 未知字段 400, 修订保持编号且新版本可独立设定密级, 批量下载 MANIFEST 含 classification/stage/structure_node 列 |
| 全量 PMS 回归 SQLite | 71 tests / 547 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 密级下拉/阶段/结构节点输入与台账密级列一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c04-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed, 11.9s | `tests/e2e/pms-c04.spec.js`: 界面登记文档A (机密/设计/主机-控制柜) 与文档B (未选密级)->读模型回显 A=confidential/stage/structure, B=internal->台账显示密级"机密/内部"与阶段"设计"列->非法密级 top-secret 经真实 HTTP fetch 断言 400->修订改码被真实 HTTP 断言 400; 1 张真实截图存 `reports/c04/` |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 密级为归集追踪字段, 未接通按用户密级过滤下载/访问 (属 `待规则`), 亦未实现按阶段/结构节点的多层归集视图与二进制存储.

边界: 归集字段随不可变版本持久化且不替代项目授权; 编号不可改与摘要校验沿用既有规则. C04 达成 `partial` (密级/阶段/结构节点作为可追踪归集字段子集已 `implemented / local`), 不等于整行含多层归集视图/密级过滤/二进制存储的全部能力或生产签收完成.

## B05/C07 会议会前资料绑定 (本轮增补, 2026-09-22)

设计与关闭口径: 在既有会议登记命令上补齐原蓝图 C07/B05 长期列为"会前资料仍待补齐"的一环——会议可引用项目内真实不可变文档版本作为会前资料. `create-meeting!` 白名单新增可选 `material_ids`, 复用 `store/evidence!` 以 `required? false` 校验: 必须为 0..50 个不重复, 每个 ID 经 `record!` 断言为同项目 `document` 类型 (不存在/跨项目/非文档类型 404), 重复或超上限或非法数组 400; 留空记为 `[]`. 引用随不可变纪要存入 payload 并进入 workspace 读模型. 该字段仅表示会议对既有受控文档版本的引用, 不改变文档状态, 不构成新的文档发布审批, 也不替代项目授权. 沟通计划生成的会议 (H02) 不受影响, 其 `material_ids` 为空.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 20 tests / 225 assertions, 0 失败/错误 | 新增用例 `meeting-can-reference-real-document-versions-as-pre-read-materials`: 两份文档版本作为会前资料持久化并读模型回显, 无资料会议记为 `[]`, 引用不存在/跨项目/非文档类型均 404, 重复及超 50 均 400, 未知字段 `materials` 400 |
| 全量 PMS 回归 SQLite | 72 tests / 556 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 会议登记弹窗会前资料多选与台账会前资料计数列一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/b05-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 见下 `pms-b05.spec.js` | 界面登记证据文档->登记会议时选择该文档为会前资料->读模型回显 material_ids->台账会前资料列计数->引用不存在文档版本经真实 HTTP fetch 断言 404; 截图存 `reports/b05/` |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 会前资料为对既有受控文档版本的引用, 未实现"售前资料/主计划版本"作为专门会前包与启动会的强制关联, 亦未对被引用文档版本的后续失效做专门阻断.

边界: `material_ids` 与整改/Gate 证据复用同一 `evidence!` 同项目文档版本校验语义; 不改变文档不可变版本与摘要校验. C07 与 B05 维持 `partial` (会前资料引用子集已 `implemented / local`), 自动到期提醒 (C07) 与专门售前资料/主计划版本关联 (B05) 仍待补齐, 不等于整行能力或生产签收完成.

## 核心通过场景

1. 四种依赖关系,工作日/例外日历,已知并行网络的CPM与浮动,树形任务隔离和循环拒绝;跨项目人员占用仅显示匿名汇总. 提交计划锁定,独立批准形成不可变基线,执行期重基线绑定已批准变更. 审批中变更失效仍可驳回解除锁定.
2. 章程批准,URS版本/整批CSV预检与事务导入,真实文本字节与SHA256下载一致,需求版本追踪,Gate缺证据阻断和独立签核,会议行动幂等转WBS. 风险定期复评/独立关闭,风险转问题,问题整改独立验证及带证据的受控重开.
3. 物料申请,独立BOM冻结,真实齐套,装配开工及独立交检,SIT/FAT/SAT结果和失败问题,真实日期约束与上游复验使下游旧资格失效,发运及独立签收,售后异常独立关闭. 交付对象保留任务/URS/证据追踪.
4. 整数金额,小数精度,负数调整/零成本确认/零收入负毛利,同源账项去重,批准成本不可改写. 工时只参与批准后分摊,跨项目同日不超过24小时,拒绝释放容量;分摊输入冻结,总额守恒,零分母阻断,幂等不重计.
5. 实际创建并批准章程/基线/执行Gate后进入执行,完整完成物料至签收与SAT,工时/成本审核,任务完工,关闭Gate/决算/清单,独立关闭审批与归档. 审批后内容变化拒绝关闭;关闭后只读;独立重开返回closing且必须重新关闭审批.
6. 身份和功能权限实时验证,无财务权限不可读取金额,通用审计不暴露财务审核意见. 撤销成员立即失去项目范围,仍有任务/指定审批/移交责任时要求先交接. 审计故障时对象和项目版本共同回滚.
7. 入站同ID同内容幂等/冲突拒绝/低版本不倒退;外发只读取真实已批准版本. 实际HTTP2xx仍需匹配message_id/receipt_id;错误回执退避到死信,人工重试保留原业务键. 超大正文取消,完整响应超时包含正文停顿,未知配置503而不是假成功.

## CI故障修复轨迹

首次新代码运行 `6823cea` 的SQLite通过;MySQL完成新迁移及前几个模块后,发现交付和接口测试都使用角色9500,共享测试库主键冲突. 已把接口模块的合成用户隔离到9600段,没有修改业务断言或跳过模块,本地接口6/33复验通过. 修复提交 `276c52c` 重新执行完整CI,SQLite和MySQL均通过. 浏览器运行发现慢环境下下拉框旧选项和偶发SQLite写锁冲突,随后在cfe4b15修复并补充专项回归.

本地首次全量回归保留的旧断言仍要求旧提示包含Gate,而真实新实现先提示缺少批准基线. 已将断言对齐真实第一项准入规则,仍验证HTTP409且不跳过生命周期检查. 合成浏览器下拉定位/页签重挂载/差异JS对象转换问题均已修复并通过原7例回归,后续增加延迟场景后完整8例通过.

浏览器CI暴露了两个真实问题,而不是单纯的定位器超时. 保存成功后GET尚未完成时可继续打开弹窗,会固化旧关联选项及项目版本; 受控延迟回归已证明旧实现失败,修复为保留页签并在两类资源都刷新完成前禁用操作. 同时,鉴权在线心跳与PMS默认DEFERRED事务的读后写升级争用SQLite锁,新增持锁回归在旧实现出现8项失败. 修复为PMS写事务在读取前以IMMEDIATE取得写锁,最多等待5秒,不重放业务回调;等待超时,业务失败及成功均恢复池连接状态. 仍保留真实旧版本409和审计失败全回滚. 修复提交`cfe4b15`的完整最终CI已全部通过,实际结果见本页验证表. 保留全部业务断言,没有增加测试重试次数或放宽等待超时来掩盖问题.

## 实际交付边界

- 本次已实现和已验证子能力与未完功能分别列入[106项矩阵](11-feature-acceptance-matrix.md). 不能把部分实现的PPT复合条目标为全部完成.
- 八类真实系统字段/认证/责任边界/沙箱尚未提供;本地通用协议和回执测试不代表CRM/OA/ERP/PLM/MES/SRM/销服物料/BI已联通.
- 完整主子单机计划网络/进度卷积/局部暂停,专属业务模板/沟通自动提醒与消息渠道推送,二进制/密级/批量文档及正式签发,完整财务更正封期/费率/跨项目池/汇率税务/经营口径,AI等仍按矩阵保留真实待办.
- 生产升级/回退和备份恢复,负载/兼容性/既有数据迁移/业务UAT尚未验收. 未重跑继承模板全部历史办公/BPM测试. 公开源码和本地启动不等于生产部署.
