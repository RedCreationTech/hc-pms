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

## C03 URS追踪矩阵与缺链检查 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐矩阵 C03 长期列为"自动缺链仍待定义"的一环——按需求最新版本只读聚合追踪链缺项. 领域新增纯函数 `evidence/traceability-report` 与 `evidence/trace-summary`; `governance/workspace` 读模型新增 `traceability` (逐需求最新版本 `{requirement_id, code, revision, priority, design_links, verification_links, satisfied?, verified?, missing}`) 与 `trace_summary` (`{requirements, fully-traced, missing-design, missing-verification}`). `design_links` 计数满足关系的文档版本, `verification_links` 计数验证关系, `missing` 为 `["satisfies"|"verifies"]` 中缺失项. 该矩阵是只读缺链提示, 分母仅取当前最新版本需求集合; 需求修订产生新版本后须重新追踪, 新版本无链即重新提示缺链. 明确不引入覆盖率分母, 不承诺对批准范围基线的覆盖率 (覆盖率分母规则仍待定义), 不改变追踪记录本身的双向校验与不可变版本语义. 前端"URS与追踪"页签新增"URS追踪完整性检查"面板, 顶部汇总标签与逐需求缺链标签 (追踪完整/缺设计满足/缺验证证据) 直接消费该读模型.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 22 tests / 245 assertions, 0 失败/错误 | 新增 `traceability-report-computes-per-version-link-gaps` (纯单元: 三需求含 URS-1 rev1+rev2, 断言最新版本缺双链, 满足不验证 design_links=1 missing=["verifies"], 汇总计数) 与 `workspace-traceability-reflects-real-requirement-traces` (真实命令: URS-A 满足+验证双链齐备 missing=[], URS-B 未追踪缺双链; 修订 URS-A 后新版本 missing=["satisfies","verifies"] 且整链齐备归零) |
| 全量 PMS 回归 SQLite | 74 tests / 574 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 追踪完整性检查面板与缺链标签列一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c03-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed (26.7s) | `pms-c03.spec.js`: 界面登记设计/验证两份证据文档, 登记两条需求并对 REQ-1 建立满足+验证双向追踪, 打开"URS与追踪"页断言汇总标签 (需求版本 2/整链齐备 1/缺设计满足 1/缺验证证据 1), REQ-1 显示"追踪完整", REQ-2 显示"缺设计满足"+"缺验证证据"; 真实 HTTP GET 回显 traceability/trace_summary 一致; 再经 API 修订 REQ-1 断言新版本双缺链且整链齐备归零; 截图存 `reports/c03/` |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现覆盖率分母与对批准范围基线的覆盖率, 未实现 SIT/FAT/SAT 偏差分级对 Gate 的阻塞联动, 未做缺链到具体缺失交付物的下钻定位.

边界: 缺链为读取时按最新版本计算的只读视图, 不写入存储, 不改变追踪记录创建时的同项目文档/任务与需求版本校验. C03 维持 `partial / 待规则` (按最新版本自动缺链与汇总子集已 `implemented / local`), 覆盖率分母与偏差级别仍待定义, 不等于整行能力或生产签收完成.

## C06 文档独立发布审批与正式签发 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐矩阵 C06 长期列为"文档独立发布审批/正式签发仍待实现"的一环——文档从登记到发布留受控版本轨迹. 复用通用治理存储 `pms_gov_record` 的 `document` kind 与既有 `registered`/`in_review`/`approved`/`rejected` 状态 (无新增迁移). 领域新增 `evidence/submit-release!` (`registered`/`rejected` -> `in_review`, 经 `reviewer!` 指定具备 `pms:quality:approve` 且非提交人的独立审核人) 与 `evidence/decide-release!` (`in_review` -> `approved` 记 `released_by`/`decision_reason`, 或 -> `rejected`), 提交与决定均走 `latest!` 只能在最新版本推进. 治理命令表新增 `[:documents :submit]`/`[:documents :decision]`, HTTP 新增 `POST /documents/:rid/submit` 与 `POST /documents/:rid/decision`. 前端"证据版本"页签新增发布状态列 (已登记/待发布审批/已发布/已退回) 与"提交发布"及审核人"批准发布/驳回"入口. 关键不变量: 新修订回到 `registered`, 已批准的旧版本保持 `approved` 不漂移, 也不替换旧 Gate/问题/验收所引用的版本. 本轮不引入正式电子签章或外部文书模板.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 23 tests / 264 assertions, 0 失败/错误 | 新增 `document-release-requires-independent-approval-and-does-not-drift`: 提交审核人为本人 409, 无审批权审核人 403, 多余字段 400; 非指定审核人决定 403, 提交人自审 403, 非法决定取值 400; 批准 -> approved 且 released_by=审核人; 新修订 -> registered, 在旧批准版本再提交 409, 旧版本读模型仍 approved; 驳回 -> rejected 且不写 released_by, 可再次提交 |
| 全量 PMS 回归 SQLite | 75 tests / 595 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 发布状态列与提交/审批入口一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c06-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 未触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed (48.2s) | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-c06.spec.js`: 界面登记证据文档 -> 提交发布选独立审核人 -> 审核人第二浏览器上下文批准发布填决策意见 -> 发布状态"已发布" -> admin 建新修订回到"已登记"且旧批准版本不漂移; 真实 HTTP GET 回显 status=approved 与 released_by=审核人; 截图存 `reports/c06/` (c06-1..c06-4) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现正式电子签章, 外部文书模板与归档留存策略, 未做发布后撤回/作废的受控反向流程.

边界: 发布审批复用与章程/变更一致的 `reviewer!`/`decision-actor!` 职责分离与 `latest!` 最新版本约束; 不改变文档不可变版本与 SHA256 摘要校验, 不改变 Gate/问题/验收对具体版本的固定引用. C06 维持 `partial` (文档独立发布审批链已 `implemented / local`), 电子签章与外部文书模板仍待实现, 不等于整行能力或生产签收完成.

## C04 文档归集视图与密级过滤 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐矩阵 C04 长期列为"尚无按阶段/节点归集视图, 密级过滤"的一环——让已登记的不可变归集字段 (密级/阶段/结构节点) 在工作台形成可核验的分层视图. 复用既有 `document` kind 与已持久化的 `classification`/`stage`/`structure_node` 字段, **无新增迁移**. 领域新增纯只读函数 `evidence/document-collection`: 以 `store/latest` 取每个文档 `code` 的最新 `revision`, 按 `stage`/`structure_node`/`classification` 聚合计数与 `total`, 空值归"未归集"并排在最后, 密级固定覆盖 `public|internal|confidential` 三值; 同一编号的旧修订不重复计数. `governance/workspace` 读模型新增 `document_collection`. 前端"证据版本"页签新增"文档归集视图"面板展示三类聚合与最新版本总数, 并给"文档与版本证据"表加按密级的客户端过滤 (仅过滤当前展示行, 不改变服务端授权与批量下载范围). 本轮不引入二进制存储与多层下钻归集.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 24 tests / 271 assertions, 0 失败/错误 | 新增 `document-collection-aggregates-latest-versions-only`: 登记跨阶段/密级/结构节点的四份文档并对 DOC-A 产生改阶段改密级的新修订, 断言 total=最新版本数 4, by-stage/by-structure-node 计数正确且空值归未归集排最后, by-classification 固定三值顺序稳定, 关键不变量——DOC-A 修订后离开"设计准备/机密"进入"测试/公开"且旧版本不重复计数 (机密计数归零) |
| 全量 PMS 回归 SQLite | 76 tests / 602 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 归集面板与密级过滤入口一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/c04b-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 不触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed (18.4s) | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-c04b.spec.js`: 界面登记跨阶段/密级/结构节点四份文档 -> 对 DOC-A 产生改阶段改密级的新修订 -> 归集视图按最新版本聚合(最新版本证据 4, 公开 2/内部 2/机密 0, 按阶段 测试·1/装配·1/设计·1/未归集·1, 按结构节点 主机·2/附件·1/未归集·1, 未归集排最后) -> 密级过滤表格 -> 真实 HTTP GET 回显 document_collection; 截图存 `reports/c04b/` (c04b-1, c04b-2) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现二进制/大文件存储, 未做按阶段/结构节点的多层下钻归集与跨层卷积.

边界: 归集为读取时按最新版本计算的只读视图, 不写入存储, 不改变文档不可变版本与 SHA256 摘要校验, 不改变项目授权与批量下载范围; 密级过滤仅作用于前端展示, 服务端仍按项目授权放行, 不据密级收紧访问. C04 维持 `partial` (按最新版本归集视图与密级过滤子集已 `implemented / local`), 二进制存储与多层下钻仍待实现, 不等于整行能力或生产签收完成.

## H01 章程初始预算 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐矩阵 H01 长期列为"初始预算与章程的正式关联仍待补齐"的一环. 复用既有 `charter` kind 与整条 create/revise/submit/decide 独立审批链, **无新增迁移**. 领域 `governance.approval` 新增 charter 专属可选字段 `initial_budget`/`budget_currency`: 金额经 `finance-money/amount!` 校验 (最多两位小数) 并拒绝负数, 规范化为两位小数最小单位后由 `money/money` 回显; 币种限 `CNY|USD|EUR|GBP|HKD`, 填预算而未选币种缺省 `CNY`, 非法币种 400; 未填预算则两键均不写入, 章程仍按原样创建. 两字段并入 `content!` 结果, 随内容版本进入不可变 payload, 修订派生新版本而旧版本预算不漂移; 预算是章程专属白名单字段, 出现在变更申请体上按白名单返回 400. 前端"章程"页签表单新增可选"初始预算"文本框与"预算币种"下拉 (留空经 transform 归一为缺省), 台账新增"初始预算"列显示"金额 币种"或对无预算版本显示"未设定". 本轮不引入初始预算与批准后财务基线的对账关联, 也不把授权PM作为章程显式字段.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 25 tests / 288 assertions, 0 失败/错误 | 新增 `charter-initial-budget-is-validated-and-versioned`: 填金额未选币种规范化 `120000.5`->`120000.50` 且缺省 `CNY`, 独立批准后预算不漂移, 修订生成新不可变版本 (`88.90/USD`) 而旧版本保持 `120000.50/CNY`, 未填预算章程不含预算键, 超两位小数/非数字/负数金额与非法币种 `RUB` 均 400, 合法变更体追加 `initial_budget` 按白名单 400 |
| 全量 PMS 回归 SQLite | 77 tests / 619 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 章程表单预算字段与台账"初始预算"列一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/h01-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 不触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed (14.4s) | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-h01.spec.js`: 界面登记章程填金额 `88.9` 并显式选币种 `USD` -> 命令响应与台账回显规范化 `88.90 USD` -> API 修订未选币种缺省 `120000.50 CNY` -> 再修订取消预算 -> 台账三版本分别显示 `88.90 USD`/`120000.50 CNY`/`未设定` 且旧版本预算不漂移 -> GET 回显三不可变版本预算字段 -> 真实 HTTP 超两位小数金额与非法币种均 400; 截图存 `reports/h01/` (h01-1) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现初始预算与批准后财务基线/成本台账的对账关联, 未把授权PM作为章程显式独立字段 (现由项目 manager_id 与编辑权限隐含承载).

边界: 初始预算是立项期在章程中声明的可选金额, 随内容版本不可变冻结, 复用章程既有独立审批与最新版本约束; 不等于批准后锁定的财务基线或成本台账 (后者由财务域独立管理), 也不据此收紧任何访问授权. H01 维持 `partial` (目标/范围/成功标准/赞助人/独立批准/版本冻结与新增的初始预算规范化与校验均已 `implemented / local`), 授权PM显式字段与预算-财务基线对账仍待补齐, 不等于整行能力或生产签收完成.

## H08 风险超阈值自动升级 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐矩阵 H08 长期列为"重大风险升级处置仍待补齐"的一环. 复用既有 `risk` kind 与整条复评/独立审核链, **无新增迁移** (升级字段随 payload JSON 存储). 领域 `governance.collaboration` 在 `create-risk!` 计算 `score = 概率 x 影响` (均 1-5, 评分 1-25) 后, 达到阈值 `escalation-threshold` (16) 即自动置 `escalated=true` 并写入 `escalation_state="pending"`, 按评分分层 `escalation-level` (>=20 为 `steering`, >=16 为 `management`) 与可读 `escalation-reason`; 未达阈值不写升级键, 既有 3x5=15 与 2x3=6 用例不受影响. 新增 `acknowledge-escalation!` (命令 `[:risks :escalate]`, 路由 `POST /risks/:record_id/escalate`) 须 `pms:quality:approve` 且 `{:write? false}` (只读范围审批人亦可确认), 校验: 未升级 409, 已确认再确认 409, 登记人本人确认 403, `decision` 限 `approved|rejected` (否则 400); 批准记 `escalation_state="acknowledged"`, 驳回(经评估可在现层处置)记 `"waived"`, 并留 `escalation_ack_by/decision/note/on` 与工作流历史. `mitigate!` 增加门控: 风险 `escalated` 且 `escalation_state="pending"` 时自行缓解返回 409, 须先经独立确认方可解除. 前端"风险与问题"页签台账新增"评分"列与"超阈值升级"列 (未触发/待升级确认·层级/升级已确认/升级已豁免), 仅对登记人之外的质量审批人在 pending 态显示"确认升级处置"入口, 弹窗 `risk-escalation-dialog` 选处置决定并填意见. 本轮不做复评后按新评分重新触发升级, 不做升级通知投递与跨项目风险汇总.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 26 tests / 307 assertions, 0 失败/错误 | 新增 `risk-escalation-requires-independent-acknowledgment-before-mitigation`: 5x5 高风险自动 `escalated`/`pending`/`steering` 且带 `escalation_reason`, 2x3 低风险不升级; 未确认自行缓解 409, 低风险缓解成功 `mitigated`, 低风险误发升级确认 409, 登记人自确认 403, 非法决定 400, 独立审批人(9302)批准后 `acknowledged`/状态仍 `open`/`ack_by=9302`/`decision=approved`, 重复确认 409, 此后高风险缓解成功 `mitigated`, workspace 读模型行反映 `acknowledged` 且 `escalated=true` |
| 全量 PMS 回归 SQLite | 78 tests / 638 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'`, 无回归 (既有 3x5=15 与 2x3=6 风险用例不受阈值 16 影响) |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4027 files), 风险台账评分/升级列与"确认升级处置"弹窗一并编译 |
| 冷启动迁移 (隔离库) | 通过 | 以独立 `/tmp/h08-e2e.db` 全新迁移至 `:3100` (HTTP 3100 / nREPL 7100), 不触碰 `:3000` 现有实例与默认库 |
| Chrome 浏览器 (Playwright) | 1 passed (21.0s) | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-h08.spec.js`: 界面登记 5x5 重大风险 -> 命令响应 `score=25`/`escalated=true`/`pending`/`steering` -> 台账"超阈值升级"列显示红色"待升级确认 / steering" 且登记人本人无"确认升级处置"入口 -> 真实 HTTP 未确认自行缓解 409、登记人自确认 403 -> 第二个已登录上下文合成独立质量审批人(只读+`pms:quality:approve`)视角出现"确认升级处置"入口并批准责成处置 -> 徽标翻转为"升级已确认"且确认入口消失 -> 门控解除后真实 HTTP 缓解 200 状态 `mitigated`; 截图存 `reports/h08/` (h08-1 待升级确认, h08-2 审批人确认入口, h08-3 升级已确认, h08-4 门控解除已缓解) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现复评改分后重新评估升级层级, 未实现升级待办的通知/消息投递, 未实现跨项目重大风险汇总看板与治理层集中确认.

边界: 升级门控只约束"超阈值重大风险在未经登记人之外独立质量审批人确认前不得自行缓解", 不改变风险既有复评/独立关闭链与项目授权; 确认动作本身要求 `pms:quality:approve` 且不得由登记人本人完成, 与文档发布/行动核验的独立性口径一致. H08 维持 `partial` (评分自动触发升级、层级与理由、独立确认解除缓解门控均已 `implemented / local`), 改分重评、升级通知投递与跨项目汇总仍待补齐, 不等于整行能力或生产签收完成.

## C10 典型风险库一键实例化 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐矩阵 C10 长期列为"典型风险模板/库仍待补齐"的一环. 本轮以代码内置的静态典型风险库交付, 复用既有 `risk` kind 与整条 H08 评分升级链, **无新增迁移** (来源字段随 payload JSON 存储). 领域 `governance.collaboration` 定义 `risk-library` 向量 (5 条: 进度延误 4×4=16 management, 关键物料断供 5×5=25 steering 且 stage="采购", 技术方案不成熟 3×3=9, 成本超支 4×5=20, 人员流失 2×3=6), 每条含 `key/category/title/probability/impact/mitigation/stage`. 新增 `from-library!` (命令 `[:risks :from-library]`, 路由 `POST /risks/from-library`) 入参白名单 `[:template_key :owner_id :due_date]` (+version), 按 key 查库 (未知 key 404), 与手工登记共用 `insert-risk!` 计算评分并触发 H08 自动升级门控, 同时回写 `source_key/source_category/stage` 以追溯来源; 未达阈值条目不写升级键. workspace 读模型新增 `assoc :risk_library` 供前端选用面板. 前端"风险与问题"页签在"登记项目风险"旁增加"从典型风险库选用"主按钮 (弹窗选条目 + 负责人 + 计划应对日期), 并新增"来源"列, 命中库源显示紫色"风险库"标签. 本轮不做用户自建/编辑风险模板库, 不做跨项目模板共享与治理层集中审批, 库内容为工程验收用的精选目录而非可运营知识库.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 29 tests / 343 assertions, 0 失败/错误 | 新增 `risk-library-instantiates-escalation-aware-risk`: 选 supply-outage 得 `score=25`/`escalated=true`/`pending`/`steering`/`stage="采购"`/`source_key="supply-outage"`, 未确认自行缓解 409; schedule-delay 得 16/management; tech-uncertainty 得 9 不升级 (无 `escalated` 键); `(:risk_library workspace)` 非空且含 key "supply-outage"; 未知 key 404, 缺 owner 400 |
| 全量 PMS 回归 SQLite | 81 tests / 674 assertions, 0 失败/错误 | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'`, 无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app`, 风险库选用弹窗与"来源"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed (批量 3 passed / 48.1s) | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-c10.spec.js`: 界面"从典型风险库选用"关键物料断供 -> `score=25`/`escalated`/`pending`/`steering`/`source_key=supply-outage`, 台账显"待升级确认 / steering"与"风险库"来源标; 再选技术方案不成熟 -> `score=9`/`escalated=false`; 截图存 `reports/c10/` (c10-1 高风险升级带来源, c10-2 低风险不升级) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现风险模板库的用户自建/编辑与版本化, 未实现跨项目模板共享与治理层集中确认, 未做库条目与具体项目类型/阶段的智能推荐.

边界: 从库实例化只是一条"以精选目录快速登记真实风险"的受控入口, 落库仍是与普通登记同构、参与同一 H08 升级门控与复评/独立关闭链的真实 `risk` 记录; 精选目录为代码内置不可运营, 不等于可配置的风险知识库或生产签收完成. C10 因此标 `implemented / local`.

## H02 沟通节奏标记已沟通与到期预警 (本轮增补, 2026-09-22)

设计与关闭口径: 补齐 H02 沟通计划"沟通节奏执行"这一本地受控事实 (**不声称自动提醒/消息投递已交付**). 领域 `governance.stakeholders` 定义各频率到天数映射 `cadence-days` `{daily 1, weekly 7, biweekly 14, monthly 30, quarterly 90}`. 新增 `log-communication!` (命令 `[:comm-plans :log]`, 路由 `POST /comm-plans/:record_id/log`) 须 `pms:project:edit`, 经 `s/latest!` 仅允许对最新版本记录 (陈旧版本 409), 入参白名单 `[:on :note]` (+version); `on` 缺省取服务器当前日期, 校验不早于上次沟通, 按频率把 `next_date` 顺延 (`on` + cadence 天数), 写 `last_communicated_on/last_communication_note` 并追加 `communication_log` (payload JSON 存储, 无迁移). `comm-plan-read-model` 读取时计算派生字段 `comm_overdue` (`next_date` 早于服务器当前日期) 与 `comm_days_until`, 不写入存储, 不构成主动提醒. 前端沟通计划台账新增"沟通到期"列 (沟通已到期 / N天后沟通 / 未排期) 与首列动作"标记已沟通"入口 (弹窗填沟通日期与纪要). 本轮不做外部通知/邮件/IM 渠道推送与到期自动提醒调度.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 29 tests / 343 assertions, 0 失败/错误 | 新增 `comm-plan-log-advances-next-date-and-flags-overdue`: 周频计划 `next_date` 过期时读模型 `comm_overdue=true`; 标记已沟通后 `next_date` 按 7 天从实际沟通日顺延, `last_communicated_on` 与 `communication_log` 各 1 条; 陈旧版本记录 log 409, 早于上次沟通的日期 400 |
| 全量 PMS 回归 SQLite | 81 tests / 674 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | 0 warnings | 沟通到期列与"标记已沟通"弹窗一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed (批量 3 passed / 48.1s) | `pms-h02c.spec.js`: 造周频计划 `next_date=2026-01-05` -> 台账显"沟通已到期" -> 点"标记已沟通"填 `on=2026-09-22` 与纪要 -> 响应 `next_date=2026-09-29`/`last_communicated_on`/`communication_log` 长度 1 -> 刷新后徽标翻转为"N 天后沟通"; 截图存 `reports/h02c/` (h02c-1 到期, h02c-2 已沟通顺延) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现到期/逾期的自动提醒投递与消息渠道推送, 未实现按日历/工作日的复杂沟通排期 (仅按固定频率天数顺延), 未实现受众分群多渠道差异化沟通记录.

边界: "标记已沟通"记录一次真实发生的沟通并据此受控顺延节奏, 逾期与剩余天数为读取时派生展示, 不代表系统已主动向任何人发出提醒; 沟通计划到会议的闭环仍沿用既有 `POST /comm-plans/:rid/meeting`. H02 维持 `partial` (沟通节奏执行已 `implemented / local`, 自动提醒投递仍待补齐).

## C09 问题逾期与阻断级读模型预警 (本轮增补, 2026-09-22)

设计与关闭口径: 为问题台账补齐只读预警视图, 与行动 `action_overdue` 同构, **不写存储, 不构成主动提醒**. 领域 `governance.collaboration/issue-read-model` 对每条 issue 计算派生字段 `issue_overdue` (存在 `due_date` 且状态非 closed 且到期日不晚于服务器当前日期时为 true) 与 `issue_critical` (`severity="blocker"`); 派生键名省去尾随 `?` 以规避 JSON 序列化歧义 (关键字键会字面序列化为带 `?` 的名称). 前端"风险与问题"问题页签新增"逾期预警"列, 命中阻断级显示红色"阻断级"标签、逾期显示火山色"已逾期"标签, 二者可叠加. 本轮不做逾期自动升级/督办流转与提醒投递, 不改变既有问题重开与独立验证链.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 29 tests / 343 assertions, 0 失败/错误 | 新增 `issue-read-model-flags-overdue-and-blocker`: 造 `due_date` 早于当前日期的 blocker 问题 -> 读模型行 `issue_overdue=true` 且 `issue_critical=true`; 造远期一般问题 -> 两字段均 false; closed 问题不计逾期 |
| 全量 PMS 回归 SQLite | 81 tests / 674 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | 0 warnings | 问题"逾期预警"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed (批量 3 passed / 48.1s) | `pms-c09b.spec.js`: 界面登记 `due=2026-01-10` 的阻断问题 -> 台账显"已逾期"与"阻断级"标记; 远期一般问题无任何标记; 并经真实 HTTP 读模型确认 `issue_overdue`/`issue_critical`; 截图存 `reports/c09b/` (c09b-1 逾期阻断, c09b-2 远期无预警) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现逾期问题自动升级/督办工作流与提醒投递, 未实现按责任人/项目的逾期汇总看板.

边界: 逾期与阻断级仅为读取时派生的界面预警, 不写入存储也不推动任何状态迁移, 问题关闭/重开仍走既有受控命令. C09 因责任人与改派、复评与独立重开等既有子能力叠加本轮逾期预警仍不完整, 维持 `partial`.

## H02 干系人权力-利益象限与未绑定责任人读模型洞察 (本轮增补, 2026-09-23)

设计与关闭口径: 为"干系人识别"台账补齐只读的权力-利益矩阵洞察, **不写存储, 不构成主动提醒**. 领域 `governance.stakeholders/stakeholder-read-model` 对每条 stakeholder 按 `influence`(权力)与 `interest`(利益)派生 `stakeholder_quadrant` (高高=manage-close, 高权力=keep-satisfied, 高利益=keep-informed, 其余=monitor) 与 `stakeholder_unbound` (未绑定 `owner_id` 时为 true); 派生键名省去尾随 `?` 以规避 JSON 序列化歧义. 前端"干系人与沟通"页签新增"管理策略"列, 四象限分别渲染红/橙/蓝/灰标签, 未绑定责任人追加火山色"未绑定责任人"标签. 本轮不做按象限的自动沟通策略下发或提醒投递, 不改变既有干系人登记与不可变修订链.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 32 tests / 361 assertions, 0 失败/错误 | 新增 `stakeholder-quadrant-and-unbound-owner-read-model`: 造四种影响力x关注度组合 -> 读模型分别给出 manage-close/keep-satisfied/keep-informed/monitor; 无 owner_id 的行 `stakeholder_unbound=true`, 有 owner_id 的行为 false |
| 全量 PMS 回归 SQLite | 84 tests / 692 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | 0 warnings | "管理策略"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed (批量 3 passed / 1.0m) | `pms-h02d.spec.js`: 界面"登记干系人"选不同权力-利益组合 -> 台账"管理策略"列显示四象限徽标, 未选责任人的行显示"未绑定责任人", 已绑定行不显示; 并经 GET 读模型二次确认 `stakeholder_quadrant`/`stakeholder_unbound`; 截图存 `reports/h02d/` (h02d-1-quadrants) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现按象限自动生成沟通计划或推送提醒, 未实现权力-利益图的可视化散点图 (当前为台账列形式).

边界: 象限与未绑定标记仅为读取时按当前干系人数据派生的界面洞察, 不写入存储也不推动状态迁移, 干系人登记/修订仍走既有受控命令. H02 核心闭环 (识别+RACI+沟通节奏) 已 `implemented / local`, 本洞察是对其"识别利益相关者及职责"目标的强化, 不改变状态口径.

## H02 RACI执行R职责负载与过载读模型洞察 (本轮增补, 2026-09-23)

设计与关闭口径: 为"RACI职责矩阵"台账补齐只读的职责集中度洞察, **不写存储, 不构成主动提醒**. 领域 `governance.stakeholders/raci-r-loads` 统计每个干系人被指派为执行(R)的活动数, `raci-read-model` 为每条 RACI 行补充 `raci_r_load` (该干系人的 R 总负载) 与 `raci_overloaded` (负载达到阈值 `raci-overload-threshold`=3 时为 true); 负责(A)/咨询(C)/知会(I) 不计入 R 负载. 前端新增"R职责负载"列, 负载为正显示蓝色"执行 R x N"标签, 过载追加红色"职责过载"标签. 本轮不做过载后的自动重分配或提醒投递.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 32 tests / 361 assertions, 0 失败/错误 | 新增 `raci-r-load-and-overload-read-model`: 一名干系人承担 3 条执行R -> 各行 `raci_r_load=3` 且 `raci_overloaded=true`; 另一名仅 1 条 R -> 负载 1 不过载; 只有 A 的干系人负载 0 |
| 全量 PMS 回归 SQLite | 84 tests / 692 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | 0 warnings | "R职责负载"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed (批量 3 passed / 1.0m) | `pms-h02e.spec.js`: 界面"指派RACI职责"为同一干系人在三活动指派执行R -> "R职责负载"列显示"执行 R x 3"与"职责过载"; 另一干系人 1 条 R 显"执行 R x 1"无过载, 其负责A行也显示该人 R 负载 1 且不过载; GET 读模型二次确认 `raci_r_load`/`raci_overloaded`; 截图存 `reports/h02e/` (h02e-1-rac-load) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 阈值 3 为代码内置常量, 未做项目级可调; 未实现过载后跨干系人再平衡建议或通知.

边界: 负载与过载为读取时按当前 RACI 指派派生的界面洞察, 不写入存储也不阻止指派本身 (缺 A/缺 R 冲突仍由既有 `raci_conflicts` 提示). 与 H02 核心闭环同属 `implemented / local` 强化项.

## B05/C07 会议行动闭环计数与逾期读模型洞察 (本轮增补, 2026-09-23)

设计与关闭口径: 为"会议行动"台账补齐只读的行动闭环汇总, **不写存储, 不构成主动提醒**. 领域 `governance.collaboration/enrich-meetings` 按 `meeting_id` 分组会议派生的 action, 为每条 meeting 补充 `meeting_action_total` (行动总数), `meeting_open_actions` (状态非 closed/converted 的未完成数) 与 `meeting_overdue_actions` (其中到期日不晚于服务器当天的未完成数); 复用既有 `action-overdue?` 判定, 已转真实任务(converted)或已关闭(closed)的行动不计入未完成. 前端新增"行动闭环"列: 无行动显示"无行动", 全部闭环显示绿色"行动已全部闭环", 否则金色"未完成 open/total"并在有逾期时叠加红色"逾期 N". 本轮不做逾期行动自动督办或提醒投递.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 32 tests / 361 assertions, 0 失败/错误 | 新增 `meeting-action-closure-counts-and-overdue`: 造一场会议 + 3 条行动 (一条转真实任务 converted, 一条到期日已过, 一条远期) -> 读模型 `meeting_action_total=3`, `meeting_open_actions=2`, `meeting_overdue_actions=1` |
| 全量 PMS 回归 SQLite | 84 tests / 692 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | 0 warnings | "行动闭环"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed (批量 3 passed / 1.0m) | `pms-b05b.spec.js`: 界面"登记项目会议"后逐条"形成行动" (一条到期日 2026-09-15 已过, 一条 2026-10-10 远期) -> 台账"行动闭环"列显示"未完成 2/2"与"逾期 1"; 将远期行动转真实任务后变"未完成 1/2"仍"逾期 1"; 无行动时显示"无行动"; GET 读模型二次确认三个计数字段; 截图存 `reports/b05b/` (b05b-1-open-overdue, b05b-2-converted) |

本轮未执行 (如实记录): MySQL 回归, 本地无可用 MySQL 实例, 待有环境时补跑. 未实现逾期行动的自动到期提醒/督办工作流, 未实现按责任人或跨会议的逾期行动汇总看板.

边界: 闭环计数为读取时按当前行动状态与服务器日期派生的界面洞察, 不写入存储也不推动行动状态迁移, 行动完成/核验/转任务仍走既有受控命令. C07 因自动到期提醒仍缺, 维持 `partial`; 本洞察是"会后行动追踪"的界面强化, 不改变状态口径.

## H18 需求/文档/干系人受控软作废与受控恢复 (本轮增补, 2026-09-23)

设计与关闭口径: 把"这条记录不再有效"表达为一次**可审计的状态迁移**(`discarded`), 而不是物理删除; 保留内容, 编号与既有版本链, 供追溯. 新领域 `governance/lifecycle.clj` 暴露 `discard!`, 由 governance 以 `approval-command lifecycle/discard! "<kind>" false` 注册为 `[:requirements :discard]`, `[:documents :discard]`, `[:stakeholders :discard]` 三条命令, 路由 `POST /<collection>/:record_id/discard`. 三层门控按序: `latest!` 只允许最新版本(陈旧 409), `status!` 只允许可作废状态(requirement=`registered`, document=`registered`/`rejected`, stakeholder=`active`, 否则 409; 提交进入 `in_review` 的文档因此不可直接作废), 再收集引用证据(requirement 被追踪指向, document 被追踪 target/会议会前 material_ids/问题与风险 evidence_ids/行动 evidence_ids 引用, stakeholder 被 RACI stakeholder_id 或沟通受众引用), 命中即 409 并在消息里列出前若干来源. 通过后 `change!` 写 `discarded` 并记 `discard_reason`/`discarded_by`/`discarded_on` + 追加 `workflow_history` 审计项(含作废前状态). 命令走 `pms:project:edit` 写权限与项目作用域, 无编辑权 403, 未知字段 400. 对称地, `restore!` 注册为 `[:requirements :documents :stakeholders :restore]` 三条命令实现受控撤销作废: 仅对最新版本且状态为 `discarded` 的记录可恢复(否则 409), 从 `workflow_history` 最近一条 `discarded` 审计项读回作废前状态, `change!` 退回该状态并追加 `restored` 审计项(含 `restored_to`), 记 `restore_reason`/`restored_by`/`restored_on`; 因 `discarded` 已在本轮迁移的状态 CHECK 内, 恢复不需新迁移.

因 `pms_gov_record` 状态 CHECK 约束原不含 `discarded`, 本轮新增整表重建迁移 `202609220011-gov-status-discard`(SQLite + MySQL 各 up/down): SQLite 不能 ALTER CHECK, 按 `PRAGMA foreign_keys=OFF` -> 建新表(状态 CHECK 增列 `discarded`) -> `INSERT...SELECT` -> `DROP` 旧表 -> `ALTER...RENAME` -> 重建索引 -> `PRAGMA foreign_keys=ON`; MySQL 采用建表 -> 拷贝 -> `DROP` -> `RENAME` -> 建索引. 该表仅有指向 `pms_project` 的出向外键, 无入向外键引用, 重建安全.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 迁移 | SQLite 全新空库迁移成功含 `discarded` 状态 | down 迁移回退原状态集合并过滤 discarded 行 |
| 治理命名空间 SQLite | 36 tests / 390 assertions, 0 失败/错误 | 新增 `requirement-discard-is-soft-and-reference-guarded`(viewer 403 + 未知字段 400 + 作废→`discarded` 带 reason/by/on + workspace 回显 + 重复 409 + 被追踪引用 409), `document-discard-rejects-referenced-and-non-discardable-status`(会议资料引用 409 + 独立文档作废 + `in_review` 状态门控 409), `stakeholder-discard-rejects-referenced-record`(RACI 引用 409 + 独立作废 + 作废后再被 RACI 引用命中 active 守卫 409), `discarded-records-can-be-restored-with-audit`(requirement 作废→恢复到 `registered` 带 restore_reason/restored_by/restored_on + workflow_history 追加 `discarded`->`restored` 审计且 `restored_to` + viewer 403 + 非 discarded 恢复 409 + 未知字段 400; document 作废→恢复; stakeholder 作废→恢复 `active` 后可再被 RACI 引用) |
| 全量 PMS 回归 SQLite | 88 tests / 721 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | "作废"/"恢复"按钮, `discard-dialog`/`restore-dialog`, 状态列"已作废"与发布标签一并编译 |
| Chrome 浏览器 (Playwright) | 2 passed | `pms-h18.spec.js`: 证据文档页签未被引用文档点"作废"->"已作废"且入口消失, 被会议会前资料引用文档 409 拒绝并在弹窗错误面板显示"记录仍被其它对象引用, 不能作废"且仍"已登记", 已作废文档再作废经真实 HTTP 命中状态守卫 409; URS与干系人页签未被引用需求/干系人作废成功, 被追踪需求与被 RACI 指派干系人被拒且仍"已登记/有效"; 截图存 `reports/h18/` (h18-1..h18-5) |
| Chrome 浏览器 (Playwright) | 2 passed | `pms-h18b.spec.js` (受控恢复): 证据文档页签作废后"恢复"入口出现且"作废"入口消失, 点"恢复"填原因->状态回到"已登记"且"作废"入口复现, 经真实 HTTP 确认 `status=registered` + `restore_reason`/`restored_by` + `workflow_history` 为 `['discarded','restored']` 且 `restored_to=registered`; 干系人作废→"已作废"→恢复→"有效", 需求作废/恢复靠入口翻转+HTTP 核实; 截图存 `reports/h18b/` (h18b-1..h18b-4) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例), 状态 CHECK 表重建迁移仅经 SQLite 往返实证, MySQL 版待有环境时补跑并做升级/回退演练.

边界: H18 原目标是"草稿清理与资料保留策略"整行, 本轮交付其中"不把取消当删除, 软作废需权限/引用校验, 作废行为可审计"以及对称的"受控撤销作废 (恢复)"这一子集, 故矩阵记 `partial`. 恢复由 `lifecycle/restore!` 注册为 `[:requirements :documents :stakeholders :restore]` 三条命令, 路由 `POST /<collection>/:record_id/restore`, 同样走 `pms:project:edit` 权限+项目作用域+未知字段 400, 三层门控 `latest!`(陈旧 409)+`status!`(只允许 `discarded`, 否则 409), 从 `workflow_history` 最近一条 `discarded` 审计项取回作废前状态并 `change!` 退回, 追加 `restored` 审计项(含 `restored_to`), 记 `restore_reason`/`restored_by`/`restored_on`; 免迁移(`discarded` 已在状态 CHECK 内). 级联影响预览与把已作废文档从 `document_collection` 归集口径中剔除已在紧随其后的 H18c 增量交付 (见下节). 仍缺: 正式历史按保留策略归档, 已作废证据对历史 Gate/验收快照的显式标注, 生产验收. 界面在记录 `discarded` 时隐藏作废入口改为"恢复"入口, 恢复后状态回退并复现作废入口, 记录全程可见.

## H18c 文档归集剔除已作废与级联影响只读预览 (本轮增补, 2026-09-23)

设计与关闭口径: 补齐 H18 遗留的两项"作废即生效于全局口径"的闭环. (a) 文档归集视图 `document-collection` 改为按每个编号最新版本且状态非 `discarded` 聚合, 已被受控作废的最新版本编号不计入 `total` 及各分桶, 而单独以 `discarded-count` 透明呈现, 前端"文档归集视图"据此显示红色"已作废 N 未计入"标签, 恢复后重新计入. (b) 新增只读级联影响预览 `lifecycle/discard-preview`, 走 `k/read!` + `pms:project:query` 读权限与项目作用域 (无读取权 403, 未知记录 404), 复用与作废守卫同一套 `references-of` 引用收集逻辑, 返回 `{kind, record_id, code, revision, status, latest?, status_discardable?, references, discardable?}`, 不写入不改任何状态; 注册为 `[:requirements :documents :stakeholders :discard-preview]` 三条命令, 路由 `GET /<collection>/:record_id/discard-preview`, 前端在需求/证据文档/干系人行内提供"级联影响"按钮打开只读弹窗呈现"可安全作废"或"不可作废"并列出引用清单. 两项均免迁移 (纯读派生, `discarded` 状态已在 CHECK 内).

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 38 tests / 414 assertions, 0 失败/错误 | 新增 `document-collection-excludes-discarded-latest-versions`(登记三份不同密级/阶段/结构节点文档 -> 归集 total 3 discarded-count 0; 作废机密件 -> total 2 discarded-count 1 且该密级归零, by-stage/by-structure-node 不再含其分桶; 恢复 -> 回到 total 3 discarded-count 0) 与 `discard-preview-reports-guards-without-mutating`(未被引用需求 `discardable?` true; 被追踪指向的需求/被追踪引用的文档 `discardable?` false 且 `references` 命中对应来源; 预览调用后 workspace 状态不变; 已作废记录 `status_discardable?` false; 未知记录 404; 无读取权 actor 403) |
| 全量 PMS 回归 SQLite | 90 tests / 745 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | 归集"已作废 N 未计入"标签, `discard-preview-modal` 只读弹窗, 三处"级联影响"按钮一并编译 |
| Chrome 浏览器 (Playwright) | 3 passed | `pms-h18c.spec.js`: 证据文档页签登记两份不同密级/阶段/结构文档 -> 归集"最新版本证据 2"无"已作废"标签; 未被引用文档"级联影响"弹窗显示"可安全作废"+"未发现引用该记录的其它对象"; 作废后归集降到"最新版本证据 1"并出现红色"已作废 1 未计入", 真实 HTTP GET 回显 `document_collection.discarded-count=1` 且对应密级归零; 被会议会前资料引用的文档"级联影响"显示"不可作废"并列出"会议 <标题>", 关闭后记录仍"已登记"(预览只读); 被 RACI 指派干系人"级联影响"显示"不可作废"并列出"RACI <活动>"; 截图存 `reports/h18c/` (h18c-1..h18c-5) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 但 H18c 两项均免迁移, 不新增 DDL); 级联预览与真实作废之间的并发窗口未做专门压测.

边界: H18c 交付的是"作废口径一致性"(归集剔除)与"作废前可预检"(级联影响只读预览), 仍属 H18 整行的子集, 故 H18 记 `partial` 不变. 预览为只读提示, 与作废命令共用引用守卫口径, 但并发下实际作废仍可能命中守卫 409; 仍缺正式历史归档策略, 已作废证据对历史 Gate/验收快照的显式标注与生产验收.

## C09d 问题阻断级自动升级与独立确认解除提交解决门控 (本轮增补, 2026-09-23)

设计与关闭口径: 把 H08 风险超阈值自动升级的门控套路复用到问题闭环. `create-issue!` 在 `severity = blocker` 时登记即自动升级, 写入 `escalated: true` 与 `escalation_state: pending`, 按登记时是否已逾期给出 `escalation_level`(到期日早于或等于服务端当天为 steering 管理层, 否则 management 经理层)与可读 `escalation_reason`; 非阻断级不写任何升级键, 保证既有 major/minor 用例不回归. 新增独立确认命令 `acknowledge-issue-escalation!` 走 `k/mutate!` 的 `{:write? false}` 只读范围审批人也能确认(同 H08 与 verify!), 自写"登记人不得自确认"职责分离校验(403, 因 `s/reviewer!`/`decision-actor!` 需 reviewer_id/submitted_by 不适用自动升级), 未升级或重复确认返回 409, decision enum `approved|rejected` 转为 `acknowledged|waived` 并记录 `escalation_ack_by/note/on` 与 `workflow_history`. `resolve!` 增加门控: 当 `escalated` 且 `escalation_state` 仍为 pending 时返回 409, 须先经独立确认方可提交解决. 注册为 `[:issues :escalate]`, 路由 `POST /issues/:record_id/escalate`. 前端"问题闭环"台账新增只读"超阈值升级"徽标列(待升级确认 / level, 升级已确认, 升级已豁免, 未触发), 并在审批人视角(具备 `pms:quality:approve` 且非登记人)提供"确认升级处置"入口打开决策弹窗. 全程免迁移(升级字段随 payload JSON 持久化).

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 39 tests / 437 assertions, 0 失败/错误 | 新增 `issue-escalation-requires-independent-acknowledgment-before-resolution`(blocker 登记即 `escalated`/pending/management 且 reason 非空; major 不写升级键; pending 时 resolve 409; 未升级 escalate 409; 登记人自确认 403; 非法 decision 400; 9302 独立批准 -> acknowledged/open/ack_by 9302/workflow_history 含 escalation_acknowledged; 重复确认 409; 确认后可 resolve 进入 in_review; 逾期 blocker 升级到 steering, 独立驳回 -> waived 后仍可 resolve). 既有 `closed-issue-reopens-only-through-independent-review` 因新门控在首次 resolve 前补一步独立确认, workflow_history 断言由 4 调整为 5(升级确认新增一条) |
| 全量 PMS 回归 SQLite | 91 tests / 768 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | "超阈值升级"徽标列, `issue-escalation-dialog` 决策弹窗, "确认升级处置"入口一并编译 |
| Chrome 浏览器 (Playwright) | 2 passed | `pms-c09d.spec.js`: 界面登记 blocker 问题 -> "超阈值升级"列显示红色"待升级确认 / management", 登记人看不到"确认升级处置"入口, 真实 HTTP resolve 409、自确认 escalate 403; 独立审批人第二上下文"确认升级处置"选"确认升级并责成处置" -> 徽标翻转"升级已确认"、确认人为审批人, 此后 resolve 放行 200 进入 in_review; 另一例逾期 blocker 升级到 steering, major "未触发"且无需独立升级确认即可径直 resolve 200; 截图存 `reports/c09d/` (c09d-1..c09d-5) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 但 C09d 免迁移, 不新增 DDL); 由风险 `materialize` 生成的问题暂不自动升级(避免与既有 materialize 用例回归), 逾期后对已登记问题的追溯升级与提醒投递未实现.

边界: C09d 交付的是"阻断级问题登记即升级 + 独立确认解除提交解决门控"这一条最小闭环, 与 H08 风险升级门控同构, 属 C09 整行子集, 故 C09 记 `partial` 不变. 升级判定只在登记时按严重度与到期日计算一次, 不做后续追溯重算; 升级通知投递与跨项目问题汇总升级仍待实现.

## 责任人跨类负载只读洞察 (本轮增补, 2026-09-23)

设计与关闭口径: 延续 H02 三项只读治理洞察(干系人象限/RACI负载/会议闭环)的"给治理台账加免迁移派生列"套路, 新增跨问题/风险/行动三张台账的"责任人负载"只读洞察. 领域层纯函数 `collaboration/owner-workloads` 对同一 `owner_id` 统计其在三类中当前未关闭的事项总数(问题与风险排除 `closed`, 行动排除 `closed`/`converted`, 与既有逾期/闭环口径一致), `owner-workload-read-model` 对每条记录 `assoc` `owner_open_load`(整数)与 `owner_overloaded`(负载达到阈值 4 时为 true); 无 `owner_id` 的行负载 0 且不过载. workspace 里先算一次跨类 `owner-loads`, 再在结果层对 `:issues`, `:risks`, `:actions` 三个数组分别 `update` 补充派生键, 使同一责任人在三张台账回显相同的负载数. 前端"风险与问题"页签(risk-section/issue-section)与"会议行动"页签(action-section)各新增一列只读"责任人负载", 用蓝色标签显示"未关闭 x N", 过载时追加红色"负载过重"标签. 全程免迁移, 免新命令, 免新状态值(纯读取时计算, 不落库不投递), 派生键去尾随 `?` 以原样序列化到 JSON.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 40 tests / 450 assertions, 0 失败/错误 | 新增 `owner-workload-aggregates-open-items-across-kinds`(同一责任人 9301 跨 2 问题+1 风险+1 行动共 4 项未关闭 -> 三张台账每行 `owner_open_load` 均为 4 且 `owner_overloaded` true; 另一责任人 9303 单问题负载 1 不过载; 独立关闭其中一条问题 -> 9301 降到 3 且解除过载; 行动转真实任务 converted -> 再降到 2 证实 converted 被剔除; 直接对纯函数传无 `owner_id` 行 -> 负载 0 且不过载). 全量 PMS 无回归 |
| 全量 PMS 回归 SQLite | 92 tests / 781 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | `owner-load-column` 复用列, risk/issue/action 三处一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-g-load.spec.js`: 同一责任人 admin 界面登记 2 条问题+1 条风险并经真实 HTTP 挂 1 条会议行动 -> "责任人负载"列在"风险与问题"与"会议行动"两张台账一致回显跨类"未关闭 x 4"并出现红色"负载过重"; 界面点该行动"转为WBS任务"后三台账同步降到"未关闭 x 3"且"负载过重"消失; 真实 HTTP GET governance 回显三台账 admin 行 `owner_open_load=3`; 截图存 `reports/g-load/` (g-load-1..g-load-3) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 但本洞察完全免迁移, 不新增 DDL); 阈值 4 为固定代码常量, 未做项目级可调阈值; 未接通按负载自动重分配或提醒投递.

边界: 责任人跨类负载是"识别一人被集中指派"的只读预警列, 与规划域基于日历容量的资源超配保护(H05)口径不同, 不替代资源容量冲突检测; 负载只在读取时按当前未关闭事项计算, 不持久化, 不构成主动通知. 该洞察横跨 C07/C09/C10 三行, 记为对 C07(行动台账负载可见)的增强, 相关行 `partial` 状态不变.

## 问题与行动到期倒计时只读洞察 (本轮增补, 2026-09-23)

设计与关闭口径: 延续"给治理台账加免迁移派生列"套路, 把 C09 问题台账与 C07 会议行动台账已有的二元"逾期预警"细化为一条只读"到期倒计时"列. 领域层新增纯函数 `collaboration/days-until`(以 `java.time.LocalDate` 计算到期日相对服务器当天的剩余天数, 负值表示已逾期天数, 空日期返回 `nil`)与常量 `due-soon-days`(3, 未决事项剩余 1..3 天视为临期). `issue-read-model` 与 `action-read-model` 各在原 `assoc` 中补充 `issue_due_in_days`/`action_due_in_days`(整数剩余天数, 已完成/已关闭/已转真实任务或未填到期日时为 `nil`), 以及 `issue_due_soon`/`action_due_soon`(剩余天数落在 `1..due-soon-days` 时为 true). 逾期/临期与既有 `*_overdue` 用同一"服务器当天 + 排除 closed(行动另排除 converted)"口径, 只是把结论从是否逾期细化到还剩几天. 派生键一律去尾随 `?` 以原样序列化到 JSON. workspace 无需改动(单 kind 读模型新增字段自动透传). 前端"风险与问题"页签的问题台账与"会议行动"页签的行动台账各新增一列只读"到期倒计时": 逾期红色"已逾期 N 天", 今天到期橙色"今天到期", 临期金色"剩 N 天临期", 尚远蓝色"剩 N 天", 已完成或无到期日显示灰色短横. 全程免迁移, 免新命令, 免新 kind, 免新状态值(纯读取时计算, 不落库不投递).

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 41 tests / 467 assertions, 0 失败/错误 | 新增 `issue-and-action-due-countdown-flags-remaining-days`(相对服务器当天登记 +30 远期 / +2 临期 / -5 逾期的问题 -> `issue_due_in_days` 分别 30/2/-5, `issue_due_soon` 仅 +2 为 true, `issue_overdue` 仅 -5 为 true; +2 天行动同样临期; 远期行动"转真实任务"后 `action_due_in_days` 转 `nil`、`action_due_soon`/`action_overdue` 转 false; 问题经 resolve+独立审批关闭后 `issue_due_in_days` 转 `nil`、`issue_due_soon` 转 false). 全量 PMS 无回归 |
| 全量 PMS 回归 SQLite | 93 tests / 798 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | `due-countdown-column` 复用列, 问题与行动两处一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-due.spec.js`: 界面登记约 +2/+30/-5 天到期的三条问题 -> 问题台账"到期倒计时"列分别显示"剩 N 天临期"/"剩 N 天"/"已逾期 N 天"; 真实 HTTP GET governance 回显 `issue_due_soon`/`issue_due_in_days`/`issue_overdue` 同口径落在预期窗口; HTTP 挂一条约 +2 天到期的会议行动 -> 行动台账也显示"剩 N 天临期"; 界面点该行动"转为WBS任务"后倒计时列不再显示临期/逾期且回显 `action_due_in_days=null`/`action_due_soon=false`; 断言按类别正则与剩余天数窗口取值以免疫 ±1 天服务器/浏览器日期漂移; 截图存 `reports/due/` (due-1-issue-countdown/due-2-action-countdown/due-3-after-convert) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 但本洞察完全免迁移, 不新增 DDL); 未接通按剩余天数自动派发提醒(C11 通知/预警仍为 planned); 风险台账的到期倒计时暂不在本轮范围(风险已有独立复审到期口径).

边界: 到期倒计时是把既有二元逾期标记细化的只读洞察, 只在读取时按服务器当天计算剩余天数, 不持久化、不构成主动通知; 与 C11"到期提醒投递"是不同能力, 不据此宣称通知闭环. 该洞察横跨 C07(会议行动)与 C09(问题)两行, 记为对二者的增强, 相关行 `partial` 状态不变.

## 风险复审到期倒计时只读洞察 (本轮增补, 2026-09-23)

设计与关闭口径: 把上一轮"到期倒计时"只读洞察从问题/行动台账扩展到 H08 项目风险台账, 沿用同一列组件与阈值口径, 但到期基准换成风险复审到期日. `reviews/risk-read-model` 内以私有副本 `days-until`(与 `collaboration/days-until` 同算法, 独立定义以避免 `collaboration` 依赖 `reviews` 造成的循环引用)按服务器当天计算剩余天数, 到期日取 `review_due_date`(不存在时回落到风险自身 `due_date`, 与既有 `review_overdue` 完全同源同基准); 新增 `review_due_in_days`(整数剩余天数, 负=已逾期, 无日期=`nil`)与 `review_due_soon`(剩余落在 `1..review-due-soon-days`(3) 为 true), 二者在状态为 `closed` 时给出 `nil`/false, 与 `review_overdue` 一致. 关键生命周期语义: `review_due_date` 仅在复审经独立审批通过(`decision approved`)后由 `approved-risk-patch` 改写为 `next_review_date`; 未进入复审前以 `due_date` 为准, 关闭后倒计时归零. 派生键去尾随 `?`. workspace 无需改动(单 kind 读模型新字段自动透传). 前端"风险与问题"页签风险台账复用 `due-countdown-column`(标题"到期倒计时", 传入键 `review_due_in_days`), 与相邻"下次复评"/"复评提醒"两列共同表达复审到期. 全程免迁移, 免新命令, 免新 kind, 免新状态值.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 42 tests / 482 assertions, 0 失败/错误 | 新增 `risk-review-due-countdown-flags-remaining-days`(三条 2x3=6 不触发升级的风险登记 +30/+2/-5 天到期 -> `review_due_in_days` 分别 30/2/-5, `review_due_soon` 仅 +2 为 true, `review_overdue` 仅 -5 为 true; 对 +2 风险提交复审并独立审批通过后 `review_due_date` 改写为 `next_review_date`, 倒计时随之后顺延仍临期; 关闭风险后 `review_due_in_days` 转 `nil`、`review_due_soon` 转 false). 全量 PMS 无回归 |
| 全量 PMS 回归 SQLite | 94 tests / 813 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | 复用 `due-countdown-column`, 风险台账新增一列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-rrc.spec.js`(隔离 `:3100` 后端, 独立空库): 界面登记约 +2/+30/-5 天到期的三条风险 -> 风险台账"到期倒计时"列分别显示金色"剩 N 天临期"/蓝色"剩 N 天"/红色"已逾期 N 天"; 真实 HTTP GET governance 回显 `review_due_in_days`/`review_due_soon`/`review_overdue` 同口径落在预期窗口; 标题避开"临期/逾期/剩"子串防 getByText 严格模式误命中; 断言按类别正则 + 剩余天数窗口取值免疫 ±1 天日期漂移; 截图存 `reports/rrc/` (rrc-1-risk-review-countdown) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本洞察完全免迁移, 不新增 DDL); 复审通过后倒计时的"重新计算并界面二次翻转"未在浏览器 E2E 内独立复现(需第二个独立审批人上下文), 该重算路径由治理测试 `risk-review-due-countdown-flags-remaining-days` 确定性地覆盖; 未接通按剩余天数自动派发复审提醒(C11 通知/预警仍为 planned).

边界: 风险复审到期倒计时是"到期倒计时"只读洞察在风险台账的同口径延伸, 以复审到期日(回落 `due_date`)为基准, 只读取时计算, 不持久化、不构成主动通知, 不替代 H08 的评分超阈值升级门控; 与 C11"到期提醒投递"是不同能力. 记为对 H08(风险复审可见性)的增强, H08 相关 `partial` 状态不变.

## 风险与问题双向来源关联只读洞察 (本轮增补, 2026-09-23)

设计与可见性口径: 兑现 C10"风险实现转问题保留关联"的界面可见性. `POST /risks/:rid/materialize` 早已把双向关联 ID 落进记录 payload (问题侧 `source_risk_id`, 风险侧 `issue_id`), 二者在 `(dissoc % :content)` 后仍随 `risks`/`issues` 数组回显, 但工作台两张台账此前只呈现各自的标题, 无法一眼看出"这条问题由哪条风险转出 / 这条风险已转出哪条问题". 本轮不新增迁移、不新增命令、不新增 kind、不改动 `materialize` 幂等语义, 只在 workspace 结果聚合层追加一次纯读标注 `collab/enrich-risk-issue-links`: 以 `:id` 建风险/问题双向索引后, 对命中来源的问题补 `issue_source_risk_id` 与 `issue_source_risk_title`(来源风险标题), 对命中转出记录的风险补 `risk_issue_id` 与 `risk_issue_title`(转出问题标题); 手工登记问题(无 `source_risk_id`)与未转出问题(无 `issue_id`)原样不写这些键, 对端缺失时标题回落 `nil`. 派生键去尾随 `?` 以稳定 JSON 序列化. 前端"风险与问题"页签问题台账新增"来源风险"列(geekblue 标签回显来源风险标题, 无来源显示灰字"手工登记"), 风险台账新增"转出问题"列(cyan 标签回显转出问题标题, 未转出显示灰字"未转出"), 用 antd 标签色 class 区分同页签两表间的同名歧义.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 治理命名空间 SQLite | 43 tests / 492 assertions, 0 失败/错误 | 新增 `risk-issue-bidirectional-source-link-is-surfaced`(登记 3x5=15 风险(不触发超阈值升级, 可直接从 open 转出)+ 2x3=6 常规风险, `materialize` 生成带 title 的问题, 另手工登记一条独立问题 -> workspace 回显: 转出问题 `issue_source_risk_id`=风险 id 且 `issue_source_risk_title`="供应商交付风险", 源风险 `risk_issue_id`=问题 id 且 `risk_issue_title`="到货延迟整改"; 手工问题 `issue_source_risk_id`/`title` 均 `nil`, 未转出风险 `risk_issue_id`/`title` 均 `nil`). 全量 PMS 无回归 |
| 全量 PMS 回归 SQLite | 95 tests / 823 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | 问题/风险台账各新增一列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-ri.spec.js`(隔离 `:3100` 后端, 独立空库): 界面登记一条 3x5 风险并"风险发生,转问题"生成一条标题不同于风险的问题, 再手工登记一条独立问题 -> 问题台账"来源风险"列对该转出问题显示 geekblue 标签=风险标题、对手工问题显示"手工登记", 风险台账"转出问题"列对该风险显示 cyan 标签=转出问题标题; 真实 HTTP GET governance 回显 `issue_source_risk_id`/`issue_source_risk_title`/`risk_issue_id`/`risk_issue_title` 与手工记录的 `nil` 均落在预期; 转出问题标题与风险标题故意取不同文案、并以标签色 class 定位规避同页签两表 getByText 严格模式误命中; 截图存 `reports/ri/` (ri-1-bidirectional-source-link) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本洞察完全免迁移, 不新增 DDL); 关联 ID 的实际写入由既有 `materialize` 命令与其回归用例保证, 本轮只在读取层补标题, 未新增独立"关联变更/解绑"命令; 未接通转出后向责任人自动派发提醒(C11 通知仍为 planned).

边界: 双向来源关联只读洞察只把 `materialize` 已持久化的关联 ID 在读取时互相标注对方标题, 供两张台账可见, 不写入存储、不新增迁移、不构成通知; 关联本身仍是 `materialize` 的既有幂等副作用. 记为对 C10"风险实现转问题保留关联"可见性的增强, C10 保持 `implemented / local`, 与其相关的横切 `partial` 口径不因这一子能力上行.

## H08 风险复评重新评分并重算升级门控 (本轮增补, 2026-09-23)

设计与关闭口径: 兑现矩阵 H08 长期列为"复评后重新评分仍待实现"的一环, 把上一轮"到期倒计时/双向来源关联"等只读洞察推进为一条真正的命令驱动闭环, 复用既有 `risk` kind 与整条复评/独立审核链, **无新增迁移** (提议与重算字段随 payload JSON 存储). 为避免 `collaboration`(登记/库实例化)与 `reviews`(复评)相互 `require` 造成循环引用, 把评分与升级判定的纯函数(`score!`, `escalation-threshold`=16, `escalation-level`, `escalation-reason`, `assessment`)抽取到新命名空间 `governance.risk-assessment`(仅依赖 `rules`), 供登记, 库实例化与复评重算三方共用, `collaboration/insert-risk!` 改为调用 `ra/assessment`. `submit-risk-review!` 白名单新增可选 `:probability` 与 `:impact`: 二者皆空视为不重评(既有复评用例不受影响); 只填其一返回 400; 越界(非 1..5)返回 400; 成对提供则以 `review_proposed_probability`/`review_proposed_impact`/`review_proposed_score` 暂存为待批准提议, **不改动现有 `score` 与升级状态**(提交只是提议). `decide-risk-review!` 在批准且结论非关闭且存在提议时按新概率×影响重算 `score` 并重新判定同一套 H08 超阈值升级门控(达阈值重新置 `escalated`/`pending`/层级, 未达阈值则清理 escalation 键), 无论批准或拒绝都清理 `review_proposed_*` 提议键, 拒绝或关闭维持原评分. 派生/提议键一律去尾随 `?` 以原样序列化到 JSON. 前端 `risk-review-dialog` 新增两个选填 `:number` 概率/影响输入(带"留空维持原评分, 填一项须同时填另一项"提示), 风险台账新增只读"复审重评"列, 处于 in_review 且有提议时以橙色标签回显"旧评分 → 新提议 待批准", 批准或清理后显示灰色短横.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 44 tests / 519 assertions, 0 failures | 新增 `risk-review-rescore-recomputes-escalation-gate`: 上升重评(3x5=15 风险提议 5x5=25, 批准前回显 `review_proposed_score=25` 而 `score` 仍 15 且 `escalated` false, 批准后 `score=25`/`escalated=true`/`pending`/`steering` 并重新门控缓解 409, 独立确认后放行缓解), 下降重评(5x5 升级确认后复评降至 1x1, 批准后 `escalated=false` 且 escalation 键清空), 只填一项/非法概率 400, 拒绝重评评分不变且提议键清理 |
| 全量 PMS 回归 (CLI SQLite) | 96 tests / 850 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有复评/升级/到期用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-rs.spec.js` (隔离 `:3100` 后端, 独立空库): 界面登记 3x5=15 风险->"提交复评"填新概率/影响 5/5 并指定独立审批人+证据->"复审重评"列回显"15 → 25 待批准"且"超阈值升级"仍"未触发", 真实 HTTP 回显 `review_proposed_score=25`/`score=15`/`escalated=false`; 独立审批人第二上下文以本人 token 真实 HTTP `decision approved`->评分落定 25 并自动重触发"待升级确认 / steering", "复审重评"列清空为"—", GET governance 二次确认 `score=25`/`escalated=true`/`escalation_state=pending`/`review_proposed_score` 已清理; 截图存 `reports/rs/` (rs-1-proposed-rescore, rs-2-approved-escalated) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 批准后的进一步"确认升级处置并解除缓解门控"由既有 H08 用例 `risk-escalation-requires-independent-acknowledgment-before-mitigation` 覆盖, 本增量用例只证明复评重算这一环; 未接通重评/升级后的通知投递, 未做跨项目风险汇总升级.

边界: 复评重新评分是 H08 复审链的一个子能力闭环(提议->独立批准->重算并重新判定升级门控), 免迁移不落新 kind; 但 H08 行仍含"升级通知投递""跨项目风险汇总升级"等未完成子项, 故 H08 保持 `partial`, 不因这一子能力上行.

## H01 章程显式授权项目经理字段 (本轮增补, 2026-09-23)

设计与关闭口径: 兑现矩阵 H01 长期列为"授权PM作为章程显式字段仍待补齐"的一环. 复用既有 `charter` kind 与整条 create/revise/submit/decide 独立审批链, **无新增迁移** (授权 PM 字段随内容版本 payload JSON 存储). 沿用上一轮 `initial_budget` 的"免迁移给治理 kind 加可选强类型字段"套路: 在 `governance.approval` 新增 charter 专属白名单向量 `charter-pm-fields` = `[:authorized_pm_id]`, 并入 `content!` 的 `allowed` 计算; 新增私有 `charter-pm!` 校验器, 对 `body` 中出现的 `authorized_pm_id` 走 `s/user!` (须为有效本地用户, 不存在或已停用返回 400), 未填时返回 nil 并在 `cond->` 里 `merge` 为 no-op, 因此既有章程用例(不含该字段)不受影响. 字段随内容版本不可变冻结, 修订派生新版本而旧版本授权 PM 不漂移; 与预算同为章程专属字段, 出现在变更申请体上按白名单返回 400. 留空时不写入该键, 项目经理仍由项目 `manager_id` 隐含承载. 前端 `charter-dialog` 新增"授权项目经理"可选下拉(复用 `user-options`), `transform` 把空串归一 dissoc; 章程台账新增"授权PM"列, 有值时按 `user_id` 匹配回显用户姓名, 无值显示"未指定". 本轮不据该字段自动改写项目编辑/审批权限或触发通知投递.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 45 tests / 528 assertions, 0 failures/errors | 新增 `charter-authorized-pm-is-explicit-validated-and-versioned`: 建章程填 `authorized_pm_id` 9303 回显并存储, 独立批准后授权 PM 不漂移, 修订至 9301 生成 rev2 而旧版本仍 9303, 未填时不含该键, 不存在人员 99999 返回 400, 合法变更体追加 `authorized_pm_id` 按白名单返回 400 |
| 全量 PMS 回归 (CLI SQLite) | 97 tests / 859 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有章程预算/审批/版本用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, 章程授权 PM 表单字段与台账"授权PM"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-h01pm.spec.js` (隔离 `:3100` 后端, 独立空库): 界面"编制项目章程"用"授权项目经理"下拉显式选人 -> 命令响应回显 `authorized_pm_id` 且 `revision=1` (截图 h01pm-1-dialog-select.png) -> HTTP 修订链改授权对象至 admin 生成 rev2 -> 再修订取消授权生成 rev3 `authorized_pm_id` 为空 -> GET governance 校验三不可变版本授权 PM 不漂移 -> 重进台账"授权PM"列对选人版本回显用户姓名, 未选版本显示"未指定" (截图 h01pm-2-ledger-column.png) -> 真实 HTTP 不存在人员作授权 PM 返回 400 且变更体追加该字段被白名单拒 400; 截图存 `reports/h01pm/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 未把授权 PM 字段与项目实际编辑/审批权限联动, 未接通授权后的通知投递; 未做初始预算与批准后财务基线的对账关联(仍属 H01 其它待补齐子项).

边界: 显式授权项目经理是 H01 章程"记录目标/范围/成功标准/赞助人/授权PM/初始预算"口径中"授权PM"一项的 `implemented / local` 落地(可选强类型字段 + 有效用户校验 + 版本冻结不漂移 + 章程专属白名单); 但 H01 行仍含"授权PM与权限/通知联动""预算-财务基线对账"等未完成子项, 故 H01 保持 `partial`, 不因这一子能力上行.

## H09 变更量化影响与高影响只读派生 (本轮增补, 2026-09-23)

设计与关闭口径: 兑现矩阵 H09"对范围/工期/成本/质量/资源做影响分析"中"量化影响"的一环. 复用既有 `change` kind 与整条 create/revise/submit/decide 独立审批链, **无新增迁移** (量化字段随内容版本 payload JSON 存储). 沿用"免迁移给治理 kind 加可选强类型字段"套路: 在 `governance.approval` 新增 change 专属白名单向量 `change-impact-fields` = `[:schedule_impact_days :cost_impact_amount]`, 并入 `content!` 的 `allowed` 计算; 新增私有 `change-impact!` 校验器: `schedule_impact_days` 须为 0 至 3650 的整数 (非整数, 负数或超范围返回 400), `cost_impact_amount` 复用 `finance-money/amount!` 规范化为两位小数最小单位并要求非负 (负数或超两位小数返回 400); 未填时对应键不写入, `cond->` 里 `merge` 空 map 为 no-op, 因此既有变更用例(不含量化字段)不受影响. 字段随内容版本不可变冻结, 修订派生新版本而旧版本量化值不漂移; 与授权 PM 相反, 量化字段是变更专属, 出现在章程体上按白名单返回 400. 高影响判定为**只读派生**: `change-read-model` 在读取时按 `schedule_impact_days >= 10` 或 `cost_impact_amount >= 100000.00` 计算 `change_high_impact` 布尔, 不落存储, 不新增迁移, 不自动升级审批链或改变状态机. 前端 `change-dialog` 新增"工期影响(天)"(`:number`) 与"成本影响金额"(文本) 两个可选字段, `transform` 把空值 dissoc; 变更台账新增"量化影响"列, 回显蓝色"工期 +N 天"/"成本 +X"标签, 达阈值追加红色"高影响"徽标, 未量化显示灰色"未量化".

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 46 tests / 544 assertions, 0 failures/errors | 新增 `change-impact-is-quantified-validated-and-high-impact-flagged`: 建变更填 `schedule_impact_days` 12 + `cost_impact_amount` "150000.5" 回显 12 与规范化 "150000.50", 读模型 `change_high_impact` 为 true; 低于阈值 (3 天 / 99999.99) 为 false; 未量化不含键且为 false; 修订至 20 天生成 rev2 而旧版本仍 12 (不漂移); 非法 "abc"/4000/"-5"/"1.234" 返回 400; 章程体追加 `schedule_impact_days` 按白名单返回 400 |
| 全量 PMS 回归 (CLI SQLite) | 98 tests / 875 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有变更审批/版本用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, 变更量化字段表单与"量化影响"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-h09.spec.js` (隔离 `:3100` 后端, 独立空库): 界面"提出项目变更"填工期 12 天 + 成本 150000.5 -> 命令响应回显 `schedule_impact_days=12` 与 `cost_impact_amount="150000.50"` (截图 h09-1-dialog-quantify.png) -> 再建未量化变更 -> GET governance 校验高影响行 `change_high_impact=true`, 未量化行 `=false` -> 真实 HTTP 建低影响变更 (3 天 / 99999.99) 派生 `=false` -> 重进台账"量化影响"列对高影响行回显"工期 +12 天"/"成本 +150000.50"及红色"高影响"徽标, 低影响行有量化标签但无徽标, 未量化行显示"未量化" (截图 h09-2-ledger-column.png) -> 真实 HTTP 非法量化值 ("abc"/4000/"-5"/"1.234") 均返回 400 且章程体追加量化字段被白名单拒 400; 截图存 `reports/h09/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 高影响判定仅到台账只读预警, 未据此自动升级审批链/改状态机/触发通知投递; 未做量化影响与财务成本台账或计划基线重排的自动对账.

边界: 变更量化影响是 H09"多维影响分析"口径中"量化(工期/成本)影响 + 高影响预警"一项的 `implemented / local` 落地(可选强类型字段 + 校验 + 版本冻结不漂移 + 变更专属白名单 + 只读派生高影响徽标); 但 H09 行仍含"CCB 多人表决""跨系统通知""财务自动应用""量化阈值联动自动升级审批"等未完成子项, 故 H09 保持 `partial`, 不因这一子能力上行.

## H08 风险应对策略可选枚举字段 (本轮增补, 2026-09-23)

设计与关闭口径: 兑现矩阵 H08"风险评分口径和复审频率明确"里"识别后登记结构化应对策略"的一环. 复用既有 `risk` kind 与整条登记/复评/独立关闭链, **无新增迁移** (字段随风险记录 payload JSON 存储). 沿用"免迁移给治理 kind 加可选强类型字段"套路的**枚举变体**: 在 `governance.collaboration` 新增集合 `risk-response-strategies` = `#{"avoid" "transfer" "mitigate" "accept"}` (PMI 四类风险应对策略), `insert-risk!` 的 `cond->` 增加一条 `(:response_strategy fields) (assoc :response_strategy (s/enum! ...))` 分支, 只在字段存在时经 `s/enum!` 校验并写入, 非法取值返回 400, 未填则不写键; `create-risk!` 的 `s/input!` 白名单新增 `:response_strategy`. 因未填不写键且分支只在字段存在时触发, 既有登记/复评/库实例化用例 (均不带该字段) 零回归. 该字段与评分/超阈值升级门控相互独立: `from-library` 实例化的风险默认不带 `response_strategy`(仍为 `nil`), 手工登记选策略也不改变 `score`/`escalated` 计算. 前端 `risk-dialog` 在期限字段后新增 `:response_strategy` `:select` 下拉(规避/转移/减轻/接受), 风险台账在"复审重评"列后新增只读"应对策略"列, 以 geekblue 标签回显中文策略名, 未设定显示灰字"未设定". 全程免迁移, 免新命令, 免新 kind, 免新状态值.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 47 tests / 551 assertions, 0 failures/errors | 新增 `risk-response-strategy-is-optional-enum-persisted`: 不填策略登记 3x3 风险 -> `response_strategy` 为 `nil`; 填 "transfer" 登记 2x3 风险 -> 回显 "transfer" 且 `escalated` false、`score` 6, 读模型 `risk-row` 二次回显 "transfer"; 非法 "ignore" 返回 400; `from-library` 实例化 `cost-overrun` -> `response_strategy` 为 `nil` 且 `source_key` "cost-overrun" 仍在 |
| 全量 PMS 回归 (CLI SQLite) | 99 tests / 882 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有风险登记/复评/升级/库实例化用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, 应对策略表单下拉与台账"应对策略"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-h08r.spec.js` (隔离 `:3100` 后端, 独立空库): 界面"登记项目风险"新增"应对策略"下拉, 登记一条选"转移"且 2x3=6 不触发升级的风险 -> 命令响应 `result.response_strategy="transfer"`/`escalated=false` (截图 h08r-1-dialog-strategy.png), 台账"应对策略"列 geekblue 标签显示"转移"; 另一条不选策略 -> 回显 `response_strategy=null` 且台账列显示灰字"未设定" (截图 h08r-2-ledger-column.png); GET governance 二次确认两条回显一致; 真实 HTTP POST 非法取值 "ignore" 命中枚举校验返回 400; 截图存 `reports/h08r/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 应对策略目前仅为登记属性, 未与后续 `mitigate` 缓解动作或审批流做联动约束(如"接受"策略是否需额外审批), 未做按策略聚合的只读统计看板, 未做策略变更历史留痕.

边界: 风险应对策略是 H08 风险识别登记口径中"结构化声明应对策略并可视"一项的 `implemented / local` 落地(可选枚举 + `s/enum!` 校验 + 缺省不写键零回归 + 免迁移持久化 + 台账只读回显); 但 H08 行仍含"升级通知投递""跨项目风险汇总升级""策略与缓解/审批联动"等未完成子项, 故 H08 保持 `partial`, 不因这一子能力上行.

## C02 需求验证方式可选枚举字段 (本轮增补, 2026-09-23)

设计与关闭口径: 兑现矩阵 C02/C01"需求可追踪且带验证方法"里"为需求条目声明结构化验证方式"的一环. 复用既有 `requirement` kind 与整条创建/修订/批量导入链, **无新增迁移** (字段随需求记录 payload JSON 存储, `store/insert!` 对 fields 里任意新增键自动序列化落库, 故读模型与 workspace 均无需改动). 沿用"免迁移给治理 kind 加可选强类型字段"套路的**枚举变体**: 在 `governance.evidence` 新增集合 `requirement-verification-methods` = `#{"test" "inspection" "demonstration" "analysis"}` (ISO/IEC/IEEE 29148 四类验证方法, 仅用于验收规划, 不替代追踪与关闭证据). 关键点一: CSV 批量导入的表头必须严格等于 `requirement-fields` 五列, 因此不能把可选字段塞进 `requirement-fields`; 改为主张用独立的 `requirement-input-fields` = `(conj requirement-fields :verification_method)` 作为创建/修订请求体的 `s/input!` 白名单, 导入路径仍走五列不变. 关键点二: 可选枚举的写入以**值存在** `(seq vm)` 为门控, 而非键存在 `(contains? body :key)`; 因为前端 antd `:select` 未选择时仍会把键以空值提交上来, 若按键存在判断会误触发 `s/enum!` 对空串校验返回 400; 用 `(seq vm)` 把 `nil` 与空串一并视作"未设定"不写键, 从而既有导入与不填用例零回归. 非法取值返回 400. 前端 `requirement-dialog` 在优先级字段后新增 `:verification_method` `:select` 下拉(测试/检验/演示/分析), URS 台账在"优先级"列后新增只读"验证方式"列, 以 geekblue 标签回显中文名, 未设定显示灰字"未设定". 全程免迁移, 免新命令, 免新 kind.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 48 tests / 561 assertions, 0 failures/errors | 新增 `requirement-verification-method-is-optional-enum-persisted`: 填 "test" 创建 -> 回显 "test" 且读模型二次回显; 不填创建 -> `verification_method` 为 `nil`; 非法 "vibes" 返回 400; 修订为 "demonstration" -> rev2 回显 "demonstration" 而原记录仍为 "test"; CSV 批量导入行 -> `verification_method` 为 `nil` (五列表头不变) |
| 全量 PMS 回归 (CLI SQLite) | 100 tests / 892 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有需求创建/修订/预检/导入用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, 验证方式表单下拉与台账"验证方式"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-c02v.spec.js` (隔离 `:3100` 后端, 独立空库): 界面"新增URS需求"新增"验证方式"下拉, 建一条选"测试"的需求 -> 命令响应 `result.verification_method="test"` (截图 c02v-1-dialog-method.png); 另一条不选 -> 回显 `verification_method=null`; GET governance 二次确认回显一致; 真实 HTTP 修订为 "demonstration" -> `result.verification_method="demonstration"` 且 revision 2、原记录仍 "test"; 台账"验证方式"列 geekblue 标签显示"演示"、未选行显示灰字"未设定" (截图 c02v-2-ledger-column.png); CSV 导入行 `result.rows` 里 `verification_method=null`; 真实 HTTP POST 非法取值 "vibes" 命中枚举校验返回 400; 截图存 `reports/c02v/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 验证方式目前仅为需求登记属性, 未与后续验收/关闭证据做联动约束(如"test"类需求是否强制要求测试通过证据), 未做按验证方式聚合的只读统计看板, CSV 导入暂不提供验证方式列(表头仍为五列).

边界: 需求验证方式是 C01/C02"需求可追踪且带验证方法"口径中"声明结构化验证方式并可视"一项的 `implemented / local` 落地(可选枚举 + 值存在门控 + `s/enum!` 校验 + 导入白名单分离 + 免迁移持久化 + 台账只读回显); 但需求"验证方法与验收证据闭环""双向追踪覆盖度"等子项仍未完备, 相关矩阵行保持既有 honest 状态, 不因这一子能力上行.

## C02 需求验证方式覆盖度只读派生 (本轮增补, 2026-09-23)

设计与关闭口径: 兑现上一轮 C02v 遗留的"未做按验证方式聚合的只读统计"边界, 为需求验证方式提供只读覆盖度聚合. 沿用"给治理台账加只读派生洞察"套路(承 C04 文档归集/H18c 归集剔除/C09-C02v 只读派生), 在 `governance.evidence` 新增纯函数 `verification-coverage`, 对传入的需求记录聚合; **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind**. 统计口径: 复用 `store/latest` 对需求按业务编码 `code` 分组取最高 revision 的既有不变量, 使同一逻辑需求的多次修订只计入一次(与 C04 文档归集"修订不重复计数"同源), 再过滤掉最新版本处于受控作废 `discarded` 的编号(沿用 H18c 归集剔除口径). 输出 `{:total :declared :undeclared :coverage-pct :by-method}`: `total` 为计入的需求编号数, `declared` 为其中已声明四类验证方法(`test`/`inspection`/`demonstration`/`analysis`)之一者, `coverage-pct` 为 `declared/total` 四舍五入整数百分比(`total` 为 0 给 0, 用 `(int (Math/round ^double (* 100.0 (/ declared total))))` 避免 ratio 序列化), `by-method` 固定四类各 `{method, count}`. 派生键一律无尾随 `?`(Clojure keyword 会把 `?` 字面序列化进 JSON); `:coverage-pct` 经 `clj->js` 后是字面 `"coverage-pct"`(保留连字符), 故前端/E2E 用 `['coverage-pct']` 中括号取值而非驼峰. workspace 里 `governance.clj` 在需求读模型之后 `assoc :verification_coverage (evidence/verification-coverage (:requirements data))` 暴露. 前端"URS与追踪"页签在需求台账之后新增 `coverage-section` 面板: 蓝色标签"最新版本需求 N", 百分比标签按 100% 绿/0% 红/其余金着色"已声明验证方式 P%", 有未设定项时追加橙色"未设定 K", 四类方法以 geekblue(计数>0)/default 标签回显"方法 · 计数", 需求为空时显示占位提示.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 49 tests / 577 assertions, 0 failures/errors | 新增 `requirement-verification-method-coverage-is-derived-read-only`: 四条需求(test/inspection/nil/test) -> total=4, declared=3, undeclared=1, coverage-pct=75, by-method test=2 inspection=1 demonstration=0 analysis=0; 把 nil 那条修订为 demonstration -> 按 code 去重后 total 仍=4, declared=4, pct=100, demonstration=1(修订不重复计数); 作废其中一条 -> total=3, declared=3, pct=100, test=1(剔除已作废最新版本) |
| 全量 PMS 回归 (CLI SQLite) | 101 tests / 908 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, workspace 新增 `:verification_coverage` 未造成既有需求/文档/归集用例回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, `coverage-section` 面板一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-c02v2.spec.js` (隔离 `:3100` 后端, 独立空库): 界面登记三条需求(测试/检验/不选) -> "验证方式覆盖度"面板显示"最新版本需求 3"、金色"已声明验证方式 67%"、橙色"未设定 1"及"测试·1 检验·1 演示·0 分析·0" (截图 c02v2-1-coverage-panel.png); 真实 HTTP 把不选那条修订为 demonstration 后重开 -> 面板升到绿色"100%"、"演示·1"、"未设定"标签消失, 计入需求数仍为 3 而非 4(证明按 code 最新有效版本聚合, 修订不重复计数), 台账同时可见 rev2"演示"与 rev1"未设定"两行 (截图 c02v2-2-coverage-full.png); 真实 HTTP GET governance 二次确认 `verification_coverage['coverage-pct']=100` 与 `by-method` 计数一致; 截图存 `reports/c02v2/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 覆盖度只反映"是否声明了验证方式", 不等于已配齐对应验收证据, 亦未与追踪矩阵"验证需求"关系做联动校验; 暂不做按类别/优先级的更细切分统计, 无导出.

边界: 验证方式覆盖度只读派生是 C01"需求可追踪且带验证方法"口径中"按验证方式聚合只读统计"一项的 `implemented / local` 落地(纯函数聚合 + latest 去重 + 剔除已作废 + 免迁移 + workspace 暴露 + 界面面板); 但需求"验证方法与验收证据闭环""双向追踪覆盖率分母界定"等子项仍未完备, 相关矩阵行保持既有 honest 状态(C01/C03 不上行), 不因这一子能力上行.

## C06 证据发布覆盖度只读派生 (本轮增补, 2026-09-23)

设计与关闭口径: 为 C06 文档发布审批链提供"按发布生命周期聚合"的只读覆盖度洞察, 与 C04 文档归集(按阶段/结构节点/密级聚合)互补——归集看"证据分布在哪", 本项看"证据发布审批推进到哪一步". 沿用"给治理台账加只读派生洞察"套路(承 C04 归集/H18c 剔除/C02v2 覆盖度), 在 `governance.evidence` 新增纯函数 `release-coverage`, 对传入的文档记录聚合; **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind**. 统计口径: 复用 `store/latest` 对文档按业务编码 `code` 分组取最高 revision 的既有不变量, 使同一逻辑文档的多次修订只计入一次(与 C04/C02v2 同一套"修订不重复计数"), 再过滤掉最新版本处于受控作废 `discarded` 的编号(沿用 H18c 归集剔除口径). 输出 `{:total :approved :in-review :registered :rejected :released-pct}`: `total` 为计入的文档编号数, 其余按发布生命周期四态各自计数(`approved` 已发布, `in_review` 待审, `registered` 未提交, `rejected` 已驳回), `released-pct` 为 `approved/total` 四舍五入整数百分比(`total` 为 0 给 0, 用 `(int (Math/round ^double (* 100.0 (/ approved total))))` 避免 ratio 序列化). 派生键一律无尾随 `?`; `:released-pct`/`:in-review` 经 `clj->js` 后是字面 `"released-pct"`/`"in-review"`(保留连字符), 故前端用 Clojure keyword 取值无碍而 E2E 原始 JSON 需 `['released-pct']`/`['in-review']` 中括号取值. workspace 里 `governance.clj` 在 `:verification_coverage` 之后 `assoc :release_coverage (evidence/release-coverage (:documents data))` 暴露. 前端"证据版本"页签在文档归集视图之后新增 `release-coverage-section` 面板: 蓝色标签"覆盖文档 N"(命名区别于归集面板的"最新版本证据", 避免同页两面板文案相撞), 百分比标签按 100% 绿/0% 红/其余金着色"已发布率 P%", 另以绿"已发布"、processing"待审"、default"未提交"、红"已驳回"标签回显各态计数(计数为 0 的状态标签不渲染), 文档为空时显示占位提示.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 50 tests / 590 assertions, 0 failures/errors | 新增 `document-release-coverage-is-derived-read-only`: 四文档 A(提交+独立批准->approved)/B(提交->in_review)/C(提交+驳回->rejected)/D(保持 registered) -> total=4, approved=1, in-review=1, rejected=1, registered=1, released-pct=25; 修订 A 产生新未发布版本 -> 按 code 去重 total 仍=4, approved=0, registered=2, released-pct=0(修订不重复计数, 最新有效版本回退); 作废处于已驳回(可作废状态)的 C -> total=3, rejected=0, in-review 仍=1(剔除已作废最新版本; 处于 in_review 的 B 不可直接作废, 符合 `discardable-status` 门控) |
| 全量 PMS 回归 (CLI SQLite) | 102 tests / 921 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, workspace 新增 `:release_coverage` 未造成既有需求/文档/归集/覆盖度用例回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, `release-coverage-section` 面板一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-c06b.spec.js` (隔离 `:3100` 后端, 独立空库): 界面登记四份证据文档 -> A 提交并由独立审核人第二浏览器上下文"批准发布"、B 提交停留"待发布审批"、C 提交后审核人"驳回"、D 保持"已登记"; "证据发布覆盖度"面板显示蓝色"覆盖文档 4"、金色"已发布率 25%"、绿"已发布 1"、"待审 1"、"未提交 1"、红"已驳回 1" (截图 c06b-1-release-coverage-partial.png); 真实 HTTP GET governance 二次确认 `release_coverage` 各态计数与 `['released-pct']=25` 一致; 界面给已发布的 A 点"新版本"建 rev2 -> 面板"覆盖文档"仍 4(按 code 去重)而"已发布率"降为 0%、"未提交"变 2, 台账同时可见 A-v2"已登记"与 A-v1"已发布"不漂移 (截图 c06b-2-revision-dedup.png); 真实 HTTP 作废处于已驳回的 C -> 面板"覆盖文档"降到 3、"已驳回"标签消失、"待审 1"仍在, 归集面板同步显示"已作废 1 未计入" (截图 c06b-3-discarded-excluded.png); 截图存 `reports/c06b/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例, 本轮完全免迁移, 不新增 DDL); 覆盖度只反映"各文档最新版本处于哪个发布状态", 不等于文档内容质量或签章合规; 暂不做按阶段/密级切分的发布进度漏斗, 无导出.

边界: 证据发布覆盖度只读派生是 C06"文档从编制到评审/发布/签发留版本轨迹"口径中"按发布生命周期聚合只读统计并界面可视"一项的 `implemented / local` 落地(纯函数聚合 + latest 去重 + 剔除已作废 + 免迁移 + workspace 暴露 + 界面面板); 但 C06 行仍含"正式电子签章""外部文书模板""Gate 引用与发布状态联动阻断"等未完成子项, 故 C06 保持 `partial`, 不因这一子能力上行.

## 蓝图对齐四增量: 平台模板/关口/现场/组合看板/四算 (本轮, 2026-09-24)

范围与取舍: 用户于 2026-09-24 明确要求"完成 PPT 中的全部内容 (要能展示这些功能)", 并在澄清中选定 (1) 只做无外部依赖的功能, CRM/OA/ERP/PLM/MES/SRM/销服/BI 各"待合同"行保持 planned 且不以模拟冒充; (2) 云端开发后同步回本机本地提交, 由用户自行推送; (3) 按 [路线图](10-development-roadmap.md) 批次连续推进. 本轮按四个增量提交在 `feat/blueprint-a-templates` 分支上 (基线 `main@c5c024a`): 增量1 `0b7b9c9` 平台模板/编码规则/项目类别与项目网络实例化, 关口目录与阻断检查点, 阶段进度卷积 (矩阵 A07/A09/A10/A11, B02/B03, B11-B13/B15); 增量2 `0422e60` 工勘/DQ/启动会会前包/单机局部暂停/齐套多层卷积/包材申请/装配步骤/发货前条件/交底时限/现场任务 (B05-B10, B16, D03/D06, E01/E04/E07-E10); 增量3 `1876031` 追踪偏差与覆盖率, 文档多层下钻与机密访问, 逾期追溯升级, 我的待办/全局检索/项目组合看板/过程看板, 四算拉通, 工时更正与封期, 跨项目研发费用池, 季度经营目标看板 (C03-C07/C09, F04-F06/F08/F09, G02/G03/G16/G18); 增量4 `1fded62` 全局检索 SQL 预筛与全量浏览器回归修复, 以及组合看板/我的待办按授权项目集合批量读取 (消除逐项目逐类型 N+1 查询: 65 个项目时 `/portfolio` 8.5s -> 0.30s, `/todo` 12.8s -> 0.21s, 真实 HTTP 计时) 与文档/报告收口. 设计与 HTTP 合同见 [blueprint-extension](contracts/blueprint-extension.md); 每行的诚实状态与子能力边界以 [矩阵](11-feature-acceptance-matrix.md) 为准.

设计要点 (沿用既有内核, 不另起炉灶): 新增平台级版本化配置表 `pms_config_record` (kind = project-template / coding-rule / quarterly-target / rd-pool / period-lock, 状态 draft -> published|frozen|locked -> retired, 以 `(kind, code, revision)` 版本化并保留 history; 发布新版本自动 retire 旧版本, 已全部 retired 的 code 允许重新创建为下一 revision); 项目模板实例化一次性生成结构节点/关口模板/阶段与节点计划容器/交付要求/收尾清单并把阶段权重快照进 `template-instance` 记录, 修改模板不追溯覆盖已实例化项目; 关口目录 (`gates/catalog.clj`) 给每类关口默认检查项与阻断检查点 (kitting 阻断 `assembly.start`, assembly-test-handover 阻断 `test.SIT`, fat-confirm 阻断 `shipment.dispatch`), 装配开工/试验结果/发运统一经 `checkpoint-ready!` 门控 (409 "阻断关口尚未通过: <关口>"); 进度卷积按阶段权重与节点任务在读取时派生 (`planning/progress.clj`), 单机局部暂停冻结该节点任务反馈 (409) 且读模型回显 `node_paused`; 工勘/交底/现场任务作为交付记录 kind 落库, 交底截止 = 发运日 + 天数, 交底完成自动下发定位/安装/调试/SAT 四条带滞后期的现场任务; 全局检索先在 SQL 层按授权项目范围与关键字 INSTR 预筛 (LIMIT 500) 再在内存做字段级匹配与机密文档过滤; 工时更正为"冲销原条目 + 新条目"链, 封期 (period-lock) 阻断该期间登记/更正; 研发费用池按已批准工时分钟数守恒分摊为各项目决算草稿版本与人工条目 (`source_ref rd-pool:<id>`); 季度目标版本化下达, 实际值按季度内关闭项目已批准决算复算, 修订不改写历史. 均沿用 `kernel/mutate!` 乐观锁 + 审计 + `{:result :project_version}` 信封, 前端 Hooks + 动态菜单 + `page/size` 分页.

| 证据 | 结果 | 说明 |
|---|---|---|
| 平台配置测试 (SQLite) | 6 tests / 93 assertions, 0 failures/errors | `pms_config_test.clj`: 模板目录导入/发布/修订/退役与版本不可变, 编码规则渲染/下一编号/项目编号与节点编码强制校验, 模板实例化项目网络 (节点/Gate 模板/计划容器/交付要求/收尾清单; 重复应用 409; 版本快照), 关口目录与阻断检查点 (齐套关口未通过 -> 装配开工 409), require_released 检查项须文档已发布, 季度目标/费用池/封期生命周期, 配置 HTTP 合同 (401/403/409) |
| 现场闭环测试 (SQLite) | 8 tests / 102 assertions, 0 failures/errors | `pms_fieldwork_test.clj`: 工勘次序/实际日期不可未来/独立确认与收尾阻塞, 包材申请与来源任务回写, 齐套 Gate 阻断装配开工, 装配步骤单调与日期约束, 齐套多层卷积与缺件, 发货前本地条件/交底截止/现场任务顺序, DQ 检查清单与交付件失效 (`dq_stale`), 局部暂停冻结反馈, 启动会强制会前包与基线 |
| 组合/财务测试 (SQLite) | 7 tests / 86 assertions, 0 failures/errors | `pms_portfolio_test.clj`: 追踪阶段/偏差等级/覆盖率与阻塞偏差, 文档多层下钻与机密文档 403, 逾期追溯升级门控, 我的待办跨项目汇总与授权范围, 组合看板卡片与节点下钻, 全局检索授权与机密过滤, 工时更正链与封期 409, 跨项目研发费用池守恒分摊与幂等, 四算拉通差异, 季度经营目标达成复算与修订保留历史 |
| 全量 PMS 回归 (CLI SQLite, 全新库) | 123 tests / 1204 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`; 既有 102 例在新增迁移 `202609240001-blueprint-extension` (治理/交付记录 kind CHECK 表重建, 计划任务 node_id/stage_code, 工时更正链列, 5 个新菜单与 3 个新权限) 下无回归; 治理测试 `traceability-report-computes-per-version-link-gaps` 因追踪摘要扩展 (phases/coverage-pct/design-pct) 改为 select-keys 断言, 不放宽原有缺链判定 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app`; 新增页面 模板与规则/我的待办/全局检索/项目组合看板/经营目标看板 与项目工作台 过程看板/进度卷积/工勘与现场/DQ与局部暂停 页签一并编译 |
| Chrome 浏览器 (Playwright, Linux Chromium 141, 隔离 `:3100` 后端独立空库) | 新增 3 个 spec 5 passed | `pms-a-templates.spec.js` (1 passed): 模板页导入目录并发布 -> 编码规则"按规则生成编号" -> 项目应用模板生成结构/Gate/计划容器 -> 进度卷积 -> 关口目录与进展 -> 研发类模板阶段不同 -> 重复应用 409; 截图 `reports/a-templates/a-1..a-6`. `pms-b-gates-fieldwork.spec.js` (2 passed): 治理侧 关口目录建立/DQ 签认与交付件失效/启动会强制会前包与基线/单机局部暂停冻结反馈 (截图 `reports/b-fieldwork/b-1..b-4`), 交付侧 交付配置/包材申请/齐套多层卷积/齐套 Gate 阻断开工/装配步骤/发货前条件/交底时限/现场任务/工勘独立确认 (截图 `d-1..d-5`, `e-1..e-3`). `pms-c-portfolio.spec.js` (2 passed): 治理侧 追踪阶段/偏差与覆盖率/文档多层下钻与机密文档 403/逾期问题追溯升级/过程看板 (截图 `reports/c-portfolio/c-1..c-3`, `g-3`), 平台侧 我的待办/全局检索/项目组合看板/经营目标/四算拉通/工时更正与封期/研发费用池分摊 (截图 `c-4`, `g-1`, `g-2`, `f-1..f-5`, `f-4b`); 均以独立审批人第二浏览器上下文完成独立确认, 无未捕获 JS 错误 |
| 全量 PMS 浏览器套件 (同一隔离后端, 单库连续运行) | 首轮 45 passed / 6 failed (24.0m); 修复后复跑 5 例 passed, 剩 1 例为环境差异 | 6 个失败均非本轮产品代码缺陷: `pms-c07`/`pms-c09b` 台账同一行既有"已逾期"标记又有后来增加的"到期倒计时"列"已逾期 N 天", `getByText('已逾期')` 触发 strict 违规 -> 改 exact 匹配; `pms-h18` 断言 `document_collection.total=2` 是 H18c 剔除已作废之前的口径 -> 改为 total=1 且 discarded-count=1; `pms-h01pm` 工作台人员下拉只列项目成员, 单库连续运行时前序用例建的非成员用户被选作候选 PM -> 用例先经 `/members` 加入成员; `pms-c-portfolio` 平台侧全局检索在多项目累积数据下内存扫描超时 -> 增量4 加 SQL 预筛后 <2s 通过; `pms-c05` 只在 `download.suggestedFilename()` 断言失败 (期望 `证据文档.zip`, 实得 `download`), 用独立 Playwright 探针复现: 本环境 Chromium 141 headless 对 blob URL 的非 ASCII `download` 属性名一律回退为 `download`, ASCII 名 `evidence.zip` 正常, 与应用代码无关 (2026-09-22 本机 Chrome 已通过该例), 未改动产品代码与该用例; 修复后全量复跑结果见下一行 |
| 全量 PMS 浏览器套件复跑 (增量4 修复后, 同一隔离后端全新库) | 49 passed / 2 failed (23.9m) | 2 个失败: `pms-c05` 同上 (环境差异, 未改代码); `pms-c-portfolio` 平台侧在同库第二次运行时 (a) 季度目标编码 `2026Q3-revenue` 已存在 409 -> 用例先退役遗留版本并取同指标最后一行, (b) 累积 65 个项目后 `/portfolio` 逐项目逐类型查询耗时 8.5s 超过 5s 断言 -> 产品侧改为按授权项目集合批量读取 (`pms/*-in-projects` 七条 IN 列表查询, 内存按 project_id 分组), 修复后在同一累积库复跑 `pms-c-portfolio.spec.js` 2 passed (1.6m), `pms-b-gates-fieldwork.spec.js` 2 passed; 组合/财务测试 7 tests / 86 assertions 与全量回归复跑结果见下一行 |
| 全量 PMS 回归复跑 (批量读取改动后, 全新 SQLite 库) | 123 tests / 1204 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` |
| 前端清缓存编译 | 4032 compiled, 0 warnings, 69.2s | 删除 `.shadow-cljs/builds/app` 后 `npx shadow-cljs compile app` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地与云端均无可用实例; `resources/migrations/202609240001-blueprint-extension.{up,down}.sql` 已按 InnoDB/LONGTEXT/INSERT IGNORE 语法与 SQLite 版逐语句同步并保留 `--;;` 分隔, 但未在 MySQL 8 上实际执行); 生产 release 构建与 uberjar 独立启动 (本轮以 dev 编译 0 warnings 与隔离后端真实 HTTP 代替); 外部系统联通 (按用户选择不做). `pms-c05` 在本环境的文件名断言差异需在本机 Chrome 复验后才能重新计为通过.

边界: 本轮把无外部依赖的 PPT 功能行推进到 `implemented / local` 或 `partial`, 具体到行的诚实状态与"待:"内容以矩阵为准. 明确不上行的复合能力: 采购/供应商进度与 ERP/MES/CRM 回传 (待合同), 编码规则/Gate 适用编号/交底自然日或工作日等待批准的业务口径 (待规则), 生产 MySQL 目标环境与 UAT (H19/H20). 模板/编码规则修改不追溯已实例化项目, 费用池按已批准工时守恒分摊不含费率/税额/汇率, 经营目标实际值只取季度内关闭项目已批准决算, 均为本地确定性口径而非财务权威数据.

## 增量5: 证据文档二进制附件与证据链 + 云端 MySQL 8 真实回归 (2026-09-24)

范围: [完成计划](13-completion-plan.md) 增量5, 矩阵 C04 / C05 / C06 (A09 文档类别归集). 设计: 文本证据保持原样 (`content_kind=text`), 新增真实文件证据 (`content_kind=file`): `POST /documents/upload` 与 `POST /documents/:rid/upload-revision` 接受 multipart (文件 + 编号/标题/密级/阶段/结构节点/类别 + 项目版本), 服务端校验文件名与扩展名白名单 (可执行等不允许) / 空文件 / 大小上限 (`PMS_FILE_MAX_MB` 缺省 50), 文件按内容寻址写入 `PMS_FILE_DIR/<project_id>/<sha256>` (同项目同内容共用一份, 已存在不覆盖, 版本不可变), 记录只存元数据与 SHA256 (正文不进 JSON 载荷); 下载 / 内联预览 / 批量 ZIP 在下发前整文件复核 SHA256, 被篡改或缺失拒绝下发 (500) 而不是把损坏件当原件; 每次访问与包内每文件校验项目授权与 `pms:document:confidential`; 发布批准时固化 `released_at` 与 `release_sha256` 作为受控签发记录 (非法定电子签章). 前端"证据版本"新增"上传证据文件" (multipart, 文件选择器) 与文件行"预览/下载" (PDF 内嵌框 / 图片 / 文本, 其它类型只下载, 显示服务端复核一致与否), 文本与文件证据共用文档类别下拉 (取已实例化模板的 document_categories). 沿用 `kernel/mutate!` 版本校验与审计, 免迁移 (字段随 payload).

| 证据 | 结果 | 说明 |
|---|---|---|
| 文档附件测试 (SQLite) | 5 tests / 68 assertions, 0 failures/errors | `pms_documents_test.clj`: 内容寻址 + 摘要 + 去重 + 修订链 (只对最新版本, 编号不变); 类型白名单 / 空文件 / 超限 (测试配置 2MiB) / 路径穿越 / 缺文件 / 伪造 sha256 字段 / 非法密级 400, 只读用户 403; 机密文件无密级权限 403 (单文件与批量), 篡改文件 500 "证据文件校验失败", 删除文件 500 "证据文件缺失"; 签发固化 release_sha256 / released_at, 已签发不能再提交 409; multipart HTTP 合同: 401 / 403 / 400 (exe) / 200 上传, 下载字节与摘要头一致, preview inline 与 415, 修订 200, 文本与文件混合批量 ZIP 4 个条目且 MANIFEST 含 content_kind |
| 全量 PMS 回归 (CLI SQLite, 全新库) | 128 tests / 1272 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` |
| 全量 PMS 回归 (MySQL 8.0.46, 云端 apt 安装, 全新库) | 128 tests / 1241 assertions, 0 failures/errors | `PMS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3306/hc_pms_test?user=pms&password=pms123&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'`; 全部迁移 (含 202609240001-blueprint-extension) 在 MySQL 实际执行; 与 SQLite 差 31 条为并发套件 SQLite 驱动专属断言 (与既往 CI 口径一致); 首轮暴露 config/fieldwork 测试的合成角色编号与 finance/ops 测试冲突 (共享 MySQL 库下 Duplicate entry 9400/9600), 已改为 9440/9640 段后通过 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` |
| Chrome 浏览器 (Playwright, Linux Chromium 141, 隔离 `:3100` 后端, `PMS_FILE_DIR` 指向隔离目录) | `pms-d-documents.spec.js` 1 passed (33s) | 界面上传真实生成的可渲染 PDF (机密, 类别 设计) -> 表格显示文件名 / 600 B / 类别 / 密级 (截图 d-1); 预览弹窗内嵌 PDF 查看器并显示 "服务端复核: 一致" (d-2); 上传 PNG (公开) -> 图片预览 (d-3); 上传 .exe -> 弹窗错误面板 "不允许的文件类型: exe" (d-4); 真实 HTTP 下载字节 SHA256 与本地文件一致且响应头 X-Content-SHA256 一致, preview 为 inline application/pdf; 无密级权限的独立审核人第二浏览器上下文: 机密 PDF 下载 403 且界面预览弹窗显示 "无机密文档访问权限" (d-5), 公开 PNG 可下载且摘要一致; 批量 ZIP 解包后 PDF 条目摘要与原件一致, MANIFEST 含 content_kind=file; 界面上传同编号新文件版本 -> V2, V1 摘要不变; 提交发布 -> 审核人签发 -> `release_sha256` 等于 V2 摘要, 台账 "已发布" (d-6); 无未捕获 JS 错误; 截图存 `reports/d-documents/` |
| 既有文档相关浏览器用例复跑 | c04, c04b, c06, c06b, b05, h18 (2), h18b (2), h18c (3), workbench (4) 通过; c05 仅环境文件名断言 | 上传入口与文本登记并列, 既有 "登记证据文档" 入口与弹窗标题保持不变 |

本轮未执行 (如实记录): 本机 macOS Chrome 复验 (含 c05 文件名); 生产对象存储 / 分布式文件系统 (G17) 仍待, 当前为本地目录内容寻址存储 (备份须包含 `PMS_FILE_DIR`); 病毒扫描与文件内容嗅探 (只按扩展名白名单与服务端 MIME 映射); 外部 CA 电子签章与外部文书模板不在本地范围.

边界: C04 / C05 / C06 三行按各自验收口径上行为 `implemented / local` (分层归集与可追踪, 逐次逐文件权限与摘要一致, 编制到签发的版本轨迹与 Gate 引用不漂移); 生产存储与签章边界如上, 不据此把 G17 或 H14 上行.

## 增量6: 计划与进度深化 (2026-09-24)

范围: [完成计划](13-completion-plan.md) 增量6, 矩阵 B02 / B03 / B12 / B15 / B19 / H04 / H06. 设计: 迁移 `202609240002-plan-progress` (双库同步: 治理记录 CHECK 新增 stage-weights / reschedule / progress-snapshot / reminder 四类 (建新表-拷贝-改名重建), 计划任务与执行反馈增加 actual_start / actual_end, 登记 sys_job 9001 `com.ruoyi.task/pms-progress-scan` 每日 06:00; down 双库可回退并可再次 up). 模板阶段声明 `levels` (main/sub/machine) 与 `default_days`, `POST /planning/derive` 按层级在阶段容器/节点容器下派生任务并 FS 串联 (幂等); `plan_conflicts` 读取时定位子/单机阶段任务晚于主计划同阶段窗口; `POST /planning/nodes/:id/reschedule` 按工作日偏移整体后移未开始叶子任务并写 reschedule 记录, 已批准基线不改写; `POST /planning/stage-weights` 版本化项目级权重覆盖 (合计 100, 覆盖全部模板阶段), 卷积与组合看板同口径; 反馈 actual_start / actual_end 校验 (不晚于当天, 完成才可填实际完成, 不早于实际开始); `earned_value` 以计划工作日为单位 (PV 按排程应完成, EV = 工期 x 完成比例, AC = 已批准工时折算, SPI/CPI/EAC/ETC/预测完工, 按阶段/节点分组); `POST /planning/snapshot` 与定时扫描同一实现 (`scan/scan-project!`: 日快照同日覆盖 + 逾期提醒同对象一条/刷新/自动关闭, 不递增项目版本不写 pms_event), `POST /api/pms/scan` 需 `pms:config:edit`; Gate 检查项 `waived + waiver_reason` 例外放行 (计入通过, 单独标注, 缺说明 409), 交接/SAT 关口检查项细化 (AT-1..AT-4, SAT-1..SAT-5). 前端: 进度卷积页签新增 派生 / 覆盖阶段权重 / 生成进度快照 按钮与节点 重排 操作, 主子约束冲突 / 挣值与完工预测 / 进度趋势 (快照) / 节点重排记录 面板, 头部 冲突数 与 SPI 标签, 任务表 实际开始/完成 与 来源 列, 反馈表单实际日期; Gate 检查弹窗 例外放行 (需说明) 选项与 例外 N 标签 (对应证据版本改为服务端校验的可选项); 我的待办 系统提醒 分组; 组合看板 SPI / CPI 列. 同时修正 RuoYi 定时任务 "执行一次" 只按 DEFAULT 组触发的缺陷 (按 sys_job 记录的任务组触发, 否则 PMS 组任务无法立即执行).

| 证据 | 结果 | 说明 |
|---|---|---|
| 计划与进度深化测试 (SQLite) | 8 tests / 112 assertions, 0 failures/errors | `pms_progress_test.clj`: 派生 3 节点 / 5 主阶段 / 15 任务 / 11 依赖且重复派生全部跳过; 拉长单机任务后冲突定位到 M1 S7 (天数为正), 主计划无该阶段任务时不判定; 重排移动未开始任务并保留已批准基线 (revision 递增), 主项目 400 / 已开始 409; 权重覆盖版本化 (revision 2 生效, 模板快照不变), 编码缺失 / 合计错误 400; 挣值纯函数确定性 (PV/EV/AC/SPI/CPI/EAC/预测完工/状态); 反馈实际日期校验 (未来 / 非完成填实际完成 / 早于开始 400) 与 read-plan 暴露挣值 (480 分钟批准工时 -> AC 1.0); 扫描同日快照幂等, 提醒同对象一条 / 刷新 / 完成后自动关闭, 责任人与项目经理待办可见, 非配置权限 403; Gate 例外放行需说明, 同时 passed 400, 提交时缺说明 409 |
| 全量 PMS 回归 (CLI SQLite, 全新库) | 136 tests / 1384 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` (增量6 代码定稿后复跑) |
| 全量 PMS 回归 (MySQL 8.0.46, 全新库) | 136 tests / 1353 assertions, 0 failures/errors | 同一命令加 `PMS_TEST_JDBC_URL` (见增量5); 首轮 8 failures / 5 errors 全部源于共享 MySQL 库下 config 套件先发布了改成单阶段的 `TPL-EQUIPMENT` 修订, 进度测试复用该版本导致派生 1 阶段; 改为 "已发布版本阶段与目录不一致时按目录建修订并发布" 后通过 (SQLite 每命名空间独立临时库, 未暴露此依赖); 与 SQLite 差 31 条为并发套件 SQLite 专属断言 |
| 迁移回退探针 (SQLite + MySQL) | `migratus/down 202609240002` 后 `up` 均成功 | SQLite down 原先保留实际日期列导致再次 up 报重复列, 已改为 DROP COLUMN (与仓库既有 SQLite down 一致); down 丢弃四类新记录 (MySQL 探针库 61 条) 并删除 job 9001, up 后 job 重新登记 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4033 files, 10 compiled) |
| Chrome 浏览器 (Playwright, Linux Chromium 141, 隔离 `:3100` 后端, 迁移 202609240002 在该库实际执行) | `pms-e-progress.spec.js` 1 passed (1.9m) | 发布带层级的设备模板 -> 建项目应用模板 (3 节点) -> 界面 "派生主/子/单机计划" 返回 node_count 3 / main_stage_count 5 / derived_count 15 / dependency_count 11, WBS 表 "来源" 列 "模板派生" (截图 e-1), 重复派生 skipped 15; 真实 HTTP 把 M1-S4 工期改 60 -> 头部 "主子约束冲突 1", 面板定位 M1-S7 晚于主计划窗口 70 天 (e-2); 界面 "覆盖阶段权重" S4=30 -> "权重来自项目级覆盖 (模板快照不变)" (e-3), 合计错误 400; 章程 / 模板必需执行关口 (需求确认Gate) / 基线批准 -> 执行; 界面 "重排" 附件单元 U2 到 2026-10-12 -> "节点重排记录" (e-4), 基线仍 approved 且计划修订递增, 重排后 U2-S2 晚于主计划 S2 窗口 -> 冲突 2 条, 主项目 400; 界面反馈 S1-MAIN 已完成 (实际 2026-09-01 / 2026-09-12) 与 S2-MAIN 处理中 (实际开始 2026-09-14) -> 任务表 "实际开始/完成" 列 (e-5), 非完成状态填实际完成 400, 已开始节点重排 409; 批准 2h 工时 -> AC 0.25; 管理员对迁移登记的定时任务 9001 "执行一次" (`PUT /api/system/job/9001/run`) -> Quartz 调用 `com.ruoyi.task/pms-progress-scan` (后端日志 "PMS 进度扫描完成: 2026-09-24 项目数 26"), 轮询真实 HTTP 看到项目当日快照; 界面 "生成进度快照" 同日覆盖同一条 (仍 1 行), "挣值与完工预测" 面板 BAC 238 / PV 57 / EV 23 / AC 0.25 / SPI 0.4 / CPI 92 / EAC 2.59 与阶段分组 (e-6), "进度趋势 (快照)" 当日行 conflict_count 2 (e-6b); 审核人 "我的待办" 出现 "系统提醒: 任务逾期 SVC-1" 与 "问题逾期" (e-7), 管理员 `POST /api/pms/scan` 覆盖执行中项目, 审核人 403, 逾期任务补录完成后再扫描提醒 closed 1; 界面填写 G5 检查 AT-1..AT-3 通过 + AT-4 "例外放行 (需说明)" -> Gate进展汇总 "检查 4/4" 与 "例外 1" (e-8), 审核人批准通过证据校验, 例外缺说明 400; 组合看板行显示 "SPI x / CPI y" (e-9), 卡片 snapshot_date 为当天且 S4 权重 30; 无未捕获 JS 错误; 截图存 `reports/e-progress/` |
| 全量 PMS 浏览器套件复跑 (同一隔离后端单库连续运行) | 52 passed / 1 failed (25.7m) | 唯一失败仍是 `pms-c05` 文件名断言 (Linux headless Chromium 对 blob 非 ASCII 文件名的环境差异, 增量4 已归因, 未改代码); 含 workbench 4 / a-templates / b-gates-fieldwork 2 / c-portfolio 2 / d-documents 及全部 c/h 用例, 说明任务表新列, Gate 检查弹窗可选证据, 我的待办新分组与组合看板新列未破坏既有用例 |

本轮未执行 (如实记录): 本机 macOS Chrome 复验; 06:00 真实 cron 触发只由 Quartz 表达式与 "执行一次" 路径证明, 未等待到次日实际触发; 提醒只写本地待办, 未投递任何外部消息 (C11 待合同); 财务金额口径挣值 (费率) 在增量7; 反馈独立审核与偏差措施登记未做 (H06 保持 partial); 权重与提醒阈值口径未经业务批准 (B02 / B19 保持 partial / 待规则); 交接 / SAT 检查项企业口径未经业务批准 (B12 / B15 保持 partial).

挣值口径提示: 早期项目 AC 很小时 CPI / EAC 会失真 (截图中 2 小时批准工时对应 CPI 92, EAC 2.59 个工作日), 这是公式的数学结果而非估算; 页面已标注价值单位与口径, 财务金额口径在增量7 费率之后.

边界: B03 上行为 `implemented / local` (派生只建任务与 FS 依赖, 里程碑与跨节点依赖手工登记; 单元/产品线外部编码映射归 A07 待规则); H04 保持 `implemented / local` 并补节点重排证据; 其余五行状态不变但证据列记录已实现子集与仍待部分, 不据此把复合行上行.

## 完整展示流程用例 (2026-09-24)

`tests/e2e/pms-demo-flow.spec.js` 用同一个设备订单项目把工作流程从平台模板走到正式关闭 (37 步, 38 张按序截图存 `reports/demo-flow/`, 剧本见 [14-demo-script.md](14-demo-script.md)): 隔离 `:3100` 后端 (增量 1-6 全部迁移已执行) 上 1 passed (6.6 分钟), 无未捕获 JS 错误; 章程 / 基线 / 交接 Gate / 工时 / 关闭审批由独立审核人第二浏览器上下文在界面批准, 重复性的提交/批准经真实 HTTP 完成 (报告逐步标注). 该用例只组合已验证的功能, 不引入新产品代码; 每次运行新建独立演示项目与审核账号, 不修改既有数据. 未执行: 本机 macOS Chrome 复验.

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
