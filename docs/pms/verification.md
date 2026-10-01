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

## H09 高影响变更提交即自动升级并强制独立确认 (本轮增补, 2026-09-28)

设计与关闭口径: 承接上一节遗留的"量化阈值联动自动升级审批"子项, 把 H09 从"高影响只到界面预警"推进到"提交即自动升级 + 批准前强制独立确认". 复用 H08 风险 / C09d 问题已验证的同一套升级状态机 (`escalated`/`escalation_state`/`escalation_level`/`escalation_reason`/`escalation_decision`/`escalation_ack_by`/`escalation_ack_on`/`workflow_history`), **无新增迁移** (升级键随记录 payload JSON 存储), **无新增 kind** (仍走 `change`). 在 `governance.approval` 抽出纯函数 `high-impact?` (读版本冻结的 `schedule_impact_days >= 10` 或 `cost_impact_amount >= 100000.00`, 复用 `finance-money/amount!` 规范化, 与读模型 `change_high_impact` 同一口径), `change-escalation` 据此产出升级补丁 map 或 nil. `submit!` 末尾的 `s/change!` 用 `cond->` 在 `kind = "change"` 时 `merge` 该补丁: 达阈值即写 `escalated true` / `escalation_state "pending"` / `escalation_level "ccb"` / `escalation_reason`, 未达阈值 `merge nil` 为 no-op 故既有变更用例零回归. `decide!` 在 `status! in_review` 之后加门控: 仅当 `kind=change` 且 `decision=approved` 且 `escalated` 且 `escalation_state=pending` 时 `fail! 409`, 驳回不受门控. 新增独立确认命令 `acknowledge-change-escalation!` (走 `pms:quality:approve` + `{:write? false}` 只读范围, 与既有独立批准一致故只读审批人也能确认): `s/latest!` 取最新版本, `status! in_review`, 未升级 409, 非 pending 409, 确认人 = `created_by` 或 = `submitted_by` 均 403, 否则 approved->acknowledged / rejected->waived 并回写 `escalation_decision/note/ack_by/ack_on` 与 `workflow_history`. 命令表 `[:changes :escalation]`, 路由 `POST /changes/:record_id/escalation`. 前端 `change-actions` 在 `approve?` 且 `in_review` 且 `escalated` 且 `pending` 且非本人登记/提交时显示"确认升级处置"入口 (复用 `change-escalation-dialog`), 变更台账新增"变更控制升级"列 (pending 红"待独立确认" / acknowledged 绿"升级已确认" / waived 蓝"升级已豁免" / 未升级灰"未触发").

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 52 tests / 608 assertions, 0 failures/errors | 新增 `high-impact-change-auto-escalates-and-gates-approval`: 建变更 (12 天 / 150000.5) -> 提交 (审核人 9302) -> 断言 `in_review` 且 `escalated true` / `escalation_state pending` / `escalation_level ccb`; 9302 直接批准 -> 409; 提交人 9301 自确认 -> 403; 独立审批人 9303 确认 approved -> `acknowledged`; 9303 重复确认 -> 409; 9302 再批准 -> `approved`. 新增 `low-impact-and-high-impact-reject-are-not-escalation-gated`: 低影响 (3 天 / 99999.99) 提交不含 `escalated` 且直接批准成功; 高影响 (15 天 / 200000) 提交 pending -> 批准 409 -> 驳回不受门控直接 `rejected` |
| 全量 PMS 回归 (CLI SQLite) | 153 tests / 1523 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有变更审批/版本/风险/问题用例无回归 |
| 授权路由遍历 | 7 tests / 89 assertions, 0 failures/errors | `authz_test` 遍历路由表, 新路由 `POST /changes/:record_id/escalation` 已声明权限, fail-closed 不破 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, "确认升级处置"入口, 确认对话框与"变更控制升级"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-h09esc.spec.js` (隔离 `:3100` 后端, 独立空库, 双真实上下文): admin 界面"提出项目变更"填工期 12 天 (截图 h09esc-1-dialog-quantify.png) -> 读模型 `change_high_impact=true` 且草稿未升级 -> 界面"提交独立审批"选独立审批人 -> GET 校验提交后 `escalated true` / `pending` / `ccb` -> 台账"变更控制升级"列显示红"待独立确认", 登记人 admin 看不到"确认升级处置"入口 (截图 h09esc-2-pending-escalation.png) -> 真实 HTTP admin 自确认升级 -> 403 -> 第二上下文独立审批人登录, 先点"批准"命中门控: 弹窗内联 `[role=alert]` 显示"尚未完成变更控制升级独立确认"且返回 409 不关闭 (截图 h09esc-3-approve-gated-409.png) -> 点"确认升级处置"选"确认升级并责成处置"保存 -> GET `escalation_state=acknowledged` 且 `escalation_ack_by` 为独立审批人, 列翻绿"升级已确认" (截图 h09esc-4-acknowledged.png) -> 再点"批准"成功, 状态 `approved` (截图 h09esc-5-approved.png); 无未捕获 JS 错误; 截图存 `reports/h09esc/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无实例, 本轮完全免迁移不新增 DDL); `escalation_level: ccb` 本轮仅为升级层级标签, 其对应的 CCB 多人表决门槛在下一节 (H09 变更控制委员会多人表决门槛) 单独落地; 未做升级通知投递, 未实现批准后自动改写计划基线/任务/费用台账.

边界: 本节把 H09 的"量化阈值联动自动升级审批"子项从缺口推进到 `implemented / local` (提交即自动升级 + 批准前强制既非登记人也非提交人的独立审批人确认 + 门控界面真实可见); 但 H09 行仍含"CCB 多人表决""跨系统通知""财务自动应用"等未完成子项, 故 H09 整体保持 `partial`, 不上行. (其中"CCB 多人表决"已由下一节单独推进到 `implemented / local`, 剩余"跨系统通知""财务自动应用"仍未完成, H09 整体仍 `partial`.)

## H09 变更控制委员会多人表决门槛 (本轮增补, 2026-09-28)

设计与关闭口径: 兑现矩阵 H09"变更控制委员会与影响决策"标题中此前仅剩标签的"CCB 多人表决"一环, 把 `escalation_level: ccb` 从"只是一个层级字符串"推进到"真实由委员会多人逐人表决并达到赞成门槛后方可批准". 复用既有 `change` kind 与整条 create/revise/submit/decide 独立审批链, **无新增迁移** (委员会名单/门槛/表决票随记录 payload JSON 存储), **无新增 kind**, **无新状态值**. 在 `governance.approval` 抽出纯函数 `ccb-tally` (定义在 `decide!` 之前以规避本项目 Clojure 1.12.4 对同 ns 内后置 `defn-` 前向引用的拒绝), 读 `:ccb_members`/`:ccb_required`/`:ccb_ballots` 计算 {:members :required :approve :reject :quorum_met :state}, `state` ∈ none(未设名单)/voting(赞成未达门槛)/passed(达到门槛)/failed(剩余票已不可能达标). `decide!` 在既有升级门控之后**独立**新增 CCB 门控: 仅当 `kind=change` 且 `decision=approved` 且记录带 `:ccb_required` 且 `approve < required` 时 `fail! 409`, 驳回不受门控, 未设委员会的变更不受门控 (与升级门控可叠加, 二者互不干扰). 新增两条命令 `set-ccb!` (`[:changes :ccb]`, 路由 `POST /changes/:record_id/ccb`) 与 `cast-ccb-ballot!` (`[:changes :ballot]`, 路由 `POST /changes/:record_id/ballot`), 均走 `k/mutate! "pms:quality:approve" {:write? false}` (只读质量审批人可发起, 与既有独立批准/确认口径一致): `set-ccb!` 校验 `members` 为 1 至 15 个不重复用户标识 (否则 400), 逐个 `s/user!` 存在性 (未知成员 404/400), `required` 为正整数且不超过成员数 (否则 400), 状态须 ∈ #{draft in_review}; 写入 `{:ccb_members :ccb_required :ccb_ballots []}` 并追加 `ccj_set` 到 `workflow_history`, 重置亦清空既有表决. `cast-ccb-ballot!` 校验 `vote` ∈ #{approve reject} (否则 400), 状态须 `in_review` (draft 投票 409), 无名单则 409; 投票人 = `created_by` 或 = `submitted_by` 或不在 `members` 中均 403; 同人重复投票以 `filterv` 覆写旧票后 `conj {:member_id :vote :note :on}`. `change-read-model` 读取时 `assoc :ccb_summary` 供台账只读呈现. 前端 `change-actions` 增加"设立/重置变更控制委员会"入口 (`approve?` 且 `draft`/`in_review`) 与"委员会表决"入口 (`approve?` 且 `in_review` 且当前用户在名单内且非登记人/提交人); 变更台账在"变更控制升级"列后新增"变更控制表决"列: 未设委员会灰字, voting 金标"表决中 a/r / 成员 m", passed 绿标"表决通过 a/r", failed 红标"表决未通过 a/r / 成员 m".

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 54 tests / 644 assertions, 0 failures/errors | 新增 `ccb-ballot-quorum-gates-change-approval`: 非升级的低影响变更提交 (审核人 9302) -> 设立委员会 [9302 9303] 门槛 2 -> 0 票批准 409 (state voting) -> 9302 投赞成 (1/2 仍 409) -> 9303 投赞成 (2/2 passed) -> 批准成功 `approved`; 另一条驳回不受 CCB 门控. 新增 `ccb-roster-and-ballot-rules-are-enforced`: draft 投票 409; 无名单投票 409; 名单 400 (空/重复/门槛超人数/门槛 0/未知成员 999999); 登记人 9301 自投 403; 非成员 9303 投 403; 非法票 400; 覆写 (赞成改反对 -> approve 0/reject 1/state failed); 0 赞成批准 409; 重置清空表决; 无名单变更直接批准成功 (state none) |
| 全量 PMS 回归 (CLI SQLite) | 155 tests / 1559 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有变更审批/版本/升级/风险/问题用例无回归 |
| 授权路由遍历 | 7 tests / 89 assertions, 0 failures/errors | `authz_test` 遍历路由表, 新路由 `POST /changes/:record_id/ccb` 与 `.../ballot` 均已声明权限, fail-closed 不破 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, "设立/重置变更控制委员会"与"委员会表决"入口及"变更控制表决"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-h09ccb.spec.js` (隔离 `:3100` 后端, 独立空库, 委员会甲/乙双真实上下文 + admin): admin 界面登记低影响变更并"提交独立审批"选委员会甲为审核人 -> admin 点"设立变更控制委员会"填成员 [甲,乙] 门槛 2 (截图 h09ccb-1-roster-dialog.png) -> 台账"变更控制表决"列显示金标"表决中 0/2 / 成员 2" (截图 h09ccb-2-voting-0of2.png) -> 真实 HTTP admin 自投命中 403 -> 甲上下文投赞成后点"批准"命中门控: 弹窗内联 `[role=alert]` 显示"变更控制委员会表决未达通过票数"且返回 409 不关闭 (截图 h09ccb-3-approve-gated-409.png) -> 乙上下文投赞成 -> 列翻绿"表决通过 2/2" (截图 h09ccb-4-passed-2of2.png) -> 甲"批准"成功, 状态 `approved` (截图 h09ccb-5-approved.png); 无未捕获 JS 错误; 截图存 `reports/h09ccb/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无实例, 本轮完全免迁移不新增 DDL); 委员会目前为单轮顺次表决 (达到赞成门槛即通过), 未实现加权票/弃权/法定最低出席人数 (quorum of attendance) 与匿名表决; 未做表决结果向委员会成员的自动通知投递; 未实现批准后按变更自动改写计划基线/任务/费用台账 (归"财务自动应用"与基线联动子项).

边界: 本节把 H09"变更控制委员会多人表决"子项从仅有层级标签推进到 `implemented / local` (可设立 1 至 15 人不重复委员会与赞成门槛 + 成员逐人可覆写表决 + 达到赞成门槛方可批准否则 409 + 登记人/提交人及非成员投票守卫 + 重置清空 + 未设委员会不受门控 + 台账只读表决状态可见); 但 H09 行仍含"跨系统通知""财务自动应用"等未完成子项, 故 H09 整体保持 `partial`, 不因这一子能力上行.

## B09 关口实例检查就绪度只读派生 (本轮增补, 2026-09-28)

设计与关闭口径: 为治理台账"Gate检查与评审"的**关口实例**补齐此前缺失的**实例级**签核就绪度洞察 (既有 `gate-progress` 仅是模板级汇总). 免迁移: 纯读模型在读取时派生, **无新增迁移**, **无新增 kind**, **无新命令**, **无新状态值**, 不改动 `submit!`/`decide!`/`evidence-ready!` 等任何写路径或门控. 在 `governance.gates` 新增纯函数 `gate-read-model`, 对单个 `gate` 记录读其 `:checks` 派生 `{:gate_total 检查项总数 :gate_passed 已满足数(通过或豁免) :gate_waived 豁免数 :blocking_checks 必需且未满足项的 code 向量 :ready_to_sign 布尔(必需项是否全部通过或豁免, 无尾随 ? 以免 JSON 键名污染)}`; 在 `governance/workspace` 的 `cond->` 里加 `(= kind "gate") gates/gate-read-model` 分支, 与既有 risk/action/issue/change 等只读派生同构. 前端关口台账在"阶段"列后新增只读"检查就绪度"列: 绿色/红色"检查 passed/total"徽标 + 蓝色"豁免 N" + 橙色"待满足 <必需未满足编码>" + 就绪时绿色"可签核", 与 `dq-section` 的 `aget row "jsKey"` 派生列渲染同套路.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 55 tests / 649 assertions, 0 failures/errors | 新增 `gate-read-model-derives-check-readiness`: 建含 R-1/R-2 必需 + O-1 可选检查的模板与关口实例 -> 读模型初值 {gate_total 3, gate_passed 0, gate_waived 0, blocking_checks ["R-1" "R-2"], ready_to_sign false} -> `checks!` 令 R-1 通过后 blocking 缩为 ["R-2"] 且 gate_passed 1 -> 再令 R-2 例外放行(带说明)后 {gate_passed 2, gate_waived 1, blocking_checks [], ready_to_sign true}; 可选项 O-1 未通过不计入门控 |
| 全量 PMS 回归 (CLI SQLite) | 156 tests / 1564 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有关口检查/豁免/评审/阻断门控用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, 关口台账"检查就绪度"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-gate-readiness.spec.js` (隔离 `:3100` 后端, 独立空库): admin 真实 HTTP 建含 2 必需 1 可选检查的模板与关口实例 (审核人为合成独立质量审批人) -> 打开 Gate 页签, 台账"检查就绪度"列显示红色"检查 0/3"与橙色"待满足 R-1, R-2"且无"可签核" (截图 gate-readiness-1-pending.png) -> 界面点"填写检查"选 R-1 检查通过 / R-2 例外放行(填说明) / O-1 未通过 (截图 gate-readiness-2-check-dialog.png) -> 重载后同列翻绿"检查 2/3"+蓝色"豁免 1"+绿色"可签核", "待满足"消失 (截图 gate-readiness-3-ready.png) -> 真实 HTTP 回显 `ready_to_sign=true`, `blocking_checks=[]`, `gate_waived=1`, `gate_passed=2`, `gate_total=3`; 无未捕获 JS 错误; 截图存 `reports/gate-readiness/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无实例, 本轮完全免迁移不新增 DDL); 就绪度仅界面只读呈现, 未据此新增/改动任何提交或批准门控 (提交/批准的强制校验仍由既有 `evidence-ready!`/`stage-ready!` 承担); 未做"就绪即自动提交评审"的自动化.

边界: 本增量把 B09 关口实例台账的**签核就绪度可见性**推进到 `implemented / local` (免迁移读取时派生必需项满足度与可签核标记, 界面徽标呈现); B09 行既有的模板级 `gate-progress` 汇总与实例独立签核不受影响, 主机交付清单业务口径仍为待办, 故不改变 B09 整体状态.

## C03 需求追踪链证据发布状态只读派生 (本轮增补, 2026-09-28)

设计与关闭口径: 为治理台账"URS与追踪"的**需求追踪矩阵**补齐此前缺失的**证据发布可见性** (追踪行只显"关联对象"标题, 看不出所引用的证据文档版本是否已发布). 免迁移: 纯读模型在读取时派生, **无新增迁移**, **无新增 kind**, **无新命令**, **无新状态值**, 不改动 `trace!`/`submit!`/`decision!` 等任何写路径或门控, 也**不做任何强制拦截** (追踪链指向未发布证据只界面提示, 是否据此阻断 Gate 仍属"待规则"). 在 `governance.evidence` 新增纯函数 `trace-read-model`, 以 `docs-by-id` (文档 `id` -> 记录) 对单条 `trace` 派生 `{:evidence_status 所引用文档版本原始状态 :evidence_release_state 归一状态 :evidence_released 布尔}`: 目标 `target_kind` 为 `document` 时按确定 `target_id` 命中版本映射 `approved`->`released` (released true), `rejected`->`rejected`, `registered`/`in_review`->`pending`, 版本不存在->`missing`; 目标为 `task` 时不参与发布口径, 记 `n/a` 且 status/released 为 `nil`. 键名去尾随 `?` 以免 JSON 污染. 在 `governance/workspace` 的 `let` 里加 `docs-by-id` 绑定, 在结果 `->` 链里加 `(update :traces #(mapv (partial evidence/trace-read-model docs-by-id) %))`, 与既有 meetings/issues/risks/actions 跨类只读标注同构. 前端"需求追踪矩阵"表在"关联对象"列后新增只读"证据发布"列: 绿"已发布"/金"待发布"/红"已驳回"/橙"证据缺失"/灰"任务关联"/无值灰"—", 用 `(aget row "jsKey")` 渲染.

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 56 tests / 663 assertions, 0 failures/errors | 新增 `trace-read-model-derives-evidence-release-state`: 建需求 + 已登记证据文档 + `verifies` 追踪 -> workspace `:traces` 该条 `evidence_status` "registered"/`evidence_release_state` "pending"/`evidence_released` false -> `:documents :submit` 后仍 "in_review"/"pending"/false -> 独立审核人 `:documents :decision approved` 后翻 "approved"/"released"/true; 另纯函数直测 task 目标 -> "n/a" 且 status/released 为 nil, 引用缺失文档 -> "missing", 命中 rejected 版本 -> "rejected" |
| 全量 PMS 回归 (CLI SQLite) | 157 tests / 1578 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有追踪矩阵/缺链检查/文档发布审批用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, 追踪矩阵"证据发布"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-c03-trace-release.spec.js` (隔离 `:3100` 后端, 独立空库): admin 真实 HTTP 建需求 + 已登记证据文档 + `verifies` 追踪 -> 打开"URS与追踪"页签, 追踪矩阵"证据发布"列显示金色"待发布" (截图 c03-trace-release-1-pending.png), 真实 HTTP 回显 `evidence_release_state="pending"`/`evidence_released=false` -> admin 提交文档发布 (审核人为合成独立质量审批人) 后仍"待发布" -> 该审核人以**自己的真实登录上下文**(第二浏览器上下文)亲自批准发布 -> 重载追踪矩阵同列翻"已发布" (截图 c03-trace-release-2-released.png), 真实 HTTP 回显 `evidence_release_state="released"`/`evidence_released=true`/`evidence_status="approved"`; 无未捕获 JS 错误; 截图存 `reports/c03-trace-release/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无实例, 本轮完全免迁移不新增 DDL); 证据发布状态仅界面只读呈现, 未据此新增/改动任何追踪登记或 Gate 提交门控 (追踪指向未发布证据不拦截); 未做"缺已发布验证证据即阻断需求关闭/关口签核"的联动 (属"待规则").

边界: 本增量把 C03 追踪矩阵的**证据发布可见性**推进到 `implemented / local` (免迁移读取时按所引用文档版本派生发布状态, 界面标签呈现); 但 C03 行仍含"阻塞偏差与 Gate 联动""覆盖率分母正式规则"等未完成子项, 故 C03 保持 `partial`, 不因这一子能力上行.

## C03 URS 需求台账内联追踪状态只读派生 (本轮增补, 2026-09-28)

设计与关闭口径: 为治理台账"URS 需求版本"主视图补齐**逐条需求追踪齐备度的内联可见性**. 上一轮 C03 的"证据发布"列挂在汇总"需求追踪矩阵"面板的追踪行上, 需求台账本身仍看不出"这条需求到底缺设计还是缺验证", 用户须自行对照矩阵. 本轮把这一判断**内联到需求台账每一行**, 与既有汇总矩阵面板互补而非重复. 免迁移: 纯读模型在读取时派生, **无新增迁移**, **无新增 kind**, **无新命令**, **无新状态值**, 不改动 `trace!`/`requirement` 创建/修订等任何写路径或门控, 也**不做任何强制拦截** (缺链需求照样可登记与流转, 是否据缺链阻断关闭/Gate 仍属"待规则"). 在 `governance.evidence` 新增纯函数 `requirement-trace-model [traces-by-req req]`, 以 `traces-by-req` (需求版本 `id` -> 该版本追踪关联向量) 对单条需求派生 `{:trace_design_links satisfies 关联数 :trace_verification_links verifies 关联数 :trace_state 齐备态}`: 无任何关联->`untracked`, 设计+验证皆非空->`complete`, 无设计关联->`missing-design`, 有设计缺验证->`missing-verification`. 关键口径: 追踪按 `requirement_id` 精确命中**具体需求版本 id** (修订产生新版本后旧版本关联不自动迁移, 新版本无关联即重新计为 `untracked`), 与既有 `traceability-report` 的按版本聚合一致. 派生键一律去尾随 `?`. 在 `governance/workspace` 的 `let` 里加 `traces-by-req (group-by :requirement_id (:traces data))` 绑定, 在结果 `->` 链里 (`:traces` 证据发布 update 之后, `collab/enrich-risk-issue-links` 之前) 加 `(update :requirements #(mapv (partial evidence/requirement-trace-model traces-by-req) %))`, 与既有跨类只读标注同构. 前端"URS 需求版本"台账在"验证方式"列后新增只读"追踪状态"列: 绿"追踪完整"/金"缺设计关联"/橙"缺验证关联"/灰"未追踪", 并附 `设计/验证` 关联数 (如 "追踪完整 1/1"), 用 `(aget row "jsKey")` 渲染, 无值显示灰"—".

| 证据 | 结果 | 说明 |
|---|---|---|
| 治理测试 (SQLite) | 57 tests / 677 assertions, 0 failures/errors | 新增 `requirement-trace-state-is-derived-read-only`: 建三条需求 (URS-U 无追踪 / URS-D 仅 satisfies / URS-F satisfies+verifies 齐备) -> workspace `:requirements` 分别派生 `trace_state` "untracked" (0/0) / "missing-verification" (1/0) / "complete" (1/1), 且 URS-F `code` 不漂移; 另纯函数直测仅 verifies 无 satisfies -> "missing-design", 关联挂在别的版本 id -> 当前版本仍 "untracked" |
| 全量 PMS 回归 (CLI SQLite) | 158 tests / 1592 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 既有追踪矩阵/证据发布列/缺链检查/文档发布审批用例无回归 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` 通过, URS 台账"追踪状态"列一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-c03-trace-state.spec.js` (隔离 `:3100` 后端, 独立空库): admin 真实 HTTP 建三条需求 (A 无追踪 / B satisfies+verifies / C 仅 satisfies) + 证据文档 -> 打开"URS与追踪"页签, "URS 需求版本"台账"追踪状态"列显示 A "未追踪 0/0", B "追踪完整 1/1", C "缺验证关联 1/0" (截图 c03-trace-state-1-states.png), 真实 HTTP 回显 `trace_state`/`trace_design_links`/`trace_verification_links` 一致 -> 对 C 追加一条 `verifies` 追踪 -> 重载同列翻"追踪完整 1/1" (截图 c03-trace-state-2-complete.png), 真实 HTTP 回显 complete; 全程只读不门控, A 需求 `status` 仍 "registered" 无漂移; 追踪仅需 `pms:project:edit` 无需独立审批人故单上下文即可; 无未捕获 JS 错误; 截图存 `reports/c03-trace-state/` |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无实例, 本轮完全免迁移不新增 DDL); 追踪状态仅界面只读呈现, 未据此新增/改动任何追踪登记或需求关闭/Gate 门控 (缺设计或缺验证不拦截); 未做"缺链即阻断需求关闭/关口签核"的联动 (属"待规则").

边界: 本增量把 C03 需求台账的**逐条追踪齐备度内联可见性**推进到 `implemented / local` (免迁移读取时按需求版本已登记 satisfies/verifies 关联派生, 界面标签呈现, 与汇总矩阵面板互补); 但 C03 行仍含"阻塞偏差与 Gate 联动""覆盖率分母正式规则"等未完成子项, 故 C03 保持 `partial`, 不因这一子能力上行.

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

## 品牌更名与完整流程演示视频 (2026-09-24)

品牌: 界面产品名由 "若依" 改为 "红创PMS" (登录页, 侧栏/顶栏红色品牌标记, 页面标题 `... - 红创PMS`, 首页欢迎语, 页脚, 头部显示当前用户昵称). 迁移 `202609240003-rebrand` (双库) 只在根部门与管理员昵称仍为若依默认值时改为 "红创科技" / "红创管理员", down 只在值未被再次修改时回退; E2E 登录辅助等待新品牌文本. 代码包名 `com.ruoyi.*` 与 `ruoyi_token` 等内部标识不变 (不影响界面, 改名会波及全部命名空间与既有会话).

演示视频: 分镜与字幕见 [15-demo-video-storyboard.md](15-demo-video-storyboard.md) (11 个功能节点章节卡, 48 个镜头, 90 条字幕, 数据源 `scripts/demo-video/storyboard.js`). `tests/e2e/pms-demo-video.spec.js` 在隔离 `:3100` 后端上用同一个设备订单项目 (本次 `DEMO-C992P`) 从平台模板走到正式关闭, 按章节分段录制 (项目经理与独立审核人各一个 1600x900 录像上下文, 注入可见光标/点击涟漪, 表单逐字输入, 跨章节状态写 `state.json`, 每章前备份 SQLite 检查点, 中断后可从未完成章节续录); `render_cards.js` 渲染片头/章节卡/片尾与带章节导航栏的背景; `music.py` 逐音符合成原创配乐 (无采样与第三方素材); `compose.py` 剪辑合成底部字幕, 账号标签, 进度条与配乐.

录屏中发现并修复的问题:

1. 产品缺陷: 实际工时, 项目费用与需求治理页的弹窗在点击时固化计划读模型里的任务/节点选项; 主读模型先返回而计划读模型未返回时就能点击 "提交实际工时", 得到空的 WBS 任务下拉 ("暂无数据", 录屏第 9 章首次暴露). 新增 `shared/first-load`: 首次加载时等计划读模型也返回后再渲染可操作内容 (工程交付页原有同样处理), 刷新时保留旧数据不闪烁, 计划读取失败不阻断主内容. 修复后该章在负载下一次通过.
2. 字幕与画面对齐: 实测录屏链路在高负载下让录像自身的时间与墙钟不均匀地偏离并累积 (同一章内镜头起点的偏差从 +0.3 秒增长到 +18 秒, 录像约比墙钟长 7%), 固定偏移无法对齐. 改为录屏时每帧底边画 8 像素时间码 (每 100 毫秒一次: 起始位 + 24 位毫秒 + 偶校验), 合成时逐帧读出并把每个镜头起止与每条字幕映射到真正显示该时刻的那一帧, 再裁掉时间码条; 镜头开始/字幕切换前还等待可见加载指示消失. 抽帧核对章程提交, 模板应用, Gate 评审, SIT/FAT 等切点, 字幕与画面一致.
3. 用例定位: 结项页 "正式关闭审批" 同时匹配面板标题与说明文字, 改为按标题角色定位.

| 证据 | 结果 | 说明 |
|---|---|---|
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (品牌与 first-load 修改后) |
| 迁移 | SQLite 隔离库实际执行 `202609240003`, 管理员昵称与根部门显示新名称 | 登录后头部显示 "红创管理员", 页面标题 "首页 - 红创PMS" |
| 演示视频录屏 (Playwright, Linux Chromium, 隔离 `:3100` 后端) | `pms-demo-video.spec.js` 1 passed (27.0m, 11 章一次跑完) | 54 个片段覆盖 48 个镜头且与分镜顺序一致, 项目最终状态 closed, 无未捕获 JS 错误; 15 段录像共 61632 帧, 时间码可读 59849 帧 |
| 成片 | `hc-pms-demo.mp4` 798.0 秒 (13 分 18 秒), 1920x1080 25 fps H.264 + AAC 48 kHz 立体声, 62.0 MiB; `hc-pms-demo.srt` 90 条 | 13 张卡片 (片头 4 秒, 11 张章节卡各 3.2 秒, 片尾 7 秒) + 54 个录屏片段; 录屏素材 1157.0 秒经长表单加速压到 751.8 秒 (90 个字幕区间中 35 个加速, 最高 2.32 倍, 加速期间导航栏下方显示 "快进 N.Nx"); 配乐 EBU R128 实测 -19.9 LUFS, LRA 1.1 LU, 峰值 -6.7 dBFS; 抽帧核对 11 张章节卡, 各章录屏, 字幕可读, 时间码条已裁掉 |
| 浏览器回归 (同一隔离后端, 覆盖工时/治理/组合/演示流程) | 首轮 12 passed / 3 failed (19.4m, 与 720p 预览转码并行); 复跑 `pms-workbench` 完整交付 1 passed (2.5m), `pms-c-portfolio` 2 passed (1.6m), `pms-demo-flow` 1 passed (6.8m) | 首轮: `auth`, `pms-b-gates-fieldwork` 2, `pms-b05`, `pms-b05b`, `pms-c-portfolio` 追踪侧, `pms-e-progress`, `pms-workbench` 其余 4 例通过. 三个失败均非本轮产品改动: `pms-workbench` 完整交付与 `pms-demo-flow` 在 CPU 被转码占满时确认按钮点击未发出请求 (超时), 无负载复跑通过且未改用例; `pms-c-portfolio` 平台侧暴露两处用例对共享库累积数据的依赖并修正: 下拉未过滤时长用户列表被虚拟滚动裁剪 (改为可搜索下拉先按标签过滤), 研发费用池预览断言单项目独得 1000.00 (同一期间残留他次运行已批准工时时会分摊到多个项目, 改为断言本项目在分摊行中且各行金额合计 1000.00). `pms-demo-flow` 复跑同时刷新了 `reports/demo-flow/` 38 张新品牌截图 |

未执行 (如实记录): 本机 macOS Chrome 复验; 视频无配音 (只有字幕与配乐); 视频与录屏素材在 `reports/demo-video/` (不入库, 本机文件夹同步了成片与字幕).

## S1-S3 权限, 组织与审批流程整改 + 组织/菜单/流程配置演示视频 (2026-09-25)

范围: [16 权限, 组织与审批流程整改](16-permission-org-workflow.md) 的 S1 安全与权限底线, S2 组织/数据权限/菜单, S3 审批策略可配置与统一待办 (实施记录见该文档). 迁移 `202609250001-security-baseline`, `202609250002-org-data-scope`, `202609250003-approval-chain` (双库). 演示视频分镜见 [17-config-video-storyboard.md](17-config-video-storyboard.md).

| 证据 | 结果 | 说明 |
|---|---|---|
| 后端全量回归 (CLI SQLite, 全新库) | 522 tests / 3102 assertions, 0 failures/errors (最终代码复跑) | `JDBC_URL=jdbc:sqlite:<新库> FLOWABLE_JDBC_URL='jdbc:h2:mem:<名>;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' clojure -M:test -d test/clj`; 含新增 `authz_test` (遍历路由表, 每个系统/办公接口声明权限), `online_test`, `org_data_scope_test` (4 tests / 75 assertions: 五种数据范围并集, 越权 403, 部门祖级/同级重名/删除约束), `pms_approval_chain_test` (4 / 52: 三级策略解析, 或签/会签, 金额阈值, 提交人排除与 403, 末级通过生效, 策略快照, 旧单人审核 409), `bpm_e2e_fixes_test` (9 / 120: 办理/查看归属, 挂起/激活, 请假/报销发起, 身份同步增删) (参数配置列表检索修复并补断言后复跑) |
| PMS 回归 (MySQL 8.0, 全新库) | 140 tests / 1405 assertions, 0 failures/errors | `PMS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3306/hc_pms_test?...' clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` (含审批链测试) |
| 迁移 (MySQL 8 + SQLite) | 全新 MySQL 库启动时三个迁移全部执行; 两库 `down 202609250003/2/1` 后 `migrate` 均成功 | MySQL: `pms_config_record_kind_chk` 含 `approval-policy`, 菜单 48 权限为 `bpm:instance:manage`, `sys_role_dept` / `pms_approval_flow` / `pms_approval_step` 存在, 接口冒烟 (`/api/pms/approval-policies`, `/api/system/role/deptTree/2`, `/api/system/user/deptTree`) 返回 200. down 会删除审批流/审批步骤与审批策略记录 (SQLite 探针库 1 条策略 + 1 条审批流), 属预期回退行为 |
| 前端编译 | 0 warnings | `npx shadow-cljs compile app` (4035 files) |
| 专项浏览器 (Playwright, Linux Chromium, 隔离 `:3100` 后端) | `security-baseline` 3 passed, `org-data-scope` 1 passed, `approval-chain` 1 passed, `role-crud` 6 passed, `user-crud` 5 passed | 截图: `reports/s1-security/` (登录无验证码, 锁定与解锁, 普通员工菜单, 在线用户只显示会话编号, 强退回登录页), `reports/s2-org/` (部门负责人, 角色数据权限, 部门经理只看本部门, 403 守卫, 菜单收回, 隐藏菜单可路由), `reports/s3-approval/` (审批策略编辑器, 发布, 提交后逐级进度, 部门负责人与财务在我的待办审批, 三级通过后已批准); `role-crud` 新增 "分配菜单权限: 父子联动, 半选上级一并授权, 取消的按钮不授权" 用例 |
| 全量浏览器套件 (同一隔离后端单库连续运行) | 首轮 95 passed / 14 failed / 2 skipped (43.4m); 修复后失败用例复跑 13 个全部通过; 第二轮全量 106 passed / 3 failed / 2 skipped (44.4m), 其中 `pms-workbench` 费用用例单独复跑通过, `pms-e-progress` 放宽全量扫描调用超时后复跑 1 passed (2.6m) | 对照: S1 前基线工作树 (同一 SQLite 库副本, `:3200`) 已有 12 个失败 (办公业务流, 设计器/前端/流程/完整演示等截图报告, 角色 x2, 用户, `pms-c05`). 首轮 14 个失败的归因与处理见下文; 仅 `pms-c05` 批量下载的中文文件名断言仍失败 (Linux headless Chromium 对 blob 非 ASCII 文件名返回 "download", 增量4 已归因; 本轮尝试延后释放 blob URL 无效, 已还原, 未改断言). 第二轮另两个失败: `pms-workbench` 提交成本版本时 30 秒无响应, 同时段后端日志有定时任务日志写入的 SQLite 忙锁 (进度扫描与 "执行一次" 并发), 无负载复跑通过; `pms-e-progress` 的同步全量扫描 `POST /api/pms/scan` 在共享库 66 个在途项目时实测 31.7 秒 (约 0.5 秒/项目), 超过请求默认 30 秒, 该调用单独放宽到 180 秒 |
| 配置演示视频录屏 | `config-demo-video.spec.js` 1 passed (4.7m, 全新 SQLite 库 `:3300` 与独立 Flowable 库) | 5 章 17 个镜头与分镜顺序一致, 8 段录像共 12631 帧 (时间码全部可读), 无未捕获 JS 错误; 周经理视角只见授权菜单与研发一部人员, 无删除按钮, 角色管理 403; 收回 "部门管理" 后切换页面菜单消失且地址访问 403; 三级审批由周经理与财务在界面通过, 事业部负责人经真实 HTTP 批准 (不入镜) |
| 成片 | `hc-pms-config-demo.mp4` 207.2 秒 (3 分 27 秒), 1920x1080 25 fps H.264 + AAC 48 kHz 立体声, 16.4 MiB; `hc-pms-config-demo.srt` 37 条 | 片头 4 秒, 5 张章节卡各 3.2 秒, 片尾 7 秒 + 17 个录屏片段; 录屏素材 184.1 秒经长表单加速到 180.2 秒 (37 个字幕区间中 5 个加速, 最高 1.21 倍); 配乐 EBU R128 实测 -19.8 LUFS, LRA 1.5 LU, 峰值 -6.1 dBFS; 抽帧核对章节卡, 菜单树勾选 (取消 "用户删除"), 部门经理视角, 403, 策略编辑器三级规则, 待办审批弹窗, 三级全部通过与 "已批准" |

回归与录屏中发现并修复的问题:

1. 角色 "分配权限" 弹窗在界面上无法使用 (菜单树从未加载, 勾选事件未注册), 已修复并支持父子联动与半选上级授权, 保存后刷新本人权限而不整页刷新 (见 16 号文档 S2 实施记录). "分配用户" 批量授权/取消按查询参数提交 (原先 400).
2. 列表刷新丢失检索条件: 角色, 用户列表在新增/修改/删除后改为沿用搜索条件刷新; 参数配置列表的检索条件从未生效 (后端把字符串键查询参数直接并入关键字参数, 前端参数名也不一致), 已修复并补单元测试, 刷新沿用检索条件.
3. 测试路由构造 (未关闭冲突检查) 暴露 `/api/pms/approvals/policies` 与 `/approvals/:flow_id` 路径冲突, 改为 `/api/pms/approval-policies`.
4. 审批策略列表 "审批级别" 列溢出遮挡状态列, 改为定宽列并自动换行.
5. 合成脚本 `compose.py` 在 `DEMO_VIDEO_DIR` 为相对路径时 concat 列表路径重复, 改为绝对路径.
6. 用例健壮性 (不放宽断言): 共享 E2E 库累积 200 多个用户后 antd 虚拟滚动只渲染前几项, 24 个 PMS 用例的下拉选择改为可搜索时先按标签过滤再选; 5 个用例的日期辅助函数由 UTC 日期改为本地日期 (北京时间 0-8 点 UTC 日期比后端 "今天" 早一天, 导致逾期天数与快照日期断言失败); antd 两字按钮带空格 ("审 批", "通 过") 改为正则匹配; antd 时间线复用 `ant-steps` 类名, 审批进度加 `approval-flow` 容器定位; 角色/用户/参数 CRUD 新记录不在第一页时先检索; 办公与截图报告类用例 (办公业务流, 设计器, 前端报告, 流程设计器, 完整演示, 更新报告) 按当前界面更新: 审批弹窗标题 "通过 · 节点名", 流程模型设计器为独立页面 (原为弹窗), 全新库无流程表单时经真实 HTTP 建一张通用表单, 请假模型内嵌表单字段为 "请假天数 / 请假原因".

未执行 (如实记录): 本机 macOS Chrome 复验; 视频无配音 (字幕 + 原创配乐); 事业部负责人第三级审批经真实 HTTP 完成而未入镜; 视频与录屏素材在 `reports/config-video/` (不入库, 本机文件夹同步成片与字幕). 性能提示: 全量进度扫描是同步逐项目处理, SQLite 开发库上约 0.5 秒/项目, 项目数上百时手动触发的 HTTP 扫描会超过常见网关超时 (定时任务不受影响), 未在本轮优化.

## H12 承诺成本与预算控制 (本轮增补, 2026-09-27)

设计与关闭口径: 兑现矩阵 H12 长期列为"承诺与实际分列不双计 / 超支审批样例仍待补齐"的一环. 这是财务域一条**新增迁移**的纵切(不同于近期一批免迁移增量): 新表 `pms_cost_commitment`(承诺台账, 状态机 draft/submitted/approved/rejected/released/cancelled, 含 currency/base_currency/exchange_rate/gross_minor/base_minor/released_minor/control_note/reviewer_id) 与 `pms_budget_control_rule`(预算控制规则, baseline=estimate|budget, action=warn|require_approval|block, threshold_pct 5-500, project_id 可空=系统默认), SQLite 与 MySQL 两套迁移同步, 每条语句以 `--;;` 分隔, 并种子两条系统默认规则(budget warn@80、budget block@100). 领域拆为 `finance-commitment`(状态机命令 create!/update-draft!/submit!/review!/release!/cancel!) 与 `finance-budget`(纯函数 `evaluate` 计算占用率 = round(100×(已承诺 + 本次base)/已批准基线总额), `active-rules`/`upsert-rule!`/`disable-rule!`/`list-rules`). 关键门控: `submit!` 调 `evaluate-and-gate!`, 命中 action=block 的启用规则且未携带 `override_block` 时返回 409 并保持 draft, 携带 override_block 强制放行进入 submitted 且把理由写入 control_note; 基线不可比(无已批准基线或总额为0)不门控. 占用口径 `commitment-consumed` 只累加 status IN (submitted,approved) 的 base_minor, 故 released/cancelled 不再计入, 部分转实付 `release!` 按 amount 累加 released 并保证剩余=base−released 守恒、不双计. GET `/finance` 追加 `commitments` 与 `budget_control`(以 budget 基线、本次追加0评估的当前快照). 前端"项目费用"页签新增第三子页签"承诺与预算控制": 承诺台账(登记/修改/提交/强制放行/批准/转实付/取消) + "预算占用评估"面板(已批准预算/已承诺/剩余/占用率·决策 + 触发规则标签) + 预算控制规则台账(系统默认与项目层并存). 关键前端坑: 后端 `:decision` keyword 经 cheshire 序列化为 JSON 字符串, 前端 `js->clj :keywordize-keys true` 只关键字化键不关键字化值, 故面板决策 `case`/`get` 必须用字符串键("block"/"warn"/"require_approval"/"ok"), 否则决策标签与颜色为空.

| 证据 | 结果 | 说明 |
|---|---|---|
| H12 财务承诺测试 (CLI SQLite) | 7 tests / 53 assertions, 0 failures/errors | `clojure -M:test -n com.ruoyi.pms-finance-commitment-test`: 状态机与约束/币种汇率校验/草稿编辑与取消/预算评估与阻断/warn规则与强制放行/规则upsert/概览暴露承诺与控制快照 |
| 全量 PMS 回归 (CLI SQLite) | 147 tests / 1489 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'` 全新库通过, 新增两表迁移与既有四算/工时/收尾用例无回归 |
| 前端编译 | 5 files / 0 warnings | `npx shadow-cljs compile app` 通过, 承诺台账/占用评估面板/预算规则台账与决策字符串键修复一并编译 |
| Chrome 浏览器 (Playwright) | 1 passed | `pms-h12.spec.js` (隔离 `:3100` 后端, 独立空库, 双真实上下文): admin 经第二审批人建立已批准预算基线 1000.00 CNY -> 界面登记承诺 CMT 1200.00 (截图 h12-1) -> 界面提交命中系统默认 block@100% 真实 409, 弹窗内红色告警"预算占用率 120% 触发阻断规则, 需上级修改规则或勾选强制放行才能提交"(截图 h12-2) -> 强制放行(理由"总经理特批: 战略设备锁定产能")进入待审批, 面板显示"占用率 120% · 触发阻断"与 warn@80%/block@100% 触发标签, admin 无批准入口(截图 h12-3) -> 独立审批人第二上下文批准(截图 h12-4) -> 部分转实付 700.00 剩余 500.00 状态保持已批准(截图 h12-5) -> 新增项目层规则 95%需上级审批与系统默认并存可见(截图 h12-6); GET `/finance` 二次确认 status=released 前为 approved、released=700.00、control_note 含放行/特批/战略、budget_control.comparable=true、consumed_minor=120000; 全程无 pageerrors; 截图存 `reports/h12/` |

本轮未执行 (如实记录): MySQL 迁移与回归仅在 SQLite 本地跑通, 双库迁移文件已同步但本机无 MySQL 实例未实跑; 预测完工成本 EAC(基于绩效/剩余估算的完工预测)未实现, 面板只做"已承诺占用率"不做 EAC 曲线; 税额/收入确认/多币种对账/封期/开票回款付款结算归 H13 未触碰; 强制放行(override_block)目前只留痕不做额外上级会签门槛, 未接通放行后的通知投递; 未与外部 ERP 总账同步.

边界: H12 交付的是"承诺与实际分列不双计 + 预算基线口径占用率门控(warn/require_approval/block) + 强制放行留痕 + 部分转实付剩余守恒"这一条本地闭环, 覆盖矩阵 H12"已批准预算/已承诺未发生/实际与剩余区分/阈值超支进入批准流程"的 `implemented / local` 子集; 但 H12 行仍含"预测完工成本 EAC 公式""税额/多币种/封期对账(与 H13 交叠)"等未完成子项, 故按整行完整目标看仍属工程验证, 不等于生产财务签收.

## H13a 会计期间封期与费用版本门控 (本轮增补, 2026-09-28)

设计与关闭口径: 兑现矩阵 H13 长期列为"封期"的一环, 拆成免迁移纵切 H13a. 不新增表或迁移, 直接复用平台级 `period-lock` 配置 (config kind, 状态 draft -> locked -> retired, 见平台配置合同与 `pms_config_test` 的封期生命周期用例). 工时侧早已用 `finance_time/period-open!` 按同一 `config/published-by-code q "period-lock" period` 门控; 本轮把同一口径扩展到成本版本: 领域 `finance_cost/period-open!` 在写事务内检查成本版本所属 `period` 是否处于 locked, 命中返回 409. 门控覆盖六条成本写路径 create/add-entry/delete-entry/submit/revise/cancel; 独立审批 `review!` 有意不受门控 (金额提交时已冻结, 与工时封期口径一致, 不让审批链因封期卡死). GET `/finance` 读模型追加 `locked_periods` 供前端渲染. 前端成本版本台账"期间"列对已封账期间显示红色"已封账"徽标并在面板顶部展示警告横幅; 锁定/解锁入口仍统一在平台配置页, 项目费用页只呈现状态不重复设置.

关键前端坑 (复用价值): antd Table 的自定义列 `:render` 回调由 JS 侧调用, 其返回值必须是 React 元素; 返回 Reagent hiccup 向量 (如 `[:span ... [antd/tag ...]]`) 不会被自动转换, 会导致该单元格渲染为空 (非锁定分支返回字符串则正常, 故只在封期后才暴露). 必须用 `r/as-element` 包裹 hiccup 分支, 与本仓库 state-column/budget-rules 等自定义列一致. 另: 编译产物把中文以 `\uXXXX` 转义输出, 用原始中文 grep bundle 会假阴性.

| 验证项 | 结果 | 证据 |
|---|---|---|
| H13a 财务封期测试 (CLI SQLite) | 13 tests / 63 assertions, 0 failures/errors | `clojure -M:test -n com.ruoyi.pms-finance-test`: 新增 `period-lock-gates-cost-version-writes` (锁定 2026-09 后 create/add-entry/delete-entry/submit/cancel/revise 均 409, 新期间 2026-10 可建, overview 暴露 locked_periods 含 2026-09, retire 后 submit 成功且不再含) 与 `period-lock-does-not-gate-independent-cost-review` (提交后锁定, 独立 review! 仍 200 approved) |
| 全量 PMS 回归 (CLI SQLite) | 149 tests / 1500 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` (本轮改动仅前端 render 包裹, 后端回归保持既有绿) |
| 前端编译 | 4035 files, 0 warnings | `npx shadow-cljs compile app` (修复后 5 files recompiled) |
| 浏览器 E2E | 1 passed | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-h13a.spec.js` (隔离 :3100 + 独立 /tmp/h13a-e2e.db 冷启动): 界面新建 2026-09 成本版本草稿+真实条目 -> 真实 HTTP 锁定 2026-09 -> 刷新后"已封账"徽标与警告横幅真实可见 -> 界面提交命中封期门控真实 409 且弹窗内联告警不关闭 -> 真实 HTTP 回显草稿仍 draft 无副作用 -> 真实 HTTP 解锁 -> 徽标消失且提交成功 status 回显 submitted; 5 张真实截图 reports/h13a/*.png |

本轮未执行 (如实记录): MySQL 迁移与回归本机无实例未实跑 (本增量为免迁移, 无新迁移文件, 但 `period-lock` 配置表本身的双库回归仍按既有 config 验证边界); 封期与结算/税额/汇率的联动未做; 工时/承诺/费用三处封期门控的口径未统一抽象 (工时与费用各自 `period-open!`); 界面未提供在项目费用页直接锁定/解锁的入口 (按设计统一到平台配置页).

边界: H13a 交付的是"已封账会计期间的成本版本六条写路径门控 409 + 独立审批不受门控 + 读模型暴露 locked_periods + 界面封账徽标与横幅可见 + 锁定/解锁留痕"这一条本地闭环, 覆盖矩阵 H13"封期"子项的 `implemented / local`; H13 行的税额/收入确认/汇率/结算对账仍属 planned, 不等于生产财务签收.

## H13c 承诺成本纳入会计期间封期 (本轮增补, 2026-09-28)

设计与关闭口径: 承接 H13a 的"封期"闭环, 把承诺成本纳入同一会计期间门控. 与 H13a 免迁移不同, 承诺原先不携带期间, 故本轮新增一份迁移为 `pms_cost_commitment` 增加可空 `period VARCHAR(7)` 列, 并按 `created_at` 回填历史承诺 (SQLite `substr(created_at,1,7)` / MySQL `DATE_FORMAT(created_at,'%Y-%m')`), 双库 `.up.sql` 每条语句后均带 `--;;` 分隔符, `.down.sql` 对称 DROP. 领域 `finance_commitment/period-open!` 复用 `config/published-by-code q "period-lock" period`, 命中 locked 期间返回 409; 门控覆盖五条承诺写路径 create/update-draft/submit/release/cancel (update-draft 同时检查原期间与新期间), 独立审批 `review!` 有意不受门控 (与成本版本/工时口径一致). 承诺登记 `period` 为可选: 留空默认当前月, 填写强制 YYYY-MM + `YearMonth/parse` 校验. 前端承诺表单增加"会计期间"输入, 承诺台账"期间"列对已封账期间渲染红色"已封账"徽标并在面板顶部展示警告横幅, 复用 H13a 的 `r/as-element` 自定义列写法与 `locked_periods` 读模型.

| 验证项 | 结果 | 证据 |
|---|---|---|
| H13c 承诺封期测试 (CLI SQLite) | 9 tests / 60 assertions, 0 failures/errors | `clojure -M:test -n com.ruoyi.pms-finance-commitment-test`: 新增 `period-lock-gates-commitment-writes` (锁定 2026-08 后 create 同期间/update-draft/submit 均 409, 新期间 2026-11 可建, overview 暴露 locked_periods 含 2026-08, retire 后 submit 成功) 与 `period-lock-does-not-gate-independent-commitment-review` (提交后锁定, 独立 review! 仍 approved); 迁移 `202609280001-commitment-accounting-period` 在测试库正常应用并回填 |
| 全量 PMS 回归 (CLI SQLite) | 151 tests / 1507 assertions, 0 failures/errors | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` (较 H13a 的 149/1500 增加 2 tests/7 assertions, 即本轮两条封期用例) |
| 前端编译 | 4035 files, 5 compiled, 0 warnings | `npx shadow-cljs compile app` |
| 浏览器 E2E | 1 passed | `BASE_URL=http://localhost:3100 npx playwright test tests/e2e/pms-h13c.spec.js` (隔离 :3100 + 独立 /tmp/h13c-e2e.db 冷启动): 界面登记 2026-08 采购承诺草稿 -> 真实 HTTP 锁定 2026-08 -> 刷新后承诺台账"期间"列红色"已封账"徽标与警告横幅真实可见 -> 界面提交命中封期门控真实 409 且弹窗内联告警"期间 2026-08 已封账, 不能再登记或变更该期间的承诺"不关闭 -> 真实 HTTP 回显草稿仍 draft 且 period 回显 2026-08 无副作用 -> 真实 HTTP 解锁 -> 徽标消失且提交成功 status 回显 submitted; 5 张真实截图 reports/h13c/*.png |

本轮未执行 (如实记录): MySQL 迁移与回归本机无实例未实跑 (双库迁移文件已同步, SQLite 侧已实跑回填); 封期与结算/税额/汇率的联动未做; 工时/费用/承诺三处 `period-open!` 仍是各自领域内的同名助手, 未抽出统一抽象层 (行为口径已一致); 界面未在项目费用页提供直接锁定/解锁入口 (按设计统一到平台配置页).

边界: H13c 交付的是"承诺成本纳入已封账会计期间的五条写路径门控 409 + 独立审批不受门控 + 承诺携带会计期间并回填历史 + 界面封账徽标与横幅可见"这一条本地闭环, 使矩阵 H13"封期"子项在工时/费用/承诺三类写路径上口径统一; 仍为 `implemented / local`, 不等于生产财务签收.

## H08 风险应对策略覆盖度只读派生 (本轮增补, 2026-09-28)

给治理工作台读模型新增派生字段 `risk_response_coverage`, 在"需求与治理 > 风险与问题"页签风险台账之后放一块"风险应对覆盖度"面板, 按每个风险的最新有效版本统计 PMI 四类应对策略 (规避 avoid / 转移 transfer / 减轻 mitigate / 接受 accept) 的声明情况: 总数, 已声明数, 未设定数, 覆盖率百分比与四类各自计数. 未选策略的风险只计入分母不计入分子, 从典型风险库实例化的风险默认不带策略同样计入未设定. 这是免迁移, 无新命令, 无新 kind 的只读派生 (与 C02v2 验证方式覆盖度, C06b 证据发布覆盖度同一模式), 不落库不投递, 不改变任何风险状态.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 58 tests / 696 assertions, 0 failures/errors (新增 `risk-response-strategy-coverage-is-derived-read-only` 1 例 19 断言: 四类计数与 75%/80% 覆盖率, 库实例化计入未设定分母, 重复读取稳定, 既有风险仍登记态且策略不漂移) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 159 tests / 1611 assertions, 0 failures/errors, workspace 新增 `:risk_response_coverage` 未造成既有风险/问题/审批用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h08rc.spec.js` 1 passed, 无未捕获 JS 错误: 界面登记转移/规避/不选三条风险 -> "风险应对覆盖度"面板显示"风险总数 3 / 已声明应对策略 67% / 未设定 1 / 规避·1 转移·1 减轻·0 接受·0" (截图 h08rc-1-coverage-panel.png); 再登记一条声明减轻 -> 升到"总数 4 / 75% / 减轻·1" (截图 h08rc-2-after-declare.png); 真实 HTTP GET governance 二次确认 `risk_response_coverage` 的 total/declared/undeclared/`['coverage-pct']`/`['by-strategy']` 与界面一致, 既有风险 `status=open` 且 `response_strategy=transfer` 不漂移 |

边界: 该面板是"是否声明应对策略"的只读覆盖度聚合, 反映登记完整性而非"措施是否已落实/有效", 也不与缓解门控或复审联动拦截; H08 行仍为 `partial`, 升级通知投递与跨项目风险汇总升级仍待实现.

## H08/C10 风险应对措施落实为可追踪预防行动项 (本轮增补, 2026-09-28)

新增一条写命令 `POST /risks/:rid/mitigation-action`, 把风险登记的应对措施 (`mitigation`) 落实为一条有负责人和到期日、可独立追踪的行动项, 让"措施"从静态文本变成可跟进的待办 (推进 C10"预防措施与风险追踪"与 H08"措施"). 复用既有 `action` kind, 不新增治理记录类型, 因此无数据库迁移; `source_risk_id` 随行动 payload 持久化保留来源可追溯. 门控: 仅 `open`/`mitigated` 风险可执行 (其余 409), 要求 `pms:project:edit`, 跨项目或缺失 `rid` 返回 404. 字段继承: `title` 缺省回退"落实预防措施: <风险标题>", `owner_id` 与 `due_date` 缺省沿用风险自身值, 显式传入则覆盖; 命令不改动风险本身状态. 读取层新增 `enrich-action-source-links` 只派生 `action_source_risk_id`/`action_source_risk_title` (会议行动无 `source_risk_id` 故不写这两键), 键名不带尾随问号. 前端风险台账新增"落实预防措施"入口 (仅 open/mitigated 且可编辑可见, 对话框预填措施), "会议行动"台账新增"来源风险"列以 purple 标签回显来源风险标题 (非风险来源显示灰字). 落库的行动复用既有完成/独立验证/转 WBS 任务全生命周期.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 59 tests / 716 assertions, 0 failures/errors (新增 `risk-mitigation-materializes-tracked-prevention-action` 1 例 20 断言: 缺省继承风险责任人/到期日与 `source_risk_id` 且新行动 `open`/风险不漂移, 缺省标题回退"落实预防措施: ", 显式 `owner_id`/`due_date` 覆盖, 读模型 `action_source_risk_id`/`action_source_risk_title` 标注而会议行动该键缺失, 行动转 WBS 任务后 `converted`, 已 materialize 风险 409, 跨项目风险 404) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 160 tests / 1631 assertions, 0 failures/errors, 新增 `[risks :mitigation-action]` 命令与路由及 `enrich-action-source-links` 未造成既有风险/问题/行动/审批用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h08pa.spec.js` 1 passed, 无未捕获 JS 错误: HTTP 建项目+一条 2x3=6 (不触发升级) 带措施的风险 -> 界面"需求与治理 > 风险与问题"风险行点"落实预防措施"打开对话框并预填措施文本 (截图 h08pa-1-risk-button.png) -> 填写行动标题保存生成 open 行动 -> "会议行动"台账该行动行"来源风险"列以 purple 标签回显来源风险标题 (截图 h08pa-2-action-source.png); 真实 HTTP GET governance 二次确认该行动 `source_risk_id`/`action_source_risk_id`/`action_source_risk_title` 指回风险且继承 `owner_id`/`due_date`, `status=open`, 风险 `status` 不变, 会议行动无来源标注 (`action_source_risk_title` 为 falsy) |
| 路由授权 | 新增 `/risks/:record_id/mitigation-action` 与其它 `command-route` 结构一致, `authz_test` 遍历路由表要求全部声明 `:perms` 仍通过 (领域层 `k/mutate!` 挂 `pms:project:edit`) |

边界: 该命令负责"措施一键落实为可追踪行动并在台账可视来源"这一条最小闭环; 暂不做措施到多条行动的批量拆分, 也不在风险侧统计其派生行动完成情况 (该边界已于 2026-09-29 后续子增量"风险侧预防措施落实情况只读派生"闭合, 见本节末), 行动全部完成后提示风险可缓解等状态联动, 升级通知投递与 MySQL 回归仍待实现; H08 行仍为 `partial`, C10 行保持 `implemented / local`.

## C01 需求验证方式与验证关联对齐只读派生 (本轮增补, 2026-09-28)

设计与关闭口径: 兑现此前 C01/C02 遗留的"覆盖度只反映是否声明验证方式而非已配齐验证证据"边界, 把需求"已声明的验证方式"与"实际是否已挂上 verifies 验证证据关联"做只读交叉核对. 沿用"给治理台账加只读派生洞察"套路(承 C04 归集/H18c 剔除/C02v2 覆盖度/C03c 追踪状态), **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind**. (1) 行内派生: 在既有 `requirement-trace-model` 里对每条需求追加 `verification_alignment`——未声明四类验证方法之一者 `not-applicable`, 已声明且该具体版本命中至少一条 `verifies` 关联者 `aligned`, 已声明却尚无 verifies 关联者 `declared-unverified` (按 `requirement_id` 精确命中版本 id, 修订不继承旧版关联). (2) 聚合派生: `governance.evidence` 新增纯函数 `verification-evidence-alignment`, **同时接收原始 `requirements` 与 `traces`** (因 workspace 的 `assoc` 块读的是未逐条 enrich 的原始 `(:requirements data)`, 不能依赖行内 `trace_verification_links`), 内部自行过滤 verifies 关联; 以每个 `code` 最新有效版本为统计单位 (复用 `store/latest` 去重), 剔除最新版本 `discarded` 者 (沿用 H18c), 分母只取已声明验证方式的最新版需求, 输出 `{declared, aligned, gap, alignment-pct}` (`alignment-pct` 为 `aligned/declared` 四舍五入整数, `declared` 为 0 给 0). 派生键无尾随 `?`; `:alignment-pct` 经 `clj->js` 后是字面 `"alignment-pct"`, 故前端用 keyword 取值而 E2E 原始 JSON 用 `['alignment-pct']` 中括号取值. workspace `governance.clj` 在 `:verification_coverage` 之后 `assoc :verification_evidence_alignment (evidence/verification-evidence-alignment (:requirements data) (:traces data))` 暴露. 前端"URS 需求版本"台账在"追踪状态"列后新增"验证对齐"列 (绿"已配验证关联"/橙"声明方式·缺验证关联"/灰"未声明方式"), "URS与追踪"页签在覆盖度面板之后新增 `alignment-section`"验证方式与验证关联对齐"面板 (蓝"已声明验证方式 N"/绿·金·红"已配验证关联 P%"/绿"对齐 A"/橙"缺验证关联 G", 无声明项显示占位提示).

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 60 tests / 746 assertions, 0 failures/errors (新增 `requirement-verification-evidence-alignment-is-derived-read-only` 1 例约 30 断言: 行内 `verification_alignment` 三态 not-applicable/declared-unverified/aligned 按具体版本 verifies 关联, 聚合 `verification_evidence_alignment` declared=2/aligned=1/gap=1/alignment-pct=50, 补一条 verifies 关联后升 aligned=2/gap=0/pct=100, 修订未声明者丢关联回落 declared-unverified 且按 code 去重仍 declared=2, 作废未被引用的修订版后 pct=100, 只读回显 `code`/`verification_method` 不漂移, 纯函数 `requirement-trace-model` 与 `verification-evidence-alignment` 直测) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 161 tests / 1661 assertions, 0 failures/errors, 新增读派生函数与 workspace `assoc` 未造成既有需求/追踪/覆盖度/归集用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c01va.spec.js` 1 passed, 无未捕获 JS 错误: 界面"新增URS需求"登记一条选"测试"与一条不选验证方式的需求 -> "验证方式与验证关联对齐"面板显示蓝"已声明验证方式 1"、红"已配验证关联 0%"、绿"对齐 0"、橙"缺验证关联 1" (截图 c01va-1-gap-panel.png), 台账"验证对齐"列对声明者显橙"声明方式·缺验证关联"、未声明者显灰"未声明方式" (截图 c01va-2-gap-column.png); 真实 HTTP 给声明者建一份证据文档并挂一条 verifies 追踪关联后重开 -> 面板升到绿"已配验证关联 100% / 对齐 1"且"缺验证关联"标签消失 (截图 c01va-4-aligned-panel.png), 台账列翻绿"已配验证关联" (截图 c01va-3-aligned-column.png); 真实 HTTP GET governance 二次确认 `verification_evidence_alignment['alignment-pct']=100` 与行内 `verification_alignment=aligned` 一致; 截图存 `reports/c01va/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移, 不新增 DDL); 对齐口径只读可见, **不做任何强制门控或拦截** (声明了验证方式却无 verifies 关联的需求照常登记与流转), 亦不做按类别/优先级的更细切分或导出.

边界: 验证方式与验证关联对齐只读派生是 C01"需求可追踪且带验证方法"口径中"声明的验证方式是否已配齐验证证据"一项的 `implemented / local` 落地 (行内交叉核对 + latest 去重 + 剔除已作废 + 免迁移 + workspace 暴露 + 台账列与面板可视), 关闭此前"覆盖度只反映是否声明而非已配齐验证证据"边界; 但"验证方法与验收证据闭环"的强制门控 (据此阻断 Gate/关闭), 双向追踪覆盖率分母正式界定与 MySQL 回归仍未完备, C01 矩阵行保持既有 `implemented / local` 不上行, C03 行仍 `partial / 待规则`, 不因这一子能力上行.

## C01 验证关联所指向证据是否已发布只读派生 (本轮增补, 2026-09-29)

设计与关闭口径: 在上一子增量"验证方式与验证关联对齐"之上再细分一层——挂上 `verifies` 关联不等于那条验证证据本身已经过审批发布, 一份仍处 `registered`/`in_review`/`rejected` 的文档即便被 verifies 指向也不构成可交付的验证证据. 本项**不改动 `verification_alignment` 既有口径**, 只在其内追加"关联到的证据是否已发布 (approved)"的正交维度, 与 C03 追踪链"证据发布状态"共用同一 `approved` 判定词汇. 沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind**. (1) 行内派生: `requirement-trace-model` 新增入参 `docs-by-id` (document 版本 id -> 含 `:status` 文档), 在 `verification_alignment` 之外派生 `verification_evidence_state`——未声明者 `not-applicable`, 已声明无 verifies 关联者 `no-verification`, verifies 命中至少一条 `target_kind = document` 且版本 `status = "approved"` 者 `released`, 有 verifies 但目标全为非批准文档或任务者 `pending` (`aligned` 与 `released/pending` 正交, `pending` 必然 `aligned` 不回退对齐结论). workspace `governance.clj` 传入既有的 `docs-by-id` 并给聚合函数追加同一入参. (2) 聚合派生: `verification-evidence-alignment` 保留 `{declared, aligned, gap, alignment-pct}` 并追加 `{evidence-released, evidence-pending, evidence-released-pct}` (分母仍取已声明数, `evidence-released/declared` 四舍五入整数, `declared` 为 0 给 0), 同样 latest 去重并剔除 `discarded`. 派生键无尾随 `?`; `:evidence-released-pct` 经 `clj->js` 后是字面 `"evidence-released-pct"`, E2E 原始 JSON 用中括号取值. (3) 前端: "URS 需求版本"台账在"验证对齐"列后新增"验证证据"列 (绿"证据已发布"/金"证据待发布"/灰"缺验证关联"/灰"未声明方式"), `alignment-section` 面板在既有标签行后追加第二行 (青·灰"已发布证据 P%"/geekblue"证据已发布 R"/pending>0 时金"证据待发布 P"). 关键坑: workspace 的 `assoc` 块读的是未经 enrich 的原始 `(:requirements data)` 与 `(:traces data)`, 聚合函数必须自带 `docs-by-id` 才能在结果层判定发布态.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 61 tests / 776 assertions, 0 failures/errors (新增 `requirement-verification-evidence-release-is-derived-read-only` 1 例: 行内 `verification_evidence_state` 三态 pending/released/no-verification 按 verifies 所指向文档版本审批态, 聚合 `verification_evidence_alignment` declared=3/aligned=2/gap=1 且 evidence-released=0/evidence-pending=2/evidence-released-pct=0, `approve!` 发布 doc-rel 后翻 released=1/pending=1/pct=33 而 `aligned` 仍 2 (正交不回归), 再发布 doc-pend 后 released=2/pending=0/pct=67, `code`/`verification_method` 不漂移, 纯函数直测 released/pending/pending-task/no-verification/not-applicable 与聚合); 既有 5 处 `requirement-trace-model` 与 1 处 `verification-evidence-alignment` 调用同步补 `docs-by-id` 入参零回归 |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 162 tests / 1691 assertions, 0 failures/errors, 新增读派生入参/键与 workspace 接线未造成既有需求/追踪/覆盖度/对齐/归集用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c01vb.spec.js` 1 passed, 无未捕获 JS 错误 (双真实上下文): 界面登记一条选"测试"的需求 -> 真实 HTTP 建证据文档并挂 verifies 关联 -> 台账"验证证据"列显金"证据待发布"、面板显"已发布证据 0% / 证据已发布 0 / 证据待发布 1"而"验证对齐"列仍绿"已配验证关联" (截图 c01vb-1-pending-panel.png, c01vb-2-pending-column.png); admin 提交该文档并指定独立审批人, 审批人第二上下文批准发布后重开 -> "验证证据"列翻绿"证据已发布"、面板升"已发布证据 100% / 证据已发布 1"且"证据待发布"消失 (截图 c01vb-3-released-column.png, c01vb-4-released-panel.png); 真实 HTTP GET governance 二次确认 `verification_evidence_state=released`、`verification_evidence_alignment['evidence-released']=1`/`['evidence-pending']=0`/`['evidence-released-pct']=100` 且 `verification_alignment` 仍 `aligned`; 截图存 `reports/c01vb/` |

本轮测试维护 (如实记录): 独立于本增量, 既有治理用例 `comm-plan-log-advances-next-date-and-flags-overdue` 因把登记日/下次日硬编码为 `2026-09-22`/`2026-09-29` 而在真实日历推进到 2026-09-29 时误报 (沟通节奏读模型 `comm-plan-read-model` 用真实 `LocalDate/now` 计算逾期), 与本次改动无关; 已改为按运行日 `java.time.LocalDate/now` + `.plusDays` 动态计算登记/下次日期, 免疫日历漂移, 非新功能.

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移, 不新增 DDL); 证据发布口径只读可见, **不做任何强制门控或拦截** (验证关联只指向未发布证据的需求照常登记与流转), 亦不做按类别/优先级的更细切分或导出.

边界: 验证关联所指向证据是否已发布只读派生是 C01"需求可追踪且带验证方法"口径中"验证证据本身是否已审批发布"一项的 `implemented / local` 落地 (行内正交派生 + 聚合追加发布计数 + latest 去重 + 剔除已作废 + 免迁移 + workspace 暴露 `docs-by-id` + 台账列与面板可视), 在不回退"已配验证关联"对齐结论的前提下关闭"挂上关联即视为验证就绪"的乐观假设; 但据发布态强制门控 (据此阻断 Gate/关闭), 双向追踪覆盖率分母正式界定与 MySQL 回归仍未完备, C01 矩阵行保持既有 `implemented / local` 不上行, C03 行仍 `partial / 待规则`, 不因这一子能力上行.

## H08/C10 风险侧预防措施落实情况只读派生 (本轮增补, 2026-09-29)

设计与关闭口径: 上一子增量"风险应对措施落实为可追踪预防行动项"把风险的措施正向落实成一条带 `source_risk_id` 的 open 行动并在行动台账标注来源风险, 但如实记录了边界"不在风险侧统计其派生行动完成情况". 本项闭合这一边界——在风险台账反向聚合每条风险派生的预防行动落实情况, 沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind, 不构成任何门控**. (1) 反向聚合: `governance.collaboration/mitigation-rollup-by-risk` 以 `(:actions data)` 为输入按行动已持久化的 `source_risk_id` 分组累计 `{total, open}`, `open` 排除 `closed`/`converted` (即 open/in_review/rejected 均算未完成), 无 `source_risk_id` 的行动 (如会议派生) 不计入任何风险. (2) 行内派生: `mitigation-read-model` 用该 rollup 对每条风险 `assoc` `mitigation_action_total`/`mitigation_action_open`/`mitigation_action_state` (`unimplemented` total 为 0 / `in-progress` open 大于 0 / `completed` 有 total 且 open 为 0; 因 `mitigation` 登记必填故不设"无措施"态). workspace `governance.clj` 在 `let` 里算 `mitigation-rollup` 并在 `->` 线程追加 `(update :risks #(mapv (partial collab/mitigation-read-model mitigation-rollup) %))` 挂到已 enrich 过的风险行之后. 键名不带尾随 `?`. (3) 前端: "风险与问题"台账在"应对措施"与"下次复评"列之间新增"措施落实"列, 橙"措施未落实"/金"落实中, N 项待办"/绿"已落实 N 项"/无值灰"—".

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 62 tests / 795 assertions, 0 failures/errors (新增 `risk-mitigation-action-rollup-is-derived-read-only` 1 例: 两条带措施风险初始均 `unimplemented` 0/0 -> `:risks :mitigation-action` 落实一条行动后该风险翻 `in-progress` 1/1 而另一条不受影响 -> 再落实第二条 2/2 -> 经 `:actions :complete` + 独立 `:actions :verify` 批准关闭第一条降到 open=1 仍 `in-progress` -> 第二条 `:actions :task` 转 WBS (converted) 后 open=0 翻 `completed` 1/0, 全程风险 `status` 保持登记态不漂移且重读稳定; 纯函数直测 rollup-by-risk 与三态映射) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 163 tests / 1710 assertions, 0 failures/errors, workspace 新增 `(update :risks ...)` 未造成既有责任人负载/到期倒计时/风险复审等风险行 enrich 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h08mr.spec.js` 1 passed, 无未捕获 JS 错误: 界面登记一条 2x3=6 (不触发升级) 带措施风险 -> "风险与问题"台账"措施落实"列显橙"措施未落实" (截图 h08mr-1-unimplemented.png); 点该行"落实预防措施"保存生成 open 行动 -> 重开风险台账该列翻金"落实中, 1 项待办" (截图 h08mr-2-in-progress.png); 在"会议行动"台账把该行动"转为WBS任务" -> 重开风险台账该列翻绿"已落实 1 项" (截图 h08mr-3-completed.png); 真实 HTTP GET governance 二次确认行内 `mitigation_action_total/open/state` 由 unimplemented 0/0 -> in-progress 1/1 -> completed 1/0 逐级翻转且风险 `status` 不漂移; 截图存 `reports/h08mr/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移, 不新增 DDL); 该列只读反向汇总, **不构成任何门控或拦截** (预防行动是否全部落实不阻止风险复审/关闭/缓解流转), 亦不做按到期日的措施逾期细分 (该边界已于同日后续子增量"风险侧预防措施逾期只读细分"闭合, 见下一节) 或措施到多条行动的批量拆分.

边界: 风险侧预防措施落实情况只读派生闭合了上一子增量"仅在行动侧标注来源而未回显风险侧措施落实进度"的边界, 是 H08/C10 风险追踪口径下 `implemented / local` 的一项只读洞察; H08 行仍为 `partial` (升级通知投递, 跨项目风险汇总升级仍待实现), C10 行保持 `implemented / local`.

## H08/C10 风险侧预防措施逾期只读细分 (本轮增补, 2026-09-29)

设计与关闭口径: 上一子增量"风险侧预防措施落实情况只读派生"如实记录了边界"不做按到期日的措施逾期细分"——一条 `in-progress` 的风险看不出其派生的预防行动里到底有几条已经到期未办. 本项在同一反向聚合上再补一层到期维度, 完全沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind, 不构成任何门控**. (1) 反向聚合扩展: `governance.collaboration/mitigation-rollup-by-risk` 在既有 `{total, open}` 累加器上追加 `overdue`, 逐条行动复用同一命名空间已有的公开谓词 `action-overdue?` (存在到期日、状态非 `closed`/`converted` 且到期日不晚于服务器当天) 判定, 故 `overdue` 恒为 `open` 的子集, 且与会议行动/问题台账既有的逾期口径完全一致, 不新写日期逻辑; `action-overdue?` 定义在 rollup 之前, 无前向引用. (2) 行内派生扩展: `mitigation-read-model` 由 `assoc` 三键改为四键, 新增 `mitigation_action_overdue` (无 rollup 命中时缺省 0), 三态判定不变 (仍 `unimplemented`/`in-progress`/`completed`). workspace `governance.clj` 接线不变 (复用同一 `mitigation-rollup` 与 `(update :risks ...)`), 因新增维度落在既有纯函数内故无需改动读模型装配. 键名不带尾随 `?`. (3) 前端扩展: "措施落实"列在金色"落实中, N 项待办"标签之后, 当 `mitigation_action_state` 为 `in-progress` 且 `mitigation_action_overdue` 大于 0 时追加一枚红色"N 项逾期"标签; 逾期行动被独立核验关闭 (`closed`) 或转 WBS 任务 (`converted`) 后该红标自动消失 (二者均不再计入 overdue).

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 63 tests / 816 assertions, 0 failures/errors (新增 `risk-mitigation-action-overdue-is-derived-read-only` 1 例: 登记到期日放在遥远过去 `2020-01-01` 的带措施风险初始 overdue=0 -> `:risks :mitigation-action` 落实一条继承该过去到期日的 open 行动后 total/open/overdue 均 1 -> 再落实一条未来到期日 `2099-01-01` 行动使 total 2 open 2 overdue 仍 1 -> 经 `:actions :complete` + 独立 `:actions :verify` 关闭逾期那条后 open 降 1 且 overdue 归 0 (closed 不计逾期) 仍 `in-progress` -> 末条 `:actions :task` 转 WBS (converted) 后 open 0 overdue 0 翻 `completed`; 纯函数直测 rollup-by-risk 对 open/in_review/closed/converted 与过去/未来到期日混合样本得 `{total 5 open 3 overdue 2}` 并验证 overdue 为 open 子集与缺省 0); 既有 `risk-mitigation-action-rollup-is-derived-read-only` 纯函数断言随 rollup 输出新增 `:overdue` 键同步补 `:overdue 0` 零回归) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 164 tests / 1731 assertions, 0 failures/errors, rollup 新增 `overdue` 累加器与 read-model 第四键未造成既有责任人负载/到期倒计时/风险复审/措施落实三态等风险行 enrich 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h08mr.spec.js` 现 2 passed (新增"预防措施逾期细分"用例), 无未捕获 JS 错误: 界面登记一条 2x3=6 带措施且到期日 `2020-01-01` 的风险 -> "措施落实"列显橙"措施未落实"; 点"落实预防措施"保存生成继承过去到期日的 open 行动 -> 重开风险台账该列同时显金"落实中, 1 项待办"与红"1 项逾期" (截图 h08mr-4-overdue.png); 在"会议行动"台账把该逾期行动"转为WBS任务" -> 重开风险台账该列翻绿"已落实 1 项"且红"逾期"标消失 (截图 h08mr-5-overdue-cleared.png); 真实 HTTP GET governance 二次确认行内 `mitigation_action_overdue` 由 1 (in-progress) -> 0 (completed) 且 overdue 恒不超过 open, 风险 `status` 不漂移; 截图存 `reports/h08mr/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移, 不新增 DDL); 逾期细分只读可见, **不构成任何门控或拦截** (措施逾期未办不阻止风险复审/关闭/缓解流转, 亦不自动改派或升级), 不做按责任人/阶段的逾期更细切分, 不做措施到多条行动的批量拆分, 不做逾期主动提醒投递.

边界: 风险侧预防措施逾期只读细分闭合了上一子增量"不做按到期日的措施逾期细分"的边界, 是 H08/C10 风险追踪口径下 `implemented / local` 的又一项只读洞察 (同一反向聚合套路第 N 次复用, 新增"到期维度子集"这一形状变体); 但据逾期强制门控/自动升级/通知投递仍未完备, MySQL 回归亦待补, 故 H08 行保持 `partial` 不上行, C10 行保持 `implemented / local`, 不因这一子能力上行.

## C07 会议行动闭环率只读汇总面板 (本轮增补, 2026-09-29)

设计与关闭口径: 此前的会议行动洞察都挂在单条会议行 ("行动闭环"列按 `meeting_id` 分组算该会议的 open/total/overdue) 或风险行 ("措施落实"列按 `source_risk_id` 反向聚合), 但整个项目"所有会议行动与风险预防行动合起来到底闭环了多少"这一 portfolio 级视角在界面里没有一处汇总. 本项新增一个只读汇总面板回答它, 完全沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind, 免新路由, 不构成任何门控**. (1) portfolio 级聚合: 新纯函数 `governance.collaboration/action-closure-summary` 以扁平 `(:actions data)` 全量为输入 (与既有 `actions-by-meeting`, `mitigation-rollup-by-risk`, `owner-workloads` 采用同一"按原始记录不按 latest 去重"的口径, 因每条行动是一次性事实而非带修订版本的业务编号), 累计 `{total, closed, open, converted, overdue, closure-pct}`——`closed` 为状态属 `closed` 或 `converted` 者, `open` 为 `total - closed`, `converted` 单列转 WBS 任务数, `overdue` 逐条复用同命名空间既有公开谓词 `action-overdue?` (存在到期日、状态非 `closed`/`converted` 且到期日不晚于服务器当天) 判定故恒为 `open` 子集, `closure-pct` 为 `closed/total` 四舍五入整数百分比 (`total` 为 0 时给 0). `action-closure-summary` 定义在 `action-overdue?` (395 行) 之后 (561 行), 无前向引用. (2) workspace 接线: `governance.clj` 在既有 workspace `assoc` 块 (`:risk_response_coverage` 之后) 增加 `:action_closure (collab/action-closure-summary (:actions data))`, 单函数不跨 kind 逐行 enrich, 装配轻量. (3) 前端: "需求与治理 / 会议行动"页签在行动台账之前新增"会议行动闭环率"面板, 以蓝"行动总数 N"、绿/金/红"已闭环 P% (C/T)", 未完成 (open 大于 0 时橙"未完成 N"), 逾期 (overdue 大于 0 时红"逾期未闭环 N"), 转任务 (converted 大于 0 时 geekblue"转任务 N") 标签回显, 无行动时显占位提示. 键名不带尾随 `?`; `:closure-pct` 经 `clj->js` 后是字面 `"closure-pct"` (保留连字符), 前端 keyword 取值无碍而 E2E 原始 JSON 需 `['closure-pct']` 中括号取值 (与既有 `alignment-pct`/`coverage-pct`/`released-pct` 同约定).

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 64 tests / 826 assertions, 0 failures/errors (新增 `meeting-action-closure-summary-is-derived-read-only` 1 例: 一次会议下五档行动——1 条 `:actions :task` 转 WBS (converted), 1 条经 `:actions :complete` + 独立 `:actions :verify` 批准关闭 (closed), 2 条 open 且到期日 `2020-01-01` 已过, 1 条 open 且到期日 `2099-01-01` 远期——GET 读 `:action_closure` 得 total 5 / closed 2 / open 3 / converted 1 / overdue 2 / closure-pct 40 且 overdue 恒为 open 子集; 一条 past-due open 行动读取后 `status` 仍 `open` 不漂移 (只读不门控); 纯函数直测 `action-closure-summary` 对同五档混合样本得 `{total 5 closed 2 open 3 converted 1 overdue 2 closure-pct 40}` 且空向量 `[]` 返回全零 + `closure-pct` 0) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 165 tests / 1741 assertions, 0 failures/errors, workspace 新增 `:action_closure` assoc 键与 `action-closure-summary` 未造成既有会议行动闭环/责任人负载/措施落实等行动侧用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c07ac.spec.js` 1 passed (47.9s), 无未捕获 JS 错误: 界面建项目与独立质量审批人第二浏览器上下文 -> 一次会议登记五档行动 (1 界面"转为WBS任务"转任务, 1 界面"提交完成"+第二上下文"批准关闭"闭环, 2 open 过去到期, 1 open 远期) -> "会议行动闭环率"面板显示蓝"行动总数 5"、金"已闭环 40% (2/5)"、橙"未完成 3"、红"逾期未闭环 2"、geekblue"转任务 1" (截图 c07ac-1-panel.png), 行动台账五档状态列可见 (c07ac-2-actions.png); 另在无行动的空项目打开面板显占位提示 (c07ac-3-empty.png); 真实 HTTP GET governance 二次确认 `action_closure` 各计数与 `['closure-pct']` 为 40 且与界面一致 (读取时按扁平行动聚合, 只读派生不落库不投递不门控); 截图存 `reports/c07ac/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移, 不新增 DDL); 该面板只读 portfolio 级汇总, **不构成任何门控或拦截** (闭环率高低不阻止任何行动登记/完成/核验/转任务流转), 不做按责任人/会议/阶段的闭环率更细切分, 不做闭环趋势时间序列或历史留存, 不做逾期主动提醒投递.

边界: 会议行动闭环率只读汇总面板把此前只在单会议行与单风险行可见的闭环计数提升到项目 portfolio 级一处汇总, 是 C07 会议行动追踪口径下 `implemented / local` 的又一项只读洞察 (同一"给治理台账加只读派生洞察"套路复用, 新增"扁平全量 portfolio 聚合"这一形状变体); 但据自动到期提醒投递, 会前资料包与主计划版本关联等 C07 复合规则仍未完备, MySQL 回归亦待补, 故 C07 行保持 `partial` 不上行.

## H03 范围基线覆盖性审查只读派生 (本轮增补, 2026-09-29)

设计与口径: H03 的"覆盖性审查"此前在界面没有一处可见——需求追踪链 (`trace` 记录 target_kind=task, relation=satisfies/verifies) 已存在, 但没人把"哪些 WBS 叶节点被需求满足关系覆盖、哪些还没"这一范围-需求双向映射算出来给人看. 本项把它作为**只读派生洞察**落在计划工作台, 完全沿用"给台账加只读派生洞察"套路, 且首次把它从治理侧搬到**计划侧**: **不落库、不投递、不改动任何不可变版本, 免迁移, 免新命令, 免新 kind, 免新路由, 不构成任何门控**. (1) 纯函数: `planning.tasks` 新增 `leaf-task?` (task_type 非 `summary` 即为叶节点, `task` 与 `milestone` 都算), `scope-read-model` (对单任务按 `traces-by-task` 分组标注 `scope_leaf/scope_satisfies_count/scope_verifies_count/scope_trace_count/scope_covered`, 汇总任务仅标 `scope_leaf=false`), `scope-coverage` (对全量 tasks+traces 聚合 `{total-leaves, covered-leaves, uncovered-leaves, coverage-pct, uncovered-codes}`, `covered` 为 `scope_satisfies_count>0` 者即至少一条 satisfies 指向, verifies 只标注不计覆盖, `coverage-pct` 为 `covered/total` 四舍五入整数百分比且 `total` 为 0 时给 0). 三者定义顺序 `leaf-task? < scope-read-model < scope-coverage` 无前向引用. (2) read-plan 接线: `planning.clj` 读模型 `let` 里以 `traces-by-task` 显式调用 `tasks/scope-read-model` 逐任务 enrich (关键坑: 不能用 `->` 线程, 否则线程值被当末位实参传入 `[traces-by-task task]` 会静默 assoc 到错误的 map 抹掉原任务键, 曾致 2 个既有用例回归), 并在 merge 块 `:paused_node_ids` 之后新增 `:scope_coverage (tasks/scope-coverage raw-tasks traces)`. (3) 前端: "计划与执行 / WBS与排程"页签在 WBS 台账之前新增"范围覆盖性审查"面板 (蓝"叶节点 N", 绿/红"已覆盖 C (P%)", 橙"未覆盖 U", 灰字"未覆盖 WBS: ..."), 并在任务表"类型"列之后新增"范围覆盖"列 (汇总行灰"汇总", 已覆盖叶绿"已覆盖 设计 N" 且 verifies>0 附青"验证 N", 未覆盖叶橙"未覆盖"). 键名不带尾随 `?`; `:coverage-pct` 经 `clj->js` 后是字面 `"coverage-pct"` (保留连字符), 前端 keyword 取值无碍而 E2E 原始 JSON 需 `['coverage-pct']` 中括号取值 (与既有 `closure-pct`/`alignment-pct` 同约定).

| 证据 | 实际记录 |
|---|---|
| 计划单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-planning-test'` 通过 12 tests / 97 assertions, 0 failures/errors (新增 `scope-coverage-is-derived-read-only` 1 例含两 testing 块: 纯函数直测 `scope-coverage` 对 汇总"1"+叶"1.1"(satisfies)+叶"1.2"(仅verifies) 得 `{total-leaves 2 covered-leaves 1 uncovered-leaves 1 coverage-pct 50}` 且 `uncovered-codes ["1.2"]`, 汇总 `scope_leaf false`/里程碑 `scope_leaf true`, satisfies 叶 `scope_covered true`, 仅 verifies 叶 `scope_covered false`; 集成测经 `gov/command! :requirements :create` + `:traces :create` 建真实 satisfies 追踪后 `read-plan` 回显 `:scope_coverage` 同计数且逐任务 `scope_covered`/`scope_leaf` 一致, 读取前后 `project_version` 不漂移证明只读不落库) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 166 tests / 1754 assertions, 0 failures/errors, read-plan 新增逐任务 `scope-read-model` enrich 与 `:scope_coverage` assoc 键未造成既有排程/挣值/基线/追踪用例回归 (修复 `->` 线程参数错位后 2 个先前回归用例恢复通过) |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h03sc.spec.js` 1 passed (45.6s), 无未捕获 JS 错误: 界面建项目转 planning -> WBS 建汇总"1"(交付汇总骨架)+叶"1.1"(受控设计任务)+叶"1.2"(未覆盖验证任务) 且两叶挂到汇总下 -> "需求与治理/URS与追踪"登记一条 URS 需求并对"受控设计任务"建 satisfies + verifies 两条追踪 (1.2 故意不覆盖) -> "计划与执行/WBS与排程"的"范围覆盖性审查"面板显示蓝"叶节点 2"、红"已覆盖 1 (50%)"、橙"未覆盖 1"、灰"未覆盖 WBS: 1.2" (截图 h03sc-1-panel.png), 任务表"范围覆盖"列 汇总行"汇总"/1.1 行绿"已覆盖 设计 1"+青"验证 1"/1.2 行橙"未覆盖" (h03sc-2-columns.png); 真实 HTTP GET planning 二次确认 `scope_coverage` 各计数与 `['coverage-pct']` 为 50 且逐任务 `scope_leaf`/`scope_covered`/`scope_satisfies_count`/`scope_verifies_count` 与界面一致; 只读不门控以存在未覆盖叶仍成功"提交计划审批"冻结 (返回 200 且显"计划已冻结,等待独立审批.") 佐证; 另在无 WBS 的空项目面板显"叶节点 0 / 已覆盖 0 (0%) / 未覆盖 0" (h03sc-3-empty.png); 截图存 `reports/h03sc/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移, 不新增 DDL); 覆盖性审查只读派生, **不构成任何门控或拦截** (未覆盖叶不阻止任务保存/依赖/资源/提交冻结), 不做范围排除项登记, 不做验收准则到叶节点的逐条映射, 不做"覆盖性审查通过后强制冻结基线"这一 H03 目标动作.

边界: H03 范围覆盖性审查只读派生把需求 satisfies 追踪链提升为 WBS 叶节点可见的覆盖判定 (逐任务列 + 项目级汇总面板), 是把"给台账加只读派生洞察"套路首次落到**计划工作台**的一次复用; 但 H03 整行验收要求"可交付范围/排除项/验收准则映射到 WBS 叶节点, 覆盖性审查后冻结, 范围变更受控"——排除项与验收准则逐条映射及覆盖性审查后强制冻结仍未实现, 故 H03 行保持 `partial` 不上行.

## C07e 会议行动受控重开 (本轮增补, 2026-09-29)

设计与口径: 此前会议行动只有"提交完成 -> 独立核验关闭/驳回"的正向闭环 (C07), 一旦行动被关闭就无法在治理侧留痕地重新打开——问题 (issue) 侧早已具备"申请—独立审批"的受控重开闭环 (`/issues/:rid/reopen` + `/decision`), 会议行动却缺位. C07e 把同一套两段式受控重开搬到会议行动, 是一条**写路径闭环** (非只读派生), 但仍坚持**免迁移、免新 kind、免新裁决路由**: 不新增治理记录类型 (不动 `pms_governance_record` 的 `CHECK(kind IN ...)`), 新字段 (`review_action=action_reopen`, `reopen_reason`, `reopen_evidence_ids`, `submitted_by`, `prior_closure_result`, `prior_verification_reason`, `reopen_decision`, `reopen_decision_reason`, `reopen_decided_by`) 全部随 payload JSON 持久化; 裁决复用既有 `POST /actions/:rid/verify` 端点, 由 `verify-action!` 按 `review_action` 分流, 不为重开单开路由. (1) 领域: `collaboration.clj` 新增 `reopen-action!` (仅 `closed` 可重开否则 409, 要求非空 `reason` 否则 400、真实证据 `evidence_ids` 否则 409、独立 `reviewer_id` 经 `s/reviewer!` 校验不得为申请人且具 `pms:quality:approve` + 项目访问否则 409/403, 转 `in_review` 并快照 `prior_closure_result`/`prior_verification_reason`), 并把 `verify-action!` 改为按 `review_action` 分流 (命中 `action_reopen` 时 approved->`open`/rejected->`closed` 且清空 `review_action`, 否则维持原完成核验 approved->`closed`/rejected->`rejected`). (2) 命令表 + 路由: `governance.clj` 加 `[:actions :reopen]`, `pms_governance.clj` 加 `POST /actions/:record_id/reopen`. (3) 前端: `governance_forms.cljs` 新增 `action-reopen-dialog` (重开依据/证据/独立审批人), `governance.cljs` 的 `action-actions` 对 `closed` 行动加"申请重开"入口、对 `action_reopen` 待审态把裁决按钮与对话框标题切换为"批准重开/驳回"与"批准重开并重新打开行动/驳回重开并维持关闭", `action-section` 新增"评审事项"列对 `action_reopen` 显 volcano 标签"重开审批中".

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 65 tests / 840 assertions, 0 failures/errors (新增 `closed-meeting-action-reopens-only-through-independent-review` 1 例: 完成并独立批准后 closed; 空依据 400, 空证据 409, 审核人为申请人 409, 审核人无质量审批权 403; 申请重开转 in_review 且 `review_action=action_reopen` 并快照 `prior_closure_result`; 申请人自批 403; 审批人驳回 -> closed; 再次申请后批准 -> open 且读取回显一致) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 167 tests / 1768 assertions, 0 failures/errors, `verify-action!` 改为按 `review_action` 分流未造成既有完成核验/关闭/逾期用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c07e.spec.js` 1 passed (1.5m), 无未捕获 JS 错误: 界面登记会议+行动 -> admin"提交完成"由独立审批人第二真实浏览器上下文"批准关闭"置 closed -> admin 对已关闭行动点"申请重开"填重开依据+证据+指定同一独立审批人 -> 行动转 in_review, "评审事项"列以 volcano 标签显"重开审批中" (截图 c07e-1-reviewing-badge.png), 真实 HTTP GET governance 回显 `status=in_review`/`review_action=action_reopen`; 审批人上下文点"驳回"选"驳回重开并维持关闭" -> 回 closed 且 `review_action` 清空; admin 再次"申请重开" -> 审批人点"批准重开"选"批准重开并重新打开行动" (截图 c07e-2-approver-reopen.png) -> 行动翻回 open, 台账状态列显"待处理" (截图 c07e-3-reopened-open.png), 真实 HTTP GET governance 二次确认 `status=open`/`review_action=null`/`reopen_decision=approved`; 截图存 `reports/c07e/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); 门控事实 (自批 403, 非 closed 不可重开 409, 缺证据 409, 空依据 400) 在浏览器侧只走界面可见路径, 非法态由后端 SQLite 用例确定性地覆盖; 不做重开次数上限、重开历史可视化报表与自动通知投递.

边界: C07e 把问题侧既有的"申请—独立审批"受控重开闭环复用到会议行动, 补齐了 C07 会议行动此前"关闭即终态、无法受控重开"的缺口; 但 C07 整行验收所涉更宽的行动治理 (如重开审计报表, 通知投递, 生产/UAT 签收) 仍未尽数实现, 故 C07 行保持 `partial` 不上行.

## C07f 会议纪要受控发布 (本轮增补, 2026-09-29)

设计与口径: 会议 (meeting) 是治理各 kind 中此前唯一只有"登记 (recorded)"终态、而无发布/裁决路径的一类——文档 (C06)、章程、关口都已具备"提交—独立裁决发布"闭环, 会议纪要么停留在登记态、无法在治理侧形成"经独立审批的正式归档纪要". C07f 把同一套两段式受控发布补到会议纪要, 是一条**写路径闭环** (非只读派生), 仍坚持**免迁移、免新 kind、免新裁决路由**: 不新增治理记录类型 (不动 `pms_governance_record` 的 `CHECK(kind IN ...)`), 新字段 (`reviewer_id`, `submitted_by`, `release_decision`, `release_reason`, `released_by`, `decided_by`) 全部随 payload JSON 持久化; 关键取舍——批准态复用 `pms_gov_record.status` 的 CHECK 允许值 `approved` (与文档/章程/关口"发布即 approved"一致), 而非引入 CHECK 未包含的 `released` 字面量 (若误用 `released` 会在 `gov/update!` 写库时触发数据约束冲突). (1) 领域: `collaboration.clj` 新增 `submit-meeting!` (仅 `recorded` 可提交否则 409, 空纪要 409 防御, 独立 `reviewer_id` 经 `s/reviewer!` 校验不得为提交人且具 `pms:quality:approve` + 项目访问否则 409/403, 转 `in_review` 记 `submitted_by`) 与 `decide-meeting!` (`{:write? false}` 只读审批人即可裁决, `s/decision-actor!` 校验只有指定审核人且非提交人, approved->`approved` 记 `released_by`, rejected->`recorded` 退回登记态可补充重提). (2) 命令表 + 路由: `governance.clj` 加 `[:meetings :submit]`/`[:meetings :decision]`, `pms_governance.clj` 加 `POST /meetings/:record_id/submit` 与 `POST /meetings/:record_id/decision`. (3) 前端: `governance.cljs` 的 `meeting-section` 新增"纪要发布"状态列 (recorded 草稿 / in_review 发布审批中 / approved 已发布), 对 `recorded` 纪要加"提交发布"入口 (选独立审批人), 对 `in_review` 且当前用户为指定审核人者显"批准发布"/"驳回"按钮 (复用 `forms/decision-dialog`).

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 66 tests / 855 assertions, 0 failures/errors (新增 `meeting-minutes-release-requires-independent-approval` 1 例: 提交人自任审核人 409, 无项目访问审核人 403; 提交转 in_review 且回显 `reviewer_id`/`submitted_by`; in_review 重复提交 409; 提交人自批 403, 非指定审核人冒名批准 403; 指定审核人批准 -> `approved` 且 `release_decision=approved`/`released_by`=审核人; 已发布不可重提 409; 驳回路径 -> `recorded` -> 重提 -> 批准 -> `approved`) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 168 tests / 1783 assertions, 0 failures/errors, 未造成既有会议登记/行动闭环/闭环率等用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c07f.spec.js` 1 passed (28.6s), 无未捕获 JS 错误: 界面 admin 在"会议与决策"台账对 recorded 纪要点"提交发布"选独立审批人 -> 纪要转 in_review, 新增"纪要发布"列以蓝色标签显"发布审批中" (截图 c07f-1-in-review-badge.png), 真实 HTTP GET governance 回显 `status=in_review`/`reviewer_id`/`submitted_by`; 独立审批人第二真实浏览器上下文亲自点"驳回"填意见 -> 纪要退回 recorded 且 `release_decision=rejected`、"提交发布"按钮重现; admin 补充后重提 -> 审批人点"批准发布"填意见 (截图 c07f-2-approver-release-dialog.png) -> 纪要翻 approved "纪要发布"列显绿色"已发布"且"提交发布"入口消失 (截图 c07f-3-released.png), 真实 HTTP GET governance 二次确认 `status=approved`/`release_decision=approved`/`released_by`=审批人; 截图存 `reports/c07f/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); 门控事实 (提交人自批 403, 非指定审核人 403, in_review 重复提交 409, 已发布不可重提 409, 无项目访问审核人 403) 在浏览器侧只走界面可见路径, 非法态由后端 SQLite 用例确定性地覆盖; 不做纪要发布的自动通知投递、跨项目纪要归集与生产/UAT 签收.

边界: C07f 把文档/章程/关口既有的"提交—独立裁决发布"闭环补到会议纪要, 补齐了会议此前"只有登记终态、无正式归档发布门控"的缺口; 但 C07 整行验收所涉更宽治理 (通知投递, 生产/UAT 签收等) 仍未尽数实现, 故 C07 行保持 `partial` 不上行.

## C07g 会议受控作废与恢复 (本轮增补, 2026-09-29)

设计与口径: H18 早已为需求/文档/干系人三类记录建立了一套 **kind 参数化**的通用软删除框架 (`governance/lifecycle.clj`: `discard!`/`restore!`/`discard-preview`, 三层门控 `latest!` + `status!` (读 `discardable-status`) + 引用守卫 (读 `references-of`), 审计走 `workflow_history` 存 `prior_status`), 会议纪要一直是这套框架里唯一未接入的治理 kind——草稿纪要么只能堆积、无法留痕地清理. C07g 承接 C07f 的纪要发布闭环, 把 meeting 接入既有框架, 是一条**写路径闭环**但仍**免迁移、免新 kind、免新裁决路由**: `discarded` 早已在 `pms_gov_record.status` 的 CHECK 允许值内 (H18 迁移 `202609220011` 已引入), 因此无需任何新 DDL. (1) 领域: `lifecycle.clj` 给 `discardable-status` 增补 `"meeting" #{"recorded"}` (仅草稿可作废, 在途发布审批 `in_review` 与已发布 `approved` 均命中状态守卫 409, 防止误删在途或已归档纪要), 给 `references-of` 增补 meeting 分支 (被派生行动 `action.meeting_id` 或沟通计划 `comm-plan.last_meeting_id` 引用即不可作废并列出来源); `collaboration.clj` 的 `create-action!` 新增前置守卫——先查目标会议状态, 若已 `discarded` 则 409 "会议已作废, 不能派生行动", 堵住向已作废纪要继续挂行动的孤儿引用. (2) 命令表 + 路由: `governance.clj` 加 `[:meetings :discard]`/`[:meetings :restore]` (复用 `approval-command lifecycle/discard! "meeting"`), `pms_governance.clj` 加 `POST /meetings/:record_id/discard`、`POST /meetings/:record_id/restore` 与 `GET /meetings/:record_id/discard-preview`. (3) 前端: `governance.cljs` 的 `meeting-section` "纪要发布"状态列新增 red "已作废" 徽标, 对可编辑且状态为 recorded 的纪要显示"作废"入口, 对 discarded 纪要显示"恢复"与"级联影响"入口 (in_review/approved 不显示作废), 复用既有 `forms/discard-dialog`/`forms/restore-dialog` 与 `discard-preview-modal`.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 67 tests / 885 assertions, 0 failures/errors (新增 `meeting-discard-is-guarded-and-restorable` 1 例, 四段断言: A recorded 会议——无编辑权 403、携带未知字段 400、级联预览 discardable?、作废转 `discarded` 且记 `discard_reason`/`discarded_by`/`prior_status=recorded`、workspace 回显 discarded、已作废再作废 409、向已作废会议派生行动 409、恢复回 `recorded` 且 `workflow_history` 含 `["discarded","restored"]`、非最新恢复 409; B in_review/approved 会议作废命中状态守卫 409 且预览 `status_discardable?` false; C 有派生行动的会议预览列出"行动"并作废 409; D 由沟通计划生成的会议 (last_meeting_id 回指) 作废 409 且预览列出"沟通计划") |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 169 tests / 1813 assertions, 0 failures/errors, 未造成既有会议登记/发布/行动闭环/H18 需求文档干系人作废等用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c07g.spec.js` 3 passed (46.6s), 无未捕获 JS 错误: 第一例 界面登记 recorded 会议 -> 行内"级联影响"打开只读预览弹窗显"可安全作废"与"未发现引用该记录的其它对象" (截图 c07g-1-preview-safe.png), 点"作废"填原因 -> "纪要发布"列翻红色"已作废"且"作废"入口消失改显"级联影响 恢复" (截图 c07g-2-discarded.png), 真实 HTTP GET governance 回显 `status=discarded`/`discarded_by`; 点"恢复" -> 回 recorded 草稿态且"提交发布"/"形成行动"入口重现 (截图 c07g-3-restored.png). 第二例 给会议派生行动 -> 预览显"不可作废"列出"行动 <标题>" (截图 c07g-4-preview-blocked.png), 真实 HTTP POST discard 命中引用守卫 409 于 `[role="alert"]` 显"记录仍被其它对象引用, 不能作废" (截图 c07g-5-discard-blocked-alert.png). 第三例 双真实上下文 admin 提交发布使纪要 in_review -> 台账不显示"作废"入口、真实 HTTP discard 命中状态守卫 409、预览显"状态不可作废" (截图 c07g-6-inreview-nodiscard.png); 截图存 `reports/c07g/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 复用 H18 既有 `discarded` CHECK 取值); 陈旧版本 409、非最新恢复 409、向已作废会议派生行动 409 等门控事实由后端 SQLite 用例确定性地覆盖, 浏览器侧核验界面可见的徽标翻转/入口显隐/告警; 不做已作废纪要对历史沟通与行动快照的显式标注、不做批量作废或按保留策略归档、不做通知投递与生产/UAT 签收.

边界: C07g 把 H18 既有的通用软删除框架接入 meeting kind, 补齐了"草稿清理此前仅覆盖需求/文档/干系人而未含会议纪要"的缺口; 但 C07 整行 (通知投递, 生产/UAT 签收等) 与 H18 整行 (正式历史按保留策略归档, 已作废证据对 Gate/验收快照显式标注等) 所涉更宽治理仍未尽数实现, 故 C07 与 H18 两行均保持 `partial` 不上行.

## H18/C03 已作废证据对历史追踪快照显式标注 (本轮增补, 2026-09-29)

设计与口径: C03 的追踪链"证据发布状态"列 (`trace-read-model` 派生 `evidence_status`/`evidence_release_state`) 与 C01 的 URS"验证证据"列 (`requirement-trace-model` 派生 `verification_evidence_state`) 都按追踪记录里已存的**确定 `target_id`** 读取那一个文档版本的发布态, 因而同编码更新版本被受控作废后, 追踪/需求行仍忠实回显旧版本口径——这是不可变历史快照应有的行为. 但评审界面此前看不出"这条追踪所依赖的证据, 其业务编码当前是否已被整体作废", 可能误用一条早已作废的证据. 本项在同一 `docs-by-id` 入参上再派生一层只读标注, 完全沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移、免新命令、免新 kind、免新路由、不构成任何门控**. (1) 集合派生: 私有纯函数 `governance.evidence/voided-document-codes` 对 `docs-by-id` 逐业务编码 `code` 用 `store/latest` 取最高 `revision` 版本, 收集其 `status = "discarded"` 者形成编码集合 (复用 C01/C04/H18c 同一"按 code 取最新有效版本"不变量). (2) 行内派生: `trace-read-model` 据目标 `target_kind = document` 且所引文档 `code` 命中该集合对追踪行追加布尔 `evidence_voided` (`task` 关联与非命中恒 false); `requirement-trace-model` 据该需求任一 verifies 关联指向 `target_kind = document` 且其 `code` 命中集合追加布尔 `verification_evidence_voided`. 因引用守卫使被追踪直接引用的版本不可作废, 标注为 true 的成立路径只能是"追踪指向旧版本 v1、同编码新版本 v2 被作废"这一"捕获时有效、事后证据整体作废"的真实场景, 与不可变快照不冲突. (3) 前端: "需求追踪矩阵"表"证据发布"列与"URS 需求版本"表"验证证据"列在原发布态徽标之后, 于命中时叠加红色"证据已作废"标签 (原徽标保持不变, 二者并存). 键名不带尾随 `?`; `evidence_voided`/`verification_evidence_voided` 经 `clj->js` 后为同名布尔, 前端 `(aget row "...")` 用 `true?` 判定.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 68 tests / 900 assertions, 0 failures/errors (新增 `discarded-evidence-latest-revision-is-flagged-in-trace-read-model` 1 例: 建项目+文档 v1 (`EV-VOID`) 登记为 `registered` -> 需求 `URS-EV` 声明验证方式 `test` 并挂一条指向 v1 的 verifies 追踪 -> 断言 `evidence_voided` 与 `verification_evidence_voided` 均 false -> `:documents :revisions` 生成同编码 v2 (`revision` 2) -> `:documents :discard` 作废 v2 (未破坏 v1, 追踪仍指 v1) -> 断言 `evidence_voided` true 且 `verification_evidence_voided` true, 而追踪行 `evidence_status` 仍 `registered`、`evidence_release_state` 仍 `pending` 不漂移 -> `:documents :restore` 恢复 v2 后两布尔复归 false; 另对 `docs-by-id` 混合样本 `{a: rev1 registered, b: rev2 discarded}` 直接纯函数测 `trace-read-model`/`requirement-trace-model` 的命中与未命中布尔) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 170 tests / 1828 assertions, 0 failures/errors, `voided-document-codes` 集合与两处行内布尔未造成既有追踪链证据发布/需求追踪状态/验证证据已发布等 read-model 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h18ev.spec.js` 1 passed (48.2s), 无未捕获 JS 错误: 界面登记一条声明验证方式"测试"的需求 -> 真实 HTTP 建证据文档 v1 并挂一条 verifies 追踪 -> "URS 需求版本"与"需求追踪矩阵"两表原徽标可见且无红"证据已作废" (截图 h18ev-1-urs-clean.png, h18ev-2-trace-clean.png); 真实 HTTP GET governance 回显行内 `evidence_voided`/`verification_evidence_voided` 均 false -> 真实 HTTP `:documents :revisions` 生成 v2 再 `:documents :discard` 作废 v2 -> 重载两表列在原徽标后追加红色"证据已作废" (截图 h18ev-3-urs-voided.png, h18ev-4-trace-voided.png); 真实 HTTP GET governance 二次确认 `evidence_voided`/`verification_evidence_voided` 转 true 而追踪所指向 v1 `evidence_status` 仍 `registered` 不漂移 -> 真实 HTTP `:documents :restore` 恢复 v2 -> 重载红标消失 (截图 h18ev-5-urs-restored.png), GET 两布尔复归 false; 截图存 `reports/h18ev/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); "对最新版本被作废的证据显式标注"这一布尔事实由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的红色标签叠加/消失与真实 HTTP 回显; 只读标注**不构成任何门控或拦截** (证据作废后既有追踪照常登记/读取/流转, 是否据此阻断 Gate/关闭仍属"待规则"), 不做批量重算或历史留存, 不做作废主动提醒投递.

边界: 本项关闭 H18 待办里"已作废证据对历史追踪快照的显式标注"这一子边界 (追踪矩阵与 URS 两处已显式提示证据整体作废), 是 C03 追踪链与 H18 作废治理口径下 `implemented / local` 的又一项只读洞察; 但 H18 整行仍有"已作废证据对历史 Gate/验收决策快照的显式标注" (见下一段本轮已落地) 与"正式历史按保留策略归档"未完备, MySQL 回归亦待补, 故 H18 与 C03 两行均保持 `partial` 不上行.

## H18 Gate 已作废证据对验收决策快照显式标注 (本轮增补, 2026-09-29)

设计与口径: 承接上一段追踪链/URS 的证据作废标注, 把同一只读派生扩展到关口 (Gate) 验收快照. Gate 检查在 `checks` 里以不可变 `evidence_ids` 绑定了具体文档版本——评审人签核时看到的是当时有效证据, 若该证据业务编码后来被整体作废, 关口台账应显式提示"这项验收依赖的证据现已作废", 以免误信历史签核仍可靠. 本项复用 H18/C03 已建立的 `governance.evidence/voided-document-codes` 派生 (改为公有供 gates 复用), 在同一 `docs-by-id` 入参上再加一层只读标注, 完全沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移、免新命令、免新 kind、免新路由、不构成任何门控**. (1) 派生: 新公有纯函数 `governance.gates/gate-evidence-voided-model [voided-codes docs-by-id gate]` 对关口实例逐检查项核验——若某检查项 `evidence_ids` 命中的文档其 `code` 落在 `voided-codes` 集合 (`store/latest` 按 `code` 取最高 `revision` 后筛 `discarded`) 中即计一次, 派生整数 `gate_voided_checks` 与布尔 `gate_evidence_voided` (`gate_voided_checks > 0`). (2) 接线: `governance.clj` workspace 的 `let` 里在既有 `docs-by-id` 后计算一次 `voided-codes (evidence/voided-document-codes docs-by-id)`, 在 `:requirements` 的 `update` 之后对 `:gates` 追加第二遍 `(update :gates #(mapv (partial gates/gate-evidence-voided-model voided-codes docs-by-id) %))` (在既有 `gate-read-model` 就绪度之后, 就绪度徽标不变, 作废标叠加). 因引用守卫使被 Gate 检查直接引用的版本不可作废, 标注为 true 的成立路径同追踪链——"检查快照指向旧 v1、同编码新 v2 事后被作废", 且快照自身 `evidence_ids` 仍为 v1 不漂移. (3) 前端: "Gate检查与评审"台账"检查就绪度"列在原有徽标 (检查 N/M、豁免、待满足、可签核) 之后追加红色"证据已作废 N"标签 (N 为 `gate_voided_checks`), 键名不带尾随 `?`.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 69 tests / 913 assertions, 0 failures/errors (新增 `discarded-evidence-latest-revision-is-flagged-in-gate-snapshot-read-model` 1 例: 建项目+文档 v1 (`GATE-EV`) -> 建含单必需检查 `E-1` 的关口模板与实例 -> `:gates :checks` 把 `E-1` 标记通过且绑定 v1 (`evidence_ids` `[(:id doc)]`) -> 断言 workspace `gate_evidence_voided` false、`gate_voided_checks` 0 -> `:documents :revisions` 生成同编码 v2 (`revision` 2) -> `:documents :discard` 作废 v2 -> 断言 `gate_evidence_voided` true、`gate_voided_checks` 1, 而检查快照 `[:checks 0 :evidence_ids]` 仍 `[(:id doc)]` 不漂移 -> `:documents :restore` 恢复 v2 后两值复归 false/0; 另以混合样本 `{a: rev1 registered, b: rev2 discarded}` 直接纯函数测 `voided-document-codes` 命中集与 `gate-evidence-voided-model` 的命中/未命中/缺文档 id) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 171 tests / 1841 assertions, 0 failures/errors, `voided-document-codes` 公有化与 `:gates` 第二遍 `update` 未造成既有关口检查就绪度/关口门控/追踪链证据发布/URS 验证证据作废等 read-model 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h18g.spec.js` 1 passed (17.6s), 无未捕获 JS 错误: 界面 admin 建项目+合成独立审核人 -> 真实 HTTP 建含单必需检查模板与关口实例, 真实 HTTP 建证据文档 v1 并 `:gates/:id/checks` 把 `E-1` 标记通过且绑定 v1 -> 重载 Gate 页签"检查就绪度"列显绿色"检查 1/1""可签核"且无红"证据已作废" (截图 h18g-1-clean.png), 真实 HTTP GET governance 回显 `gate_evidence_voided` false/`gate_voided_checks` 0 -> 真实 HTTP `:documents/:id/revisions` 生成 v2 再 `:documents/:id/discard` 作废 v2 -> 重载"检查就绪度"列在原徽标后追加红色"证据已作废 1"且"可签核"仍在 (截图 h18g-2-voided.png), GET 二次确认 `gate_evidence_voided` true/`gate_voided_checks` 1 而 `checks[0].evidence_ids` 仍 `[v1]` 不漂移 -> 真实 HTTP `:documents/:id/restore` 恢复 v2 -> 重载红标消失 (截图 h18g-3-restored.png), GET 两值复归 false/0; 截图存 `reports/h18g/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); "对最新版本被作废的证据在关口快照上显式标注"这一布尔事实由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的红色标签叠加/消失与真实 HTTP 回显; 只读标注**不构成任何门控或拦截** (证据作废后既有已签核关口照常读取与流转, 是否据此阻断关闭/重签仍属"待规则"), 不做批量重算、不做作废主动提醒投递、不做已作废证据对历史 Gate 决策"须重新评审"的强制流程.

边界: 本项关闭 H18 待办里"已作废证据对历史 Gate/验收决策快照的显式标注"这一子边界, 至此 H18 三项作废证据显式标注 (追踪链 / URS 验证证据 / Gate 验收快照) 均已落地为 `implemented / local` 只读洞察; 但 H18 整行仍有"正式历史按保留策略归档"未完备, MySQL 回归亦待补, 且作废标注仍不接入任何重评审/阻断强制流程, 故 H18 行保持 `partial` 不上行.

## H18 DQ 已作废交付件对签认快照显式标注 (本轮增补, 2026-09-30)

设计与口径: 承接上一段 Gate 验收快照的证据作废标注, 把同一只读派生扩展到 DQ (设计确认) 签认快照. DQ 在 `deliverable_ids` 里以不可变文档版本 `id` 绑定了确认所依据的交付件——签认时看到的是当时有效交付件, 若该交付件业务编码后来被整体作废, DQ 台账应显式提示"这次确认依赖的交付件现已作废", 以免误信历史签认仍可靠. 本项复用 H18/C03/Gate 已建立的 `governance.evidence/voided-document-codes` 公有派生, 在同一 `docs-by-id` 入参上再加一层只读标注, 完全沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移、免新命令、免新 kind、免新路由、不构成任何门控**. (1) 派生: 新公有纯函数 `governance.quality/dq-deliverable-voided-model [voided-codes docs-by-id dq]` 对 DQ 实例逐交付件核验——若某 `deliverable_ids` 命中的文档其 `code` 落在 `voided-codes` 集合 (`store/latest` 按 `code` 取最高 `revision` 后筛 `discarded`) 中即计一次, 派生整数 `dq_voided_deliverables` 与布尔 `dq_deliverable_voided` (`dq_voided_deliverables > 0`). 与既有 `dq_stale` 正交: `dq_stale` 只问"同编码是否已存在更高 `revision` 更新版本" (不论状态), 本项问"所引编码的**最新版本**是否恰为 `discarded`", 故"先修订 v2 再作废 v2"时二者可同时为 true. (2) 接线: `governance.clj` workspace 复用 `let` 里已算好的 `voided-codes`, 在既有 `(update :dqs ... dq-read-model ...)` 之后追加第二遍 `(update :dqs #(mapv (partial quality/dq-deliverable-voided-model voided-codes docs-by-id) %))` (失效徽标不变, 作废标叠加). 因引用守卫使被 DQ `deliverable_ids` 直接引用的版本不可作废, 标注为 true 的成立路径同追踪链/Gate——"签认快照指向旧 v1、同编码新 v2 事后被作废", 且快照自身 `deliverable_ids` 仍为 v1 不漂移. (3) 前端: "DQ 编制与确认"台账在"版本失效"列后新增"交付件作废"列, 命中时红色"已作废 N"标签 (N 为 `dq_voided_deliverables`), 未命中绿色"未作废", 键名不带尾随 `?`.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 70 tests / 929 assertions, 0 failures/errors (新增 `discarded-deliverable-latest-revision-is-flagged-in-dq-snapshot-read-model` 1 例: 建项目+文档 v1 (`DQ-DLV`) -> 建含单必需检查 `C-1` 且 `deliverable_ids` 绑定 v1 的 DQ -> 断言 workspace `dq_deliverable_voided` false、`dq_voided_deliverables` 0 -> `:documents :revisions` 生成同编码 v2 (`revision` 2) -> 断言 `dq_stale` true 而 `dq_deliverable_voided` 仍 false (有更新版但未作废) -> `:documents :discard` 作废 v2 -> 断言 `dq_deliverable_voided` true、`dq_voided_deliverables` 1, 而 DQ `:deliverable_ids` 仍 `[(:id doc)]` 不漂移 -> `:documents :restore` 恢复 v2 后两值复归 false/0; 另以混合样本 `{a: rev1 registered, b: rev2 discarded}` 直接纯函数测 `dq-deliverable-voided-model` 的命中/未命中/缺文档 id/空交付件) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 172 tests / 1857 assertions, 0 failures/errors, `:dqs` 第二遍 `update` 未造成既有 DQ 失效判定/局部暂停/追踪链证据发布/URS 验证证据作废/Gate 验收快照作废等 read-model 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h18dq.spec.js` 1 passed (53.5s), 无未捕获 JS 错误: 界面 admin 建项目 -> 真实 HTTP 建证据文档 v1 并建 `deliverable_ids` 绑定 v1 的 DQ -> 重载 DQ 页签"交付件作废"列显绿色"未作废"且"版本失效"列"版本有效" (截图 h18dq-1-clean.png), 真实 HTTP GET governance 回显 `dq_deliverable_voided` false/`dq_voided_deliverables` 0 -> 真实 HTTP `:documents/:id/revisions` 生成 v2 -> 重载"版本失效"列翻红"交付件已更新"而"交付件作废"仍"未作废" (证明两口径正交) -> 真实 HTTP `:documents/:id/discard` 作废 v2 -> 重载"交付件作废"列追加红色"已作废 1"且"交付件已更新"仍在 (截图 h18dq-2-voided.png), GET 二次确认 `dq_deliverable_voided` true/`dq_voided_deliverables` 1 而 `deliverable_ids` 仍 `[v1]` 不漂移 -> 真实 HTTP `:documents/:id/restore` 恢复 v2 -> 重载"已作废 1"消失复归"未作废" (截图 h18dq-3-restored.png), GET 两值复归 false/0; 截图存 `reports/h18dq/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); "所引编码最新版本被作废"这一布尔事实由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的红色"已作废 N"标签叠加/消失与真实 HTTP 回显及与"交付件已更新"徽标的正交并存; 只读标注**不构成任何门控或拦截** (交付件作废后既有已签认 DQ 照常读取与流转, 是否据此阻断重签仍属"待规则"), 不做批量重算、不做作废主动提醒投递、不做已作废交付件对历史 DQ 签认"须重新确认"的强制流程.

边界: 本项关闭 H18 待办里"已作废交付件对 DQ 签认快照的显式标注"这一子边界, 至此 H18 四项作废证据/交付件显式标注 (追踪链 / URS 验证证据 / Gate 验收快照 / DQ 签认快照) 均已落地为 `implemented / local` 只读洞察; 但 H18 整行仍有"正式历史按保留策略归档"未完备, MySQL 回归亦待补, 且作废标注仍不接入任何重签/阻断强制流程, 故 H18 行保持 `partial` 不上行.

## B08 DQ 必需检查就绪度只读列 (本轮增补, 2026-09-30)

设计与口径: DQ 检查清单每项带 `required` 布尔 (缺省 true), `check-dq!` 早已按"全部必需项通过"把状态推到 `ready`, 但台账原有"检查通过"列只回显 `dq_passed/dq_total` (对**全部**清单项计数), 看不出签认真正依赖的"必需项还差几条"——这与关口台账此前存在的"检查就绪度"盲区同类. 本项把 Gate "检查就绪度"的口径延伸到 DQ, 完全沿用"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移、免新命令、免新 kind、免新路由、不构成任何门控**. (1) 派生: 在既有公有纯函数 `governance.quality/dq-read-model` 内追加四个只读键——`dq_required_total` (`filter :required` 项数), `dq_required_passed` (其中 `:passed` 为真者), `dq_required_missing` (`total - passed`), `dq_required_met` (`(= total passed)` 即全部必需项已通过, 与可选检查项是否通过无关; 无必需项时恒为 true, 与 `check-dq!` 的 `every?` 口径一致). 与 `dq_passed/dq_total` 正交: 可选未过而必需全过时"检查通过"显 `2/3` 而"必需检查就绪度"显绿"必需就绪 2/2", 恰说明签认已就绪. (2) 接线: 无新增——`dq-read-model` 已在 workspace 的 `(update :dqs ...)` 里逐条 enrich, 新键随既有派生一并产出. (3) 前端: "DQ 编制与确认"台账在"检查通过"列后新增"必需检查就绪度"列, 无必需项灰"无必需项", 就绪绿"必需就绪 rp/rt", 未就绪红"必需 rp/rt 缺 X", 键名不带尾随 `?`.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 71 tests / 948 assertions, 0 failures/errors (新增 `dq-required-check-readiness-is-flagged-in-read-model` 1 例: 建项目 + 建含两条必需 R-1/R-2 与一条可选 O-1 的 DQ -> 初始 workspace 断言 `dq_required_total` 2、`dq_required_passed` 0、`dq_required_missing` 2、`dq_required_met` false 且 `dq_passed` 0/`dq_total` 3 -> `:dqs :checks` 通过 R-1 + 可选 O-1 而 R-2 未过 -> 断言 `dq_required_passed` 1、`dq_required_missing` 1、`dq_required_met` 仍 false 而 `dq_passed` 已 2 (可选计入总通过) -> `:dqs :checks` R-1/R-2 全过而可选 O-1 退回未过 -> 断言 `dq_required_met` true、`dq_required_missing` 0、`dq_passed` 仍 2/`dq_total` 3 且 `status` "ready" (必需全过即就绪, 与可选无关); 另纯函数直测 `dq-read-model` 对无必需项清单给 `dq_required_total` 0 且 `dq_required_met` true) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 173 tests / 1876 assertions, 0 failures/errors, `dq-read-model` 新增四键未造成既有 DQ 失效判定/DQ 交付件作废/局部暂停/追踪链证据发布等 read-model 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-dqreq.spec.js` 1 passed (16.2s), 无未捕获 JS 错误: 界面 admin 建项目 -> 真实 HTTP 登记 2 必需 1 可选清单的 DQ -> 重载 DQ 页签"必需检查就绪度"列显红色"必需 0/2 缺 2"且"检查通过"列"0/3" (截图 dqreq-1-initial.png), GET 回显 `dq_required_total` 2/`dq_required_passed` 0/`dq_required_missing` 2/`dq_required_met` false -> 真实 HTTP `/dqs/:id/checks` 通过 R-1 + 可选 O-1 而 R-2 未过 -> 重载显红"必需 1/2 缺 1"而"检查通过"已"2/3" (证明可选已过不消减必需缺口, 截图 dqreq-2-partial.png), GET `dq_required_met` false/`dq_passed` 2 -> 真实 HTTP `/dqs/:id/checks` R-1/R-2 全过而可选退回未过 -> 重载翻绿"必需就绪 2/2"且红"缺 1"消失而"检查通过"仍"2/3" (截图 dqreq-3-ready.png), GET `dq_required_met` true/`dq_required_missing` 0/`status` "ready"; 截图存 `reports/dqreq/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); "必需全过即就绪"这一布尔事实由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的红/绿徽标翻转与真实 HTTP 回显及与"检查通过"全量计数列的正交并存; 只读派生**不构成任何门控或拦截** (就绪与否仅界面提示, 提交签认仍由既有 `submit-dq!` 的必需项校验把关), 不新增迁移/kind/命令/状态值/路由, 不改动任何不可变版本.

边界: 本项为 B08 DQ 台账补齐"必需项就绪度"只读可见性 (与 B09 关口"检查就绪度"同口径), 属 `implemented / local` 的只读洞察增量; B08 整行核心闭环 (清单/交付件/独立签认/失效标注) 早已 `implemented / local`, 本增量不上行也不改变其状态; 待补项仍为交付配置业务口径与 MySQL 回归.

## Gate 已通过证据尚未正式发布只读标注 (本轮增补, 2026-09-30)

设计与口径: 承接 Gate "证据已作废"标注, 本项问一个更前置的问题——关口检查项已勾选通过 (`:passed`) 并绑定 `evidence_ids` (登记时锁定的不可变文档版本 `id`), 但所绑文档可能尚未走完发布流程 (文档须经 `submit` + 独立审核人 `decide-release!` 才从 `registered`/`in_review` 变 `approved`). 存在"检查已标记通过、证据却还没正式发布"的窗口, 台账应显式提示, 以免评审人误以为该项证据已具正式效力. 本项复用 H18/C03/Gate/DQ 已确立的"给治理台账加只读派生洞察"套路, **不落库、不投递、不改动任何不可变版本, 免迁移、免新命令、免新 kind、免新路由、不构成任何门控**. (1) 派生: 新公有纯函数 `governance.gates/gate-evidence-release-model [docs-by-id gate]` 逐检查项核验——只统计 `:passed` 为真且 `evidence_ids` 非空的检查项, 若其中任一绑定文档当前 `:status` 不为 `"approved"` (含 `registered`/`in_review`/`rejected`/缺档) 即计一次待发布, 派生整数 `gate_evidence_checks` (通过且绑证据的检查项数), `gate_evidence_pending` (其中证据未全发布者) 与布尔 `gate_evidence_unreleased` (`gate_evidence_pending > 0`). 与既有 `gate_evidence_voided` 是**两个正交口径**: 作废问"所引编码最新版本是否恰为 `discarded`", 本项问"所引版本是否尚未 `approved`"; 因快照 `evidence_ids` 不漂移, "快照指向已发布 v1 (`unreleased` false) 而同编码 v2 事后被作废 (`voided` true)"可同时成立. (2) 接线: `governance.clj` workspace 复用 `let` 里已算好的 `docs-by-id`, 在既有 `(update :gates ... gate-evidence-voided-model ...)` 之后追加第三遍 `(update :gates #(mapv (partial gates/gate-evidence-release-model docs-by-id) %))`. (3) 前端: "Gate检查与评审"台账在"检查就绪度"列红色"证据已作废 N"之后, 命中时追加金色"证据待发布 N"标签 (N 为 `gate_evidence_pending`), 与红色作废标、绿色可签核标叠加呈现, 键名不带尾随 `?`.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 72 tests / 968 assertions, 0 failures/errors (新增 `passed-evidence-not-yet-released-is-flagged-in-gate-snapshot-read-model` 1 例: 建项目 + 建含 2 必需 E-1/E-2 与 1 可选 E-3 的模板与关口实例 -> 三条检查均 `:passed` 但 E-1/E-2 各绑一份 `registered` 文档、E-3 无证据 -> 断言 `gate_evidence_checks` 2 (可选无证据项不计)、`gate_evidence_pending` 2、`gate_evidence_unreleased` true -> 独立签发 doc-a -> `gate_evidence_pending` 降 1 仍 true -> 独立签发 doc-b -> `gate_evidence_pending` 0、`gate_evidence_unreleased` false 而 `gate_evidence_checks` 恒 2 -> 再对 doc-a 修订 v2 并作废 v2 -> 断言 `gate_evidence_unreleased` 仍 false 而 `gate_evidence_voided` 翻 true (两口径正交); 另纯函数直测 `gate-evidence-release-model` 的全发布/含未发布/含驳回/缺档/未通过项/空证据/多项计数七种口径) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 174 tests / 1896 assertions, 0 failures/errors, `:gates` 第三遍 `update` 未造成既有 Gate 就绪度/Gate 证据作废/DQ 交付件作废/追踪链证据发布等 read-model 用例回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-gaterel.spec.js` 1 passed (22.5s), 无未捕获 JS 错误: 界面 admin 建项目 + 合成独立审核人 -> 真实 HTTP 建含 1 必需检查的模板与关口实例并建 `registered` 证据文档 v1、把检查标记通过且绑定 v1 -> 重载 Gate 页签"检查就绪度"列显绿色"检查 1/1"+蓝色"可签核"+金色"证据待发布 1"且无"证据已作废" (截图 gaterel-1-pending.png), GET 回显 `gate_evidence_checks` 1/`gate_evidence_pending` 1/`gate_evidence_unreleased` true/`gate_evidence_voided` false -> admin 真实 HTTP `:documents/:id/submit` 指定审核人、审核人以第二真实登录上下文 `:documents/:id/decision` 批准发布 -> 重载金色"证据待发布 1"消失而"检查 1/1""可签核"仍在 (截图 gaterel-2-released.png), GET `gate_evidence_unreleased` false/`gate_evidence_pending` 0 -> 真实 HTTP 对 v1 修订 v2 并作废 v2 -> 重载红色"证据已作废 1"出现而金色"证据待发布"仍无 (截图 gaterel-3-orthogonal.png), GET `gate_evidence_voided` true 而 `gate_evidence_unreleased` 仍 false 且 `checks[0].evidence_ids` 仍 `[v1]` 不漂移; 截图存 `reports/gaterel/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL); "已通过证据是否已正式发布"这一布尔事实由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的金色"证据待发布 N"徽标出现/消失、双上下文真实发布流程、以及与红色"证据已作废"徽标的正交叠加; 只读标注**不构成任何门控或拦截** (证据未发布不影响关口既有签核与流转, 仅界面提示; 是否据此阻断关口批准仍属"待规则"), 不做批量重算、不做未发布证据的主动提醒投递、不做"证据须先发布方可签核"的强制流程.

边界: 本项为 Gate 台账补齐"已通过证据尚未正式发布"只读可见性, 是 H18 作废证据家族 (追踪链 / URS 验证证据 / Gate 验收快照 / DQ 签认快照) 之外一个正交的"发布态"洞察维度, 属 `implemented / local` 只读增量; Gate 核心闭环 (检查/证据绑定/独立签核/就绪度/作废标注) 早已 `implemented / local`, 本增量不上行也不改变其状态; 待补项仍为证据未发布是否须阻断关口批准的规则确认与 MySQL 回归.

## H06 挣值绩效偏差登记与纠正措施闭环 (本轮增补, 2026-09-30)

设计与口径: 承接 H06 "执行进展与预测", 挣值与完工预测面板此前已只读派生 SPI/CPI/EAC 等指标, 但当进度或成本真正落后时, 界面一直没有把"哪一项指标越界"聚合成一条显式偏差记录, 更没有一处供责任人登记纠正措施并跟踪其闭环的入口. 本项补齐"绩效偏差识别 + 纠正措施登记 + 独立核验闭环"这一子能力, 采用两段式: (1) 只读派生偏差 — 免迁移, 免新 kind, 免新命令, 免新路由, 不构成门控: 新增纯函数 `planning.earned-value/performance-variances` 逐口径核验, 派生整数键 `variance_action_total` (为该偏差登记的措施数), `variance_action_open` (其中未闭环数, 复用 `variance-action-open?` 排除 `closed`/`converted`) 与状态键 `variance_action_state` (`unimplemented` 零措施 / `in-progress` 有措施但未全闭环 / `completed` 全部闭环), 阈值 0.9 (SPI 或 CPI 非空且 < 0.9 分别判 `behind`/`over`), `planning.clj` read-plan 里以 `{:earned_value evm :performance_variances variances}` 暴露; (2) 写命令 — 复用 `action` 治理 kind, **免迁移**: 新命令 `[:actions :from-variance]` (路由 `POST /governance/actions/from-variance`) 经 `collaboration/variance-action!` 写入, `s/input!` 白名单 `[:title :owner_id :due_date :variance_kind :variance_status_date]`, `s/enum!` 限 `variance_kind` 为 `schedule`/`cost` (非法 400), `s/insert!` 落一条 `kind="action" status="open"` 记录并把 `variance_kind`/`variance_status_date` 写进 payload (store 的 `insert!` 将非 `:code/:owner_id` 字段序列化进 JSON, `decode` 读回时合并, 故命令结果回显这两个键), 闭环完全复用既有 C07 行动闭环链 `/actions/:rid/complete` (结果+证据+独立复核人 -> `in_review`) 与 `/actions/:rid/verify` (审批人 `pms:quality:approve` 且 `approver != submitted_by` -> `closed`). 前端: "进度卷积"页签挣值面板与历史面板之间新增 `views/variance-panel` "绩效偏差与纠正措施"面板, 逐条偏差回显 `variance-kind-labels` (进度落后/工时超支), 触发指标与阈值, 以及 `variance-state-labels` (尚未落实/落实中 N·未闭环 M/已闭环), 命中未闭环行提供"登记纠正措施"入口打开 `forms/variance-action-dialog` (标题责任人到期日必填, 责任人取 `w/user-options` 下拉). 键名不带尾随 `?`.

| 证据 | 实际记录 |
|---|---|
| 进度单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-progress-test'` 通过 10 tests / 135 assertions, 0 failures/errors (新增 `variance-derivation-and-coverage-are-pure-read-only` 与 `variance-action-registers-open-and-closes-through-independent-review` 两例: 前者对有派生+批准基线但零进展反馈的项目断言 `schedule_status behind` 且 `performance_variances` 含 schedule 项、`variance_action_state unimplemented`、`variance_action_total 0`, 纯函数直测阈值口径与状态翻转; 后者对登记后的措施断言初始 `open`, 经 complete (带同项目 document 证据 + 复核人 != 提交人) -> `in_review`, 再 verify (审批人 != 提交人) -> `closed`, 且偏差 `variance_action_state` 随之由 `in-progress` 转 `completed`, 非法 `variance_kind` 经服务层 400) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 176 tests / 1919 assertions, 0 failures/errors, read-plan 新增 `:performance_variances` 与 `[:actions :from-variance]` 命令未造成既有挣值/行动闭环/治理回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 compiled / 0 warnings (自定义列 render 用 `(aget row "jsKey")` 避免 `:infer-warning`) |
| HTTP 合同 (契约) | `contracts/governance.md` 命令表新增 `POST /actions/from-variance` 行 (title/owner_id/due_date/variance_kind, 可选 variance_status_date; 复用 action kind 免迁移; `s/enum!` 限 schedule/cost 非法 400; 复用 complete/verify 闭环) |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h06v.spec.js` 1 passed (47.9s), 无未捕获 JS 错误: fixture 用 admin 建项目 + 合成含 `pms:quality:approve` 的独立审核人 (第二真实登录上下文) + 设备模板 -> 派生计划但不填任何进展反馈 -> `toExecution` 进执行 -> 重载页签携带最新版本 -> 断言 `schedule_status behind`、`performance_variances` schedule 项 `variance_action_state unimplemented` -> "绩效偏差与纠正措施"面板显"进度落后"+"尚未落实" (截图 h06-1-variance-detected.png) -> 界面"登记纠正措施"填标题/责任人 (body-level portal 下拉 type+Enter)/到期日保存 -> 面板翻"落实中 (1 项 · 未闭环 1)" (截图 h06-2-action-registered.png) -> 真实 HTTP 对非法 `variance_kind` "risk" 打 `/actions/from-variance` 断言 400 -> 真实 HTTP complete (带同项目文档证据 + 复核人) 再 verify (审核人上下文) -> 面板翻"已闭环 (1 项 · 未闭环 0)" (截图 h06-3-action-closed.png); 截图存 `reports/h06-variance/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 偏差登记复用 `action` kind 的 JSON payload 无需建表); 偏差是否越界这一事实由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的"绩效偏差与纠正措施"面板状态徽标 (尚未落实 / 落实中 / 已闭环) 翻转、真实登记对话框与 body-level portal 责任人下拉、非法 `variance_kind` 的真实 HTTP 400、以及复用既有行动链的完成-独立核验闭环.

边界: 本项把 H06 从"仅有挣值数值可看"推进到"绩效偏差可识别 + 纠正措施可登记 + 可经独立核验闭环", 属 `partial` 行内的一个 well-scoped 子能力增量; H06 仍保持 `partial` 状态不上行, 因为"反馈独立审核"与"财务金额口径挣值 (增量7 费率后)"仍是真实待办. 本项**不构成任何自动门控** (偏差不阻断任何登记或流转, 闭环走的是既有行动链), 不新增迁移/kind/命令路由外的表结构, 不改变不可变版本.

## H06 偏差纠正措施闭环汇总只读面板 (本轮增补, 2026-09-30)

设计与口径: 承接上一子增量"绩效偏差登记与纠正措施闭环", 界面此前只在"绩效偏差与纠正措施"面板逐条回显每个偏差的落实状态, 但没有一处把跨全部挣值偏差的纠正措施**整体闭环健康度**聚合成一眼可读的汇总. 本项补齐这一只读派生洞察, 复用已验证的会议行动闭环口径, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: 新增纯函数 `planning.earned-value/variance-closure-summary` 在 `performance_variances` 之上跨全部偏差聚合, 输出整数键 `variance_count` / `variance_with_action` / `variance_closed` / `action_total` (带 `variance_kind` 标注的纠正措施总数) / `action_closed` / `action_open` / `action_closure_pct` (`round(100 x closed/total)`, 分母 0 取 0), 键名不带尾随 `?`; `planning.clj` read-plan 以 `:variance_closure` 暴露; 前端在挣值面板与逐条偏差面板之间新增 `views/variance-closure-panel` "偏差纠正措施闭环汇总"面板, 以指标卡展示上述字段, 闭环率按 100% (蓝) / 部分 (金) / 0% (红) 着色, 无偏差时提示"暂无绩效偏差". 该汇总与逐偏差明细同源, 只是把明细里散落的落实状态提升为项目级健康度.

| 证据 | 实际记录 |
|---|---|
| 进度单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-progress-test'` 通过 11 tests / 151 assertions, 0 failures/errors (新增 `variance-closure-summary-aggregates-across-variances`: (a) 纯函数直测三条覆盖态偏差 (in-progress/completed/unimplemented) + 四条措施 (含一条无 `variance_kind` 的普通会议行动) 断言 `variance_count 3` / `variance_with_action 2` / `variance_closed 1` / `action_total 3` / `action_closed 2` / `action_open 1` / `action_closure_pct 67`, 空输入各计数 0; (b) 集成路径 `project!`->`derive!`->`execution!` 后经 `[:actions :from-variance]` 登记一条 schedule 措施, 断言 `read-plan` 的 `:variance_closure` `action_total 1` / `action_closed 0` / `action_open 1` / `action_closure_pct 0`, 再经 complete + 独立 verify 闭环后翻为 `action_closed 1` / `action_open 0` / `action_closure_pct 100`) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 177 tests / 1935 assertions, 0 failures/errors, read-plan 新增 `:variance_closure` 未造成既有挣值/偏差/行动闭环/治理回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 6 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/planning.md` 新增"偏差纠正措施闭环汇总 `variance_closure`"段: 纯函数跨偏差聚合的整数键口径, 前端只读面板与着色, 明确不写存储 / 不新增 kind/命令/路由/表结构 / 不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h06v.spec.js` 1 passed (48.0s), 无未捕获 JS 错误: 在既有偏差登记闭环流程上追加对同页 "偏差纠正措施闭环汇总" 面板的核验 — 登记前断言 "措施总数" 与闭环率 "0%", 界面登记一条纠正措施后断言 "1 项" 与闭环率仍 "0%" (截图 h06-4-closure-partial.png), 经真实 HTTP complete + 独立上下文 verify 闭环后断言 "全部闭环偏差" 与闭环率翻为 "100%" (截图 h06-5-closure-complete.png); 原三张偏差明细截图 (h06-1/2/3) 一并复跑通过; 截图存 `reports/h06-variance/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总只读派生复用既有 `performance_variances` 与 `action` payload 无需建表); 汇总数字的正确性由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的汇总面板指标卡在登记 -> 独立核验闭环过程中的真实翻转 (0% -> 0% -> 100%) 与着色.

边界: 本项是 H06 `partial` 行内的又一个 well-scoped 只读子能力 (把逐条偏差落实状态提升为项目级闭环健康度汇总), 不改变不可变版本, 不新增任何写路径或门控; H06 仍保持 `partial` 不上行, 因为"反馈独立审核"与"财务金额口径挣值 (增量7 费率后)"仍是真实待办.

## H08 风险升级处置闭环汇总只读面板 (本轮增补, 2026-09-30)

设计与口径: 承接既有 H08 超阈值风险自动升级与独立确认状态机, 界面此前只在风险台账逐条以"待升级确认 / 升级已确认 / 升级已豁免 / 未触发"徽标回显单条升级处置状态, 但没有一处把跨全部风险的超阈值升级**独立确认处置进度**聚合成一眼可读的项目级汇总 (与刚交付的 H06 偏差闭环汇总面板为同套路的姊妹项). 本项补齐这一只读派生洞察, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: 新增纯函数 `governance.collaboration/risk-escalation-disposition-summary` 复用 `store/latest` 对风险记录聚合, 只筛 `escalated` 为真者按 `escalation_state` (pending/acknowledged/waived) 与 `escalation_level` (steering/management) 计数, 输出整数键 `total` / `escalated` / `not-escalated` / `pending` / `acknowledged` / `waived` / `by-level` (`[{level, count}]`), 键名不带尾随 `?`; `governance.clj` workspace 以 `:risk_escalation_summary` 暴露 (紧邻 `:risk_response_coverage`); 前端"风险与问题"页签在风险台账与覆盖度面板之后新增 `governance/risk-escalation-section` "风险升级处置汇总"面板, 以蓝色"风险总数 N" / 红色"已超阈值升级 N" / 橙色"待独立确认 N" / 绿色"已确认责成处置 N" / 青色"评估后豁免 N"标签回显, 并按两级以 geekblue/灰标签回显"管理层 · 计数 / 经理层 · 计数", 无风险时给出提示文案.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 73 tests / 988 assertions, 0 failures/errors (新增 `risk-escalation-disposition-summary-is-derived-read-only`: 界面命令登记 5x5=25 (steering) / 4x4=16 (management) / 3x5=15 (未达阈值) 三条风险 -> 断言汇总 `total 3` / `escalated 2` / `not-escalated 1` / `pending 2` / `acknowledged 0` / `waived 0` / `by-level` steering 1 management 1; 独立审批人 9302 对 steering 决策 approved 后 `pending 1` / `acknowledged 1`, 对 management 决策 rejected 后 `pending 0` / `acknowledged 1` / `waived 1`; 两次读 `risk_escalation_summary` 结果相等 (只读派生不漂移); 未达阈值的 3x5 风险行 `escalated` 为 false 且 `status` 仍 open) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 178 tests / 1955 assertions, 0 failures/errors, workspace 新增 `:risk_escalation_summary` 未造成既有升级门控/覆盖度/行动闭环/治理回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/governance.md` 新增"风险升级处置闭环汇总 `risk_escalation_summary`"段: 纯函数按 latest 风险聚合的整数键与分级口径, 前端只读面板与着色, 明确不写存储 / 不新增 kind/命令/路由/表结构 / 不构成门控 / 不改变既有 `escalate` 门控语义 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h08es.spec.js` 1 passed (48.4s), 无未捕获 JS 错误: 界面登记 5x5 (steering) / 4x4 (management) / 3x5 (未达阈值) 三条风险 -> "风险升级处置汇总"面板显示"风险总数 3 / 已超阈值升级 2 / 待独立确认 2 / 管理层·1 经理层·1"且无已确认/已豁免标签 (截图 h08es-1-escalated-pending.png); 真实 HTTP GET governance 回显 `risk_escalation_summary` 各字段一致; 由登记人之外的独立质量审批人第二真实浏览器上下文点"确认升级处置"对 steering 选"确认升级并责成处置" -> 面板"待独立确认"降到 1 且出现"已确认责成处置 1" (截图 h08es-2-one-acknowledged.png); 再对 management 选"评估后可在现层处置" -> "待独立确认"归零且"已确认责成处置 1 / 评估后豁免 1" (截图 h08es-3-closed-disposition.png); 真实 HTTP GET governance 二次确认 `pending 0`/`acknowledged 1`/`waived 1`, 3x5 风险 `escalated=false`/`status=open` 不漂移, steering `escalation_state=acknowledged` 且 `escalation_ack_by` 为审批人、风险 `status` 仍 open; 面板断言以 `panel(page, '风险升级处置汇总')` 作用域定位 (与姊妹"风险应对覆盖度"面板共用"风险总数"文案, 用 heading 精确过滤避免严格模式串台); 截图存 `reports/h08es/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总只读派生复用既有 `pms_gov_record` 风险 payload 的升级字段无需建表); 汇总数字的正确性由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的汇总面板标签在独立确认过程中的真实翻转 (待确认 2 -> 1 -> 0, 已确认/已豁免逐级出现).

边界: 本项是 H08 `partial` 行内的又一个 well-scoped 只读子能力 (把逐条超阈值升级的独立确认处置状态提升为项目级闭环健康度汇总), 不改变不可变版本, 不新增任何写路径或门控, 也不改变既有 `escalate` 门控语义; H08 仍保持 `partial` 不上行, 因为"升级通知投递"与"跨项目风险汇总升级"仍是真实待办.

## C09d 问题升级处置闭环汇总只读面板 (本轮增补, 2026-09-30)

设计与口径: 承接既有 C09d 阻断级/逾期问题自动升级与独立确认状态机, 界面此前只在问题台账逐条以"待升级确认 / 升级已确认 / 升级已豁免 / 未触发"徽标回显单条升级处置状态, 但没有一处把跨全部问题的自动升级**独立确认处置进度**聚合成一眼可读的项目级汇总 (与刚交付的 H08 风险升级处置汇总面板为同套路的姊妹项). 本项补齐这一只读派生洞察, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: 新增纯函数 `governance.collaboration/issue-escalation-disposition-summary` 复用 `store/latest` 对问题记录聚合, 只筛 `escalated` 为真者按 `escalation_state` (pending/acknowledged/waived) 与 `escalation_level` (steering/management) 计数, 输出整数键 `total` / `escalated` / `not-escalated` / `pending` / `acknowledged` / `waived` / `by-level` (`[{level, count}]`), 键名不带尾随 `?`; 与风险侧的唯一口径差异是问题读模型不对非阻断问题把 `escalated` 归一化为 `false`, 而是保持 `nil` (非阻断登记不写任何 escalation 键), 故 `filterv :escalated` 自然只命中阻断/逾期升级项; `governance.clj` workspace 以 `:issue_escalation_summary` 暴露 (紧邻 `:risk_escalation_summary` 与 `:action_closure`); 前端"风险与问题"页签在问题台账之后新增 `governance/issue-escalation-section` "问题升级处置汇总"面板, 以蓝色"问题总数 N" / 红色"已升级待处置 N" / 橙色"待独立确认 N" / 绿色"已确认责成处置 N" / 青色"评估后豁免 N"标签回显, 并按两级以 geekblue/灰标签回显"管理层 · 计数 / 经理层 · 计数", 无问题时给出提示文案.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 74 tests / 1008 assertions, 0 failures/errors (新增 `issue-escalation-disposition-summary-is-derived-read-only`: 命令登记 blocker 逾期 (due 2020-01-10 -> steering) / blocker 在办 (due 2099-12-31 -> management) / major (不写 escalation 键) 三条问题 -> 断言汇总 `total 3` / `escalated 2` / `not-escalated 1` / `pending 2` / `acknowledged 0` / `waived 0` / `by-level` steering 1 management 1; 独立审批人 9302 对 steering 决策 approved 后 `pending 1` / `acknowledged 1`, 对 management 决策 rejected 后 `pending 0` / `acknowledged 1` / `waived 1`; 两次读 `issue_escalation_summary` 结果相等 (只读派生不漂移); major 问题行 `escalated` 为 `nil` 且 `status` 仍 open) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 179 tests / 1975 assertions, 0 failures/errors, workspace 新增 `:issue_escalation_summary` 未造成既有问题升级门控/风险升级汇总/覆盖度/行动闭环/治理回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (增量, 与 H08 面板共享既有产物) |
| HTTP 合同 (契约) | `contracts/governance.md` 新增"问题升级处置闭环汇总 `issue_escalation_summary` (C09d 延伸)"段: 纯函数按 latest 问题聚合的整数键与分级口径, 前端只读面板与着色, 明确不写存储 / 不新增 kind/命令/路由/表结构 / 不构成门控 / 不改变既有 `escalate` 门控语义 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c09es.spec.js` 1 passed, 无未捕获 JS 错误: 界面登记 blocker 逾期 (2020-01-10, steering) / blocker 在办 (2099-12-31, management) / major (未来日) 三条问题 -> "问题升级处置汇总"面板显示"问题总数 3 / 已升级待处置 2 / 待独立确认 2 / 管理层·1 经理层·1"且无已确认/已豁免标签 (截图 c09es-1-escalated-pending.png); 真实 HTTP GET governance 回显 `issue_escalation_summary` 各字段一致; 由登记人之外的独立质量审批人第二真实浏览器上下文点"确认升级处置"对 steering 选"确认升级并责成处置" -> 面板"待独立确认"降到 1 且出现"已确认责成处置 1" (截图 c09es-2-one-acknowledged.png); 再对 management 选"评估后可在现层处置" -> "待独立确认"归零且"已确认责成处置 1 / 评估后豁免 1" (截图 c09es-3-closed-disposition.png); 真实 HTTP GET governance 二次确认 `pending 0`/`acknowledged 1`/`waived 1`, major 问题 `escalated` 为假且 `status=open` 不漂移, steering `escalation_ack_by` 为审批人; 面板断言以 `panel(page, '问题升级处置汇总')` 作用域定位 (与姊妹风险面板用 heading 精确过滤避免严格模式串台), 逐条截图用 `scrollIntoViewIfNeeded` + 元素级 `screenshot` 规避抽屉内部滚动折叠; 截图存 `reports/c09es/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总只读派生复用既有 `pms_gov_record` 问题 payload 的升级字段无需建表); 汇总数字的正确性由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的汇总面板标签在独立确认过程中的真实翻转 (待确认 2 -> 1 -> 0, 已确认/已豁免逐级出现).

边界: 本项是 C09 `implemented / local` 行内的又一个 well-scoped 只读子能力 (把逐条问题升级的独立确认处置状态提升为项目级闭环健康度汇总), 不改变不可变版本, 不新增任何写路径或门控, 也不改变既有 `escalate` 门控语义; 汇总只反映升级与确认状态推进到哪一步, 不等于处置本身是否到位或问题是否已解决, 也不做升级通知的外部投递 (投递仍属 C11 待办).

## C07 会议纪要发布覆盖度只读面板 (本轮增补, 2026-09-30)

设计与口径: 承接既有 C07f 纪要受控发布闭环 (recorded 提交 -> in_review -> approved 批准归档, 及 C07g 的 discarded 受控作废), 界面此前只在会议台账逐条以"草稿 / 发布审批中 / 已发布 / 已作废"徽标回显单条纪要发布状态, 但没有一处把跨全部会议的发布推进聚合成一眼可读的项目级汇总 (与刚交付的"会议行动闭环率"面板为同套路的发布侧姊妹项). 本项补齐这一只读派生洞察, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: 新增纯函数 `governance.collaboration/meeting-release-coverage` 复用 `store/latest` 以每个 `code` 的最新有效版本为统计单位对会议聚合, 按 `:status` 计数输出整数键 `total` / `approved` / `in-review` / `recorded` / `discarded` 与 `release-pct`, 键名不带尾随 `?`; 发布率 `release-pct = approved / (total - discarded)` 四舍五入整数 (分母刻意排除已作废会议使误登记的作废纪要不拖低发布率, 分母为 0 时给 0); `governance.clj` workspace 以 `:meeting_release_coverage` 暴露 (紧邻 `:action_closure`); 前端"会议行动"页签在会议台账与"会议行动闭环率"面板之间新增 `governance/meeting-release-coverage-section` "会议纪要发布覆盖度"面板, 以蓝色"会议总数 N" / 绿·金·红"已发布 P% (approved/denom)" / 橙色"发布审批中 N" / 灰色"草稿 N" / 红色"已作废 N"标签回显 (计数为 0 的状态标签不渲染), 无会议时给出占位提示.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 75 tests / 1023 assertions, 0 failures/errors (新增 `meeting-release-coverage-is-derived-read-only`: 命令登记四条会议覆盖四态 -> 草稿保持 recorded, 提交在办转 in_review, 提交并批准转 approved, recorded 直接作废转 discarded -> 断言汇总 `total 4` / `approved 1` / `in-review 1` / `recorded 1` / `discarded 1` / `release-pct 33` (分母 3 排除已作废); 批准在办后 `in-review 0` / `approved 2` / `release-pct 67`; 再作废草稿后 `recorded 0` / `discarded 2` / `release-pct 100` (分母收缩为 2); 两次读 `meeting_release_coverage` 结果相等 (只读派生不漂移); 已发布会议行 `status` 仍 approved, 作废会议行 `status` 仍 discarded) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 180 tests / 1990 assertions, 0 failures/errors, workspace 新增 `:meeting_release_coverage` 未造成既有纪要发布闭环 / 会议作废恢复 / 行动闭环 / 治理回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (增量, 与既有会议面板共享产物) |
| HTTP 合同 (契约) | `contracts/governance.md` 新增"会议纪要发布覆盖度只读汇总面板 `meeting_release_coverage` (C07 延伸)"段: 纯函数按 latest 会议聚合的整数键与"分母排除已作废"的发布率口径, 前端只读面板与着色, 明确不写存储 / 不新增 kind/命令/路由/表结构 / 不构成门控 / 不改变既有发布状态机语义 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c07mrc.spec.js` 1 passed, 无未捕获 JS 错误: 界面登记四条会议 (周例会草稿 / 设计评审在办 / 需求基线发布 / 重复登记作废) 推进到四种发布态 -> "会议纪要发布覆盖度"面板显示"会议总数 4 / 已发布 33% (1/3) / 发布审批中 1 / 草稿 1 / 已作废 1" (截图 c07mrc-1-four-states.png); 真实 HTTP GET governance 回显 `meeting_release_coverage` 各字段一致 (`total 4`/`approved 1`/`in-review 1`/`recorded 1`/`discarded 1`/`release-pct 33`); 由登记人之外的独立质量审批人第二真实浏览器上下文批准在办纪要 -> 面板升到"已发布 67% (2/3)"且"发布审批中"消失 (截图 c07mrc-2-second-published.png); 登记人再受控作废草稿 -> 分母收缩升到"已发布 100% (2/2) / 已作废 2"且"草稿"消失 (截图 c07mrc-3-full-release.png); 真实 HTTP GET governance 二次确认 `approved 2`/`discarded 2`/`recorded 0`/`in-review 0`/`release-pct 100`, 已发布会议仍 `approved`、已作废会议仍 `discarded`、草稿作废后为 `discarded` 不漂移; 每次写命令前重新打开页签刷新 `project_version` 规避乐观锁 409, 面板断言以 `panel(page, '会议纪要发布覆盖度')` heading 精确过滤作用域定位, 负向断言用 `/发布审批中\s*\d/` 与 `/草稿\s*\d/` 匹配数字标签以避开面板描述文案中的同名单词, 逐条截图用 `scrollIntoViewIfNeeded` + 元素级 `screenshot` 规避抽屉内部滚动折叠; 截图存 `reports/c07mrc/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总只读派生复用既有 `pms_gov_record` 会议 payload 的发布状态字段无需建表); 汇总数字的正确性由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的汇总面板标签在独立批准与受控作废过程中的真实翻转 (发布率 33% -> 67% -> 100%).

边界: 本项是 C07 `partial` 行内的又一个 well-scoped 只读子能力 (把逐条纪要发布状态提升为项目级发布健康度汇总), 不改变不可变版本, 不新增任何写路径或门控, 也不改变既有纪要发布/作废状态机语义; 发布覆盖度只反映各会议最新版本处于哪个发布状态, 不等于纪要内容质量或结论是否已落实, 亦不做发布进度漏斗或通知投递 (投递仍属 C11 待办), 故 C07 整行保持 `partial`.

## C07 会议行动可选优先级枚举字段 (本轮增补, 2026-09-30)

设计与口径: 会议行动台账此前只有行动内容, 负责人与到期日期, 没有一处能标记"这条行动到底有多急" (与已交付的风险响应策略 `risk-response-strategies` 与验证方式枚举为同一套路的写路径可选强类型字段). 本项给"从会议派生行动"这条用户写命令补一个可选优先级枚举字段, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: `governance.collaboration` 新增枚举集合 `action-priorities #{"high" "medium" "low"}`; `create-action!` 的 `s/input!` 白名单追加 `:priority`, 并在 `cond->` 里对 `(:priority body)` truthy 时才 `(s/enum! ... "优先级")` assoc 进 payload (未选时不写键, 视为未设定, 既有其它两条内部派生路径 `mitigation-action!` / `variance-action!` 不写该键故天然保持"未设定"零回归); 非法取值命中 `s/enum!` 走真实 HTTP 400. 前端"新增会议行动"对话框 (`governance_forms.cljs action-dialog`) 增加 `:type :select` 优先级下拉 (高/中/低) 并在 `:transform` 里把空值 `dissoc` 掉避免发送 `""`; 行动台账 (`governance.cljs action-section`) 在到期日期列后新增"优先级"列, 以 red"高" / orange"中" / green"低" 徽标回显, 无值显灰色"未设定". 优先级随 `action` payload JSON 持久化, 复用既有 `store/change!` 的 merge 语义在转 WBS 任务等状态流转中保留该键, 无需任何 DDL.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 76 tests / 1030 assertions, 0 failures/errors (新增 `meeting-action-priority-is-optional-enum-persisted`: 命令 `:meetings :actions` 带 `:priority "high"` 登记 -> 断言结果 `(:priority action)` 为 `"high"` 且在 `workspace` 回显; 不带优先级的普通行动 -> `(:priority ...)` 为 `nil` 且状态仍 `open` (未选不写键); `:priority "urgent"` 非法取值 -> `error-status` 400; `:priority "medium"` 行动经 `:actions :task` 转 WBS 后仍回显 `"medium"` 且状态 `converted` (merge 保留优先级跨流转)) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 181 tests / 1997 assertions, 0 failures/errors, `create-action!` 白名单与 `cond->` 追加 `:priority` 未造成既有会议行动闭环 / 派生行动作废守卫 / 挣值偏差 / 风险预防行动回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (增量, 与既有会议行动台账共享产物) |
| HTTP 合同 (契约) | `contracts/governance.md` 新增"会议行动可选优先级枚举字段 (C07 延伸)"段: `action-priorities` 枚举集合, `create-action!` 白名单追加 `:priority` 与 `cond->` 仅在取值存在时 `s/enum!` 校验并 assoc 的免迁移口径, 未选不写键 (其它内部派生路径保持未设定), 非法取值 400, 前端下拉与台账着色, 明确不新增 kind/命令/路由/表结构 / 不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c07ap.spec.js` 1 passed, 无未捕获 JS 错误: 界面在一条会议下"形成行动"对话框分别选高/中/低优先级并留一条未选登记 (截图 ap-1-dialog-priority.png 显示对话框优先级下拉选中"高"); 行动台账"优先级"列以红/橙/绿徽标回显高/中/低, 未选的显示灰色"未设定" (截图 ap-2-ledger-column.png / ap-3-priority-cells.png 元素级截图); 真实 HTTP GET governance 回显各行动 `priority` 为 `high`/`low`/`medium` 且未选那条为 `null`; 中优先级行动转 WBS 任务后仍回显 `medium` 且状态 `converted`; 真实 HTTP POST `/governance/meetings/:id/actions` 带非法 `priority "urgent"` 返回 400; 每次写命令前重新打开页签刷新 `project_version` 规避乐观锁 409, `choose` 助手在点击前 `expect(option).toBeVisible()` 防 typeahead 竞态; 截图存 `reports/ap/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 优先级随 `pms_gov_record` 的 `action` payload JSON 持久化无需建表或加列); 优先级取值合法性与跨流转保留由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的下拉选择与台账徽标着色及非法取值的真实 HTTP 400.

边界: 本项是 C07 `partial` 行内的又一个 well-scoped 写路径子能力 (给会议行动加可选优先级标注), 不落库到独立列, 不新增 kind/命令/路由/表结构, 不构成任何门控 (优先级高低不阻止任何登记或流转), 也不改变行动状态机语义; 优先级只是给行动打标, 不做按优先级排序/筛选/到期提醒投递 (投递仍属 C11 待办), 故 C07 整行保持 `partial`.

## H02 沟通日志可选实际渠道枚举字段 (本轮增补, 2026-09-30)

设计与口径: H02 沟通计划此前"标记已沟通"只记录沟通日期与纪要, 无法表达"这一次实际是用什么渠道沟通的" (计划里维护的 `channel` 是计划默认渠道, 不等于每次实际发生的渠道). 本项给 `log-communication!` 这条用户写命令补一个可选本次实际渠道枚举字段, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: 复用 `governance.stakeholders` 既有枚举集合 `channels #{"meeting" "email" "dashboard" "report" "review"}` (无需新增枚举); `log-communication!` 的 `s/input!` 白名单追加 `:channel`, 用 `(if-let [c (:channel body)] (s/enum! c channels "沟通方式") (:channel plan))` 计算生效渠道 (提供则校验枚举, 留空则回退沿用计划自身默认渠道), 生效值同时写入 `last_communication_channel` 与逐次追加的每条 `communication_log`; 非法取值命中 `s/enum!` 走真实 HTTP 400. 因回退语义使 `channel` 恒有一个生效值, 既有仅传 `on`/`note` 的旧用例行为不变 (回退到计划渠道). 前端"标记已沟通"对话框 (`governance_forms.cljs comm-plan-log-dialog`) 增加 `:type :select` 本次沟通渠道下拉 (会议/邮件/看板/报告/评审, 提示"留空则沿用计划渠道 X") 并在 `:transform` 里把空值 `dissoc` 掉避免发送 `""`; 沟通计划台账 (`governance.cljs comm-plan-section`) 在"渠道"列后新增"最近沟通方式"列, 以蓝色中文标签回显 `last_communication_channel`, 未记录时显灰色"未记录". 字段随 `comm-plan` payload JSON 持久化, 复用既有 `store/change!` 的 merge 语义, 无需任何 DDL.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 77 tests / 1039 assertions, 0 failures/errors (新增 `comm-plan-log-channel-is-optional-and-falls-back-to-plan`: 建 SH 干系人 + meeting/周频 CP 计划; 带 `:channel "email"` 标记已沟通 -> 断言结果 `:last_communication_channel` 为 `"email"` 且末条 `communication_log` 的 `:channel` 为 `"email"`, `next_date` 按周频 +7 顺延; 不带渠道再标记 -> 回退为计划渠道 `"meeting"` 且 `communication_log` 累加为 2 条; `:channel "smoke-signal"` 非法取值 -> `error-status` 400; 事后 `workspace` 回显计划 `:channel` 仍为 `"meeting"` 未被污染, `:last_communication_channel` 为回退后的 `"meeting"`)) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 182 tests / 2006 assertions, 0 failures/errors, `log-communication!` 白名单与回退语义追加 `:channel` 未造成既有沟通节奏顺延 / 到期预警 / 沟通计划生成会议闭环回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (增量, 与既有沟通计划台账共享产物) |
| HTTP 合同 (契约) | `contracts/governance.md` 的 `POST /comm-plans/:rid/log` 路由行与"干系人, RACI与沟通计划"段更新: 新增可选 `channel` 入参复用 `channels` 枚举, 留空回退计划默认渠道, 生效值写 `last_communication_channel` 与每条 `communication_log`, 非法渠道 400, 台账"最近沟通方式"列回显, 明确不新增 kind/命令/路由/表结构 / 不改变计划默认渠道字段 / 不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h02cl.spec.js` 1 passed, 无未捕获 JS 错误: 界面登记干系人与 meeting/周频沟通计划 -> 在"干系人与沟通"页签点计划行"标记已沟通"选"邮件" (截图 h02cl-1-dialog-channel.png 显示对话框"本次沟通渠道"下拉选中"邮件"且提示"留空则沿用计划渠道 meeting") -> 命令响应回显 `last_communication_channel="email"` 且末条留痕 `channel="email"`; 台账"最近沟通方式"列以蓝色标签回显"邮件" (截图 h02cl-2-ledger-email.png); 再次"标记已沟通"不选渠道 -> 回退计划渠道, 台账列翻为"会议" (截图 h02cl-3-ledger-fallback.png 元素级表格截图); 真实 HTTP GET governance 二次确认计划 `channel` 仍为 `meeting`, `last_communication_channel` 为回退后的 `meeting`, `communication_log` 渠道序列为 `["email","meeting"]`; 真实 HTTP POST `/governance/comm-plans/:id/log` 带非法 `channel "smoke-signal"` 返回 400; 每次写命令前重新打开页签刷新 `project_version` 规避乐观锁 409, `choose` 助手在点击前 `expect(option).toBeVisible()` 防 typeahead 竞态; 截图存 `reports/h02cl/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 本次实际渠道随 `pms_gov_record` 的 `comm-plan` payload JSON 持久化无需建表或加列); 渠道取值合法性与回退语义由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的下拉选择, 台账"最近沟通方式"列着色与回退及非法取值的真实 HTTP 400.

边界: 本项是 H02 `implemented / local` 行内的又一个 well-scoped 写路径子能力 (给沟通日志加可选本次实际渠道标注), 不落库到独立列, 不新增 kind/命令/路由/表结构, 不构成任何门控 (渠道为何不阻止任何记录), 也不改变沟通节奏顺延与到期预警语义; 渠道只是给每次沟通留痕打标, 不做按渠道聚合统计/筛选/外部通知投递 (自动提醒投递仍属 H02 待补齐项), 故 H02 整行的外部通知/定时派发边界不变.

## H02 干系人参与态度可选枚举字段 (本轮增补, 2026-09-30)

设计与口径: H02 干系人识别此前只登记分类 / 角色 / 关注度 / 影响力, 缺少 PMBOK 投入度评估矩阵所要求的"当前参与态度"这一维度, 无法表达"这个干系人现在对项目的态度是未知晓 / 抵制 / 中立 / 支持 / 主导". 本项给 `stakeholder-fields!` 这条用户写路径补一个可选参与态度枚举字段, 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成门控: 在 `governance.stakeholders` 新增枚举集合 `engagement-levels #{"unaware" "resistant" "neutral" "supportive" "leading"}`; `s/input!` 白名单追加 `:engagement`, 用 `cond->` 仅在 `(:engagement body)` 存在时 `(assoc :engagement (s/enum! ...))` — 提供则校验枚举 (非法取值走真实 HTTP 400), 留空则完全不写该键 (读回 `nil`), 因此既有仅传 code/name/role/category/interest/influence/owner_id 的旧用例行为不变 (零回归); 修订可改态度而 `code` 不变 (沿用不可变版本 `revise-stakeholder!` 的 code 守卫与 revision 递增). 前端"登记干系人"对话框 (`governance_forms.cljs stakeholder-dialog`) 在"影响力"字段后新增 `:type :select` 参与态度下拉 (未知晓/抵制/中立/支持/主导, 提示"留空则不设定当前参与态度") 并在 `:transform` 里把空串/`nil` `dissoc` 掉避免发送 `""`; 干系人台账 (`governance.cljs stakeholder-section`) 在"影响力"列后新增"参与态度"列, 以彩色中文标签回显 `engagement` (抵制=红, 中立=蓝, 支持=绿, 主导=金, 未知晓=默认), 未设定者显灰字"未设定". 字段随 `stakeholder` payload JSON 持久化, 复用既有 `store/change!` 的 merge 语义, 无需任何 DDL.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 78 tests / 1047 assertions, 0 failures/errors (新增 `stakeholder-engagement-is-optional-enum-persisted`: 建带 `:engagement "supportive"` 的干系人 -> 断言结果 `:engagement` 为 `"supportive"` 且 `workspace` 回显一致; 建不带态度的干系人 -> `:engagement` 为 `nil` 且状态仍 `active`; `:engagement "champion"` 非法取值 -> `error-status` 400; 对 supportive 者发 `:engagement "leading"` 修订 -> 新版 `:engagement` 为 `"leading"` 且 `:revision` 为 2, 而原版仍回显 `"supportive"` 不漂移) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 183 tests / 2014 assertions, 0 failures/errors, `stakeholder-fields!` 白名单与 `cond->` 值存在性判定追加 `:engagement` 未造成既有干系人登记 / 权力-利益象限 / RACI 指派 / 沟通受众 / 作废恢复回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (增量, 与既有干系人台账共享产物) |
| HTTP 合同 (契约) | `contracts/governance.md` 的 `POST /stakeholders` 路由行与"干系人, RACI与沟通计划"段更新: 新增可选 `engagement` 入参枚举 unaware/resistant/neutral/supportive/leading, 留空不写该键回退未设定, 非法取值 400, 修订可改态度而 code 不变, 台账"参与态度"列彩色回显, 明确不新增 kind/命令/路由/表结构 / 不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h02eg.spec.js` 1 passed, 无未捕获 JS 错误: 界面"登记干系人"选"支持" (截图 h02eg-1-dialog-engagement.png 显示对话框"参与态度(可选)"下拉选中"支持"且提示"留空则不设定当前参与态度") -> 命令响应回显 `engagement="supportive"`; 再登记一条选"抵制"回显 `resistant`, 一条不选回显 `null`; 台账"参与态度"列分别以彩色标签回显"支持"/"抵制"与灰字"未设定" (截图 h02eg-2-ledger-engagement.png 元素级表格截图); 真实 HTTP 对支持方发 `engagement "leading"` 修订 -> 台账新增一行 rev2 显金色"主导"而旧行仍"支持" (截图 h02eg-3-ledger-leading.png); 真实 HTTP GET governance 二次确认三条 `engagement` 分别为 `supportive`/`resistant`/`null`; 真实 HTTP POST `/governance/stakeholders` 带非法 `engagement "champion"` 返回 400; 每次写命令前重新打开页签刷新 `project_version` 规避乐观锁 409, `choose` 助手在点击前 `expect(option).toBeVisible()` 防 typeahead 竞态; 截图存 `reports/h02eg/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 参与态度随 `pms_gov_record` 的 `stakeholder` payload JSON 持久化无需建表或加列); 枚举合法性与留空回退语义由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的下拉选择, 台账"参与态度"列着色与未设定回退, 修订不漂移及非法取值的真实 HTTP 400.

边界: 本项是 H02 `implemented / local` 行内的又一个 well-scoped 写路径子能力 (给干系人加可选参与态度标注), 不落库到独立列, 不新增 kind/命令/路由/表结构, 不构成任何门控 (态度为何不阻止任何记录), 也不改变权力-利益象限, RACI 负载与沟通受众语义; 参与态度只是给干系人打标, 不做按态度聚合的投入度评估矩阵统计/筛选 (该 portfolio 级汇总面板仍属待补齐项), 故 H02 整行的外部通知/定时派发边界不变.

## H04 任务级基线进度偏差只读派生 (本轮增补, 2026-09-30)

设计与口径: H04 排程与关键路径此前已具备四类依赖 / 工作日历 / 浮动 / 关键路径 / 不可变基线, 以及节点级按工作日整体重排而不改写原基线, 但矩阵业务闭环明确要求"展示基线偏差", 而计划工作台只呈现当前排程与挣值, 缺少"当前排程相对已批准基线逐任务偏移了多少"这一视角. 本项在 `planning.baseline` 新增只读派生纯函数 `variance`: 取 `store/rows :planning/baselines` 中最新一条 `status="approved"` 的基线, 经 `record!` 解析其 `snapshot_json` 得到冻结排程 `(:schedule (:snapshot rec))`, 与 `store/snapshot` 的当前排程 `(:schedule current-snapshot)` 逐任务比较; 用 `java.time` `(.between ChronoUnit/DAYS ...)` 计算完成日日历天差 (正=延后 behind, 负=提前 ahead, 零=持平 on_baseline), 基线中不存在的当前任务标 `added`; 输出按 `task_id` 索引的 `:by-task` 偏差 map 与项目级 `:summary` (available / baseline_id / baseline_revision / total / on-baseline / behind / ahead / added / worst-finish-slip). `planning/read-plan` 用 `(merge % (get (:by-task bvar) (:task_id %) {}))` 把逐任务键 `baseline_state` / `baseline_start_variance` / `baseline_finish_variance` 富化进 `:tasks`, 并在 merge 结果暴露 `:baseline_variance (:summary bvar)`. 键名不带尾随 `?` 以免序列化为 JSON 字面键. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (偏差只读呈现, 既不阻断编辑也不阻断提交冻结). 前端 `planning.cljs` 在 WBS 台账"范围覆盖"列后新增"基线偏差"列 (延后=红并附 `+N 天`, 提前=绿, 持平=蓝, 新增=橙, 无已批准基线=灰"无基线"), 并在 WBS与排程页签顶部新增"基线进度偏差"汇总面板 (任务/持平/延后/提前/新增/最大完成延后/基线修订, 无基线时显"尚无已批准计划基线").

| 证据 | 实际记录 |
|---|---|
| 计划单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-planning-test'` 通过 13 tests / 119 assertions, 0 failures/errors (新增 `baseline-schedule-variance-is-derived-read-only`: 无已批准基线 -> `:available` 为 `false` 且 `:by-task` 为空; 批准基线且排程未漂移 -> 逐任务 `baseline_state` 为 `on_baseline`, `baseline_finish_variance` 为 0, summary `total` 1 / `on-baseline` 1; 批准后再延长任务A工期使其完成日晚于基线并新增基线外任务C -> A 为 `behind` 且 `worst-finish-slip` 等于 A 的正偏差, C 为 `added`, summary `total` 2 / `behind` 1 / `added` 1, 且 `read-plan` 前后 `project_version` 不漂移证明纯读不落库) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 184 tests / 2036 assertions, 0 failures/errors; `read-plan` 逐任务 merge 与 `:baseline_variance` 暴露未造成既有排程 / 关键路径 / 挣值 / 范围覆盖 / 重排保留基线回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (与既有 WBS 台账与范围覆盖面板共享产物) |
| HTTP 合同 (契约) | `contracts/planning.md` 的 `GET /planning` 读模型更新: 新增只读派生 `baseline_variance` 汇总 (available/baseline_id/baseline_revision/total/on-baseline/behind/ahead/added/worst-finish-slip) 与逐任务 `baseline_state`/`baseline_start_variance`/`baseline_finish_variance`; 明确取最新已批准基线冻结排程与当前排程逐任务比较, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h04bv.spec.js` 1 passed (49.0s), 无未捕获 JS 错误: 建两条叶任务 A(工期2)/B(工期3) -> admin 界面"提交计划审批"冻结 -> 独立审核人 (`pms:plan:approve` 角色 + 第二真实上下文) 经 `/planning/baselines/{id}/review` 批准基线 -> 延长任务A工期至 9 使完成日晚于基线 -> PUT 任务, 界面新增任务C -> 面板回显"任务 3 / 持平 1 / 延后 1 / 提前 0 / 新增 1 / 最大完成延后 9 天 / 基线修订 2" (截图 h04bv-1-panel.png), WBS"基线偏差"列分别显 A"延后 +9 天"(红), B"持平"(蓝), C"新增"(橙) (截图 h04bv-2-columns.png); 真实 HTTP GET `/planning` 二次确认 `baseline_variance` summary total 3 / behind 1 / added 1 / on-baseline 1 / worst-finish-slip>0 且逐任务 `baseline_state` 为 behind/on_baseline/added, A 的 `baseline_finish_variance`>0, C 为 `null`; 空态: 新项目有任务但无已批准基线 -> 面板"尚无已批准计划基线" 且列"无基线" (截图 h04bv-3-empty.png); 截图存 `reports/h04bv/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 偏差为读取时对已批准基线 `snapshot_json` 与当前 `store/snapshot` 排程的纯函数比较, 不落任何新表/列); behind/ahead/on_baseline/added 判定与汇总计数由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的面板徽标与逐任务列着色, 服务端回显一致及空态回退.

边界: H04 整行此前已为 `implemented / local`, 本项是补齐该行既有业务闭环描述中"展示基线偏差"这一子能力的只读派生实现, 不新增写路径/kind/命令/路由/表结构, 不构成任何门控 (偏差不阻止任何编辑或提交冻结), 也不改写不可变基线; 偏差口径 (以完成日日历天差衡量, 不引入成本基线偏差) 与关键路径/浮动/偏差的业务批准口径及全订单真实试点仍属待补齐项, 故不据此提升任何 `partial` 行.

## H05 资源超配项目级只读汇总 (本轮增补, 2026-09-30)

设计与口径: H05 资源容量此前已具备日容量 / 单日容量覆盖 / 任务分配, 以及按 `user_id` 跨未结束项目合并人员负荷并对设备按项目与日期汇总的逐日超配明细 `overallocations` (保护外部项目隐私, 只回匿名工时), 但矩阵业务闭环要求"超配冲突提示并经协调解决", 而工作台只呈现逐日明细表, 缺少"本项目整体超配到什么程度"这一项目级视角. 本项在 `planning.capacity` 新增只读派生纯函数 `overload-summary`: 对 `overloads` 返回的逐日明细行读取时聚合, 输出 `available / total-rows / distinct-resources / person-rows (scope="shared_person" 的行) / equipment-rows (total-rows - person-rows) / worst-excess-hours (apply max excess_hours) / peak-date (按日汇总超出工时最大的一天)`. `planning/read-plan` 把 `overloads` 绑定结果同时暴露为逐日 `:overallocations` 与项目级 `:overload_summary`. 键名不带尾随 `?` 以免序列化为 JSON 字面键. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (汇总只读呈现, 既不阻断保存/提交/冻结也不改变明细). 前端 `plan_views.cljs` 在"资源与日历"页签顶部"资源负荷检查"面板的逐日明细表上方新增彩色标签概览条 (超配资源 / 人日超配 / 设备日超配 / 最大单日超出 N 工时 / 峰值负荷日 D; 无超配时保留原"当前排程未发现资源超负荷"提示).

| 证据 | 实际记录 |
|---|---|
| 计划单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-planning-test'` 通过 14 tests / 133 assertions, 0 failures/errors (新增 `resource-overload-summary-is-derived-read-only`: 人员容量8对 A/B 各分配 6h 使 09-21/09-22 各 12h 超 4h + 设备容量8对 A 分配 10h 且 09-22 放宽到 12 仅 09-21 超 2h -> `available` 为 `true`, `total-rows` 3, `distinct-resources` 2, `person-rows` 2, `equipment-rows` 1, `worst-excess-hours` 4 (`==` 数值比较, 值为 BigDecimal), `peak-date` "2026-09-21", 且 `read-plan` 前后 `project_version` 不漂移证明纯读不落库; 无超配用例只用设备资源 (无 `user_id`, 不参与跨项目同人归集) 分配 4h<8 -> `available` 为 `false`, 计数 0, `peak-date` 为 `nil`, `overallocations` 为空) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 185 tests / 2050 assertions, 0 failures/errors; `read-plan` 暴露 `:overload_summary` 未造成既有排程 / 关键路径 / 挣值 / 范围覆盖 / 基线偏差 / 跨项目人员隐私回归 (早期一版用例曾以 `user_id 9201` 作人员资源, 因 `:once` 共享库下 `capacity/overloads` 按 `user_id` 跨未结束项目归集而污染 `dependency-dag-and-resource-overload` 与 `shared-person-capacity-preserves-other-project-privacy`, 已改用隔离人员 9203 + 无超配用例改设备资源消除归集) |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 6 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/planning.md` 的 `GET /planning` 读模型更新: 新增只读派生 `overload_summary` 汇总 (available/total-rows/distinct-resources/person-rows/equipment-rows/worst-excess-hours/peak-date), 明确由纯函数 `planning.capacity/overload-summary` 对逐日 `overallocations` 明细读取时聚合, 免迁移/免新命令/不构成门控, 明细口径不变 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h05ro.spec.js` 1 passed (25.4s), 无未捕获 JS 错误: 建两条叶任务 A/B (09-21 起 2 工作日) -> 真实 HTTP 建人员资源 (容量8, 绑定项目经理) 分配 A 6h + B 6h -> 09-21/09-22 各 12h 超 4h; 建设备资源 (容量8) 分配 A 10h 且 09-22 放宽到 12 -> 仅 09-21 超 2h -> "资源与日历"页签"资源负荷检查"面板顶部彩色标签回显"超配资源 2 / 人日超配 2 / 设备日超配 1 / 最大单日超出 4 工时 / 峰值负荷日 2026-09-21" (截图 h05ro-1-panel.png), 其下逐日明细 3 行 (机床 09-21 计划10/容量8/超出2, 工程师 09-21 与 09-22 各计划12/容量8/超出4); 真实 HTTP GET `/planning` 二次确认 `overload_summary` 各字段与 `overallocations.length` 为 3 一致; 空态: 新项目仅设备资源 4h<8 -> 面板"当前排程未发现资源超负荷" 且 `available=false` / `total-rows=0` / `peak-date=null` (截图 h05ro-2-empty.png); 截图存 `reports/h05ro/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有 `overloads` 逐日明细的纯函数聚合, 不落任何新表/列); 计数与峰值口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见的标签概览与逐日明细同源, 服务端回显一致及无超配回退.

边界: H05 整行保持 `partial` — 本项只是把已有逐日超配明细提升为项目级只读概览的洞察子能力, 一个只读子能力不改变整行状态; 矩阵业务闭环要求的"技能匹配 / 替代人员 / 请假规则 / 超配经协调解决的处置闭环 (如自动改派或容量再平衡建议)"仍未实现, 且汇总不构成任何门控 (不阻断保存/提交/冻结, 明细口径不变), 不新增写路径/kind/命令/路由/表结构.

## H05 任务工时投入覆盖度只读派生 (本轮增补, 2026-09-30)

设计与口径: H05 已具备日容量, 任务分配与面向"是否排太多"的超配保护 (上一子项 `overload-summary` 又给出项目级"超配到什么程度"的只读概览), 但工作台仍缺一个互补视角 — "哪些可分配任务根本还没排入任何工时". 本项在 `planning.capacity` 新增只读派生纯函数 `allocation-coverage`: 以可分配叶任务 (`task_type="task"`, 汇总与里程碑不接受工时分配, 故被排除在分母外, 与 `resources/allocate!` 规则一致) 为总体, 逐任务判断是否至少命中一条 `allocations` 记录, 输出 `available / total-tasks / with-allocations / without-allocations / coverage-pct (无任务时为 0, 否则 100*with/total 四舍五入取整) / unallocated-tasks (未投入任务的 task_id/wbs_code/name 清单)`. `planning/read-plan` 用已绑定的 `raw-tasks` (`store/rows q project :planning/tasks`) 与 `snapshot` 里的 `:allocations` 派生并暴露顶层 `:allocation_coverage` (下划线命名, 与既有 `:overload_summary` 一致; 内层键用连字符). 键名不带尾随 `?` 以免序列化为 JSON 字面键. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 既不阻断保存/提交/冻结也不改变任何写路径). 前端 `plan_views.cljs` 新增 `allocation-coverage` 面板 "任务投入覆盖度" (彩色标签 可分配任务 / 已有投入 / 未投入 / 投入覆盖率 + 未投入任务 volcano 标签清单), `planning.cljs` 在"资源与日历"页签将其置于负荷检查面板之前; 无普通任务时显空态 "尚无普通任务 (汇总与里程碑不计入投入覆盖).".

| 证据 | 实际记录 |
|---|---|
| 计划单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-planning-test'` 通过 15 tests / 151 assertions, 0 failures/errors (新增 `task-allocation-coverage-is-derived-read-only`: 纯函数用例 汇总+2 叶任务+里程碑, 2 条分配都指向同一任务 -> `total-tasks` 2 / `with-allocations` 1 / `without-allocations` 1 / `coverage-pct` 50 / 未投入清单含 "1.2"; 仅里程碑用例 -> `available=false` / `total-tasks` 0 / `coverage-pct` 0 / 未投入清单空; `read-plan` 集成用例 3 叶任务 A/B/C + 里程碑 + 设备资源, 分配 A 4h + B 4h (C 不分配) -> `total-tasks` 3 / `with-allocations` 2 / `without-allocations` 1 / `coverage-pct` 67 / 未投入清单 `["C"]`, 且前后 `project_version` 不漂移证明纯读不落库) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 186 tests / 2068 assertions, 0 failures/errors; `read-plan` 暴露 `:allocation_coverage` 未造成既有排程 / 关键路径 / 挣值 / 范围覆盖 / 基线偏差 / 资源超配汇总 / 跨项目人员隐私回归 (集成用例用设备资源, 无 `user_id`, 不触发跨项目同人归集) |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings |
| HTTP 合同 (契约) | `contracts/planning.md` 的 `GET /planning` 读模型更新: 新增只读派生 `allocation_coverage` 汇总 (available/total-tasks/with-allocations/without-allocations/coverage-pct/unallocated-tasks), 明确由纯函数 `planning.capacity/allocation-coverage` 对可分配叶任务与 `:allocations` 明细读取时聚合, 分母排除汇总与里程碑, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h05ac.spec.js` 1 passed, 无未捕获 JS 错误: 建三条叶任务 A/B/C + 里程碑 M, 设备资源分配 A 4h + B 4h (C 不分配) -> 真实 HTTP GET `/planning` 回显 `allocation_coverage` (`available=true` / `total-tasks` 3 / `with-allocations` 2 / `without-allocations` 1 / `coverage-pct` 67 / 未投入清单 wbs `["C"]`); "资源与日历"页签"任务投入覆盖度"面板彩色标签回显"可分配任务 3 / 已有投入 2 / 未投入 1 / 投入覆盖率 67%" 并列出未投入任务 "C 未分配C" (截图 h05ac-1-panel.png); 空态: 新项目仅里程碑 -> `available=false` / `total-tasks=0` / `coverage-pct=0` 且面板显"尚无普通任务 (汇总与里程碑不计入投入覆盖)." (截图 h05ac-2-empty.png); 截图存 `reports/h05ac/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 覆盖度为读取时对既有 `tasks` 与 `:allocations` 快照的纯函数聚合, 不落任何新表/列); 计数与覆盖率口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: H05 整行保持 `partial` — 本项只是"是否还没排入工时"这一只读洞察子能力, 与 `overload-summary` 的"是否排太多"互补, 一个只读子能力不改变整行状态; 技能匹配 / 替代人员 / 请假规则 / 超配经协调解决的处置闭环仍未实现, 覆盖度不构成任何门控 (不阻断保存/提交/冻结), 不新增写路径/kind/命令/路由/表结构.

## H05 关键路径投入缺口只读派生 (本轮增补, 2026-09-30)

设计与口径: 上一子项 `allocation-coverage` 给出"全部可分配任务里谁还没排入工时"的全局视角, 但进度控制真正关心的是"关键路径上还没排入工时的任务" — 这些任务一旦无人投入会直接推迟项目完工, 是最高优先级的进度风险. 本项在 `planning.capacity` 新增只读派生纯函数 `critical-path-staffing`: 以关键路径 (`schedule/schedule` 返回的 `:critical_path` 零浮动叶任务集合) 与可分配叶任务 (`task_type="task"`, 汇总与里程碑不接受工时分配故被排除在分母外) 的交集为总体, 逐任务判断是否至少命中一条 `allocations` 记录, 输出 `available / critical-tasks / staffed / unstaffed / staffing-pct (无任务时为 0, 否则 100*staffed/total 四舍五入取整) / unstaffed-tasks (缺口任务的 task_id/wbs_code/name 清单)`. `planning/read-plan` 用已绑定的 `raw-tasks`, `snapshot` 里的 `:allocations` 与 `[:schedule :critical_path]` 派生并暴露顶层 `:critical_path_staffing` (下划线命名, 与既有 `:overload_summary` / `:allocation_coverage` 一致; 内层键用连字符). 键名不带尾随 `?` 以免序列化为 JSON 字面键. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 既不阻断保存/提交/冻结也不改变任何写路径). 前端 `plan_views.cljs` 新增 `critical-path-staffing` 面板 "关键路径投入缺口" (彩色标签 关键路径任务 / 已投入 / 投入缺口 / 关键路径投入率 + 缺口任务 volcano 标签清单), `planning.cljs` 在"资源与日历"页签将其置于投入覆盖度面板之后, 负荷检查面板之前; 关键路径上无可分配任务时显空态 "关键路径上暂无可分配任务 (里程碑与汇总不计入).".

| 证据 | 实际记录 |
|---|---|
| 计划单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-planning-test'` 通过 16 tests / 169 assertions, 0 failures/errors (新增 `critical-path-staffing-is-derived-read-only`: 纯函数用例 tasks s/a/b/c/m + 分配指向 a 与 c + `critical-path` `["a" "b" "m"]` -> `available=true` / `critical-tasks` 2 (a,b; 里程碑 m 被排除; c 不在关键路径) / `staffed` 1 / `unstaffed` 1 / `staffing-pct` 50 / 缺口清单含 wbs "1.2"; 仅里程碑关键路径 `["m"]` -> `available=false` / `critical-tasks` 0 / `staffing-pct` 0 / 缺口清单空; `read-plan` 集成用例 LONG(4 工作日, 关键) + SHORT(1 工作日, 有浮动非关键) + 设备资源, 只给 SHORT 分配 4h -> `critical_path_staffing` 缺口清单 `["LONG"]` / `available=true` / `critical-tasks` 1 / `staffed` 0 / `unstaffed` 1 / `staffing-pct` 0, 且前后 `project_version` 不漂移证明纯读不落库) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 187 tests / 2086 assertions, 0 failures/errors; `read-plan` 暴露 `:critical_path_staffing` 未造成既有排程 / 关键路径 / 挣值 / 范围覆盖 / 基线偏差 / 资源超配汇总 / 投入覆盖度 / 跨项目人员隐私回归 (集成用例用设备资源, 无 `user_id`, 不触发跨项目同人归集) |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 6 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/planning.md` 的 `GET /planning` 读模型更新: 新增只读派生 `critical_path_staffing` 汇总 (available/critical-tasks/staffed/unstaffed/staffing-pct/unstaffed-tasks), 明确由纯函数 `planning.capacity/critical-path-staffing` 对关键路径叶任务与 `:allocations` 明细读取时聚合, 分母排除汇总与里程碑, 是 `allocation_coverage` 的优先级聚焦, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h05cp.spec.js` 1 passed (17.8s), 无未捕获 JS 错误: 建 LONG(4 工作日) + SHORT(1 工作日) 叶任务, 设备资源只给 SHORT 分配 4h -> 真实 HTTP GET `/planning` 回显 `critical_path_staffing` (`available=true` / `critical-tasks` 1 / `staffed` 0 / `unstaffed` 1 / `staffing-pct` 0 / 缺口清单 wbs `["LONG"]`) 且同读模型 `allocation_coverage` (`total-tasks` 2 / `with-allocations` 1 / `coverage-pct` 50) 证明两面板口径不同; "资源与日历"页签"关键路径投入缺口"面板彩色标签回显"关键路径任务 1 / 已投入 0 / 投入缺口 1 / 关键路径投入率 0%" 并列出缺口任务 "LONG 关键装配" (截图 h05cp-1-panel.png); 空态: 新项目仅里程碑 -> `available=false` / `critical-tasks=0` / `staffing-pct=0` 且面板显"关键路径上暂无可分配任务 (里程碑与汇总不计入)." (截图 h05cp-2-empty.png); 截图存 `reports/h05cp/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 缺口为读取时对既有 `tasks` / `:allocations` / `:critical_path` 快照的纯函数聚合, 不落任何新表/列); 计数与投入率口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: H05 整行保持 `partial` — 本项只是把已有投入覆盖度聚焦到"关键路径上还没排入工时"这一最高进度风险的只读洞察子能力, 与 `allocation-coverage` 的全局视角互补, 一个只读子能力不改变整行状态; 技能匹配 / 替代人员 / 请假规则 / 超配经协调解决的处置闭环仍未实现, 缺口不构成任何门控 (不阻断保存/提交/冻结), 不新增写路径/kind/命令/路由/表结构.

## H10 DQ 质量检查闭环汇总只读派生 (本轮增补, 2026-09-30)

设计与口径: DQ (设计确认) 台账此前只有逐条 DQ 的"检查通过""必需检查就绪度""版本失效""交付件作废"内联徽标, 整个项目"DQ 关键任务总体签认闭环到什么程度, 还有几条在草稿/待提交/审批中, 多少已签认, 多少依据已失效或作废"无处一眼可读. 本项在 `governance.quality` 新增只读汇总纯函数 `dq-summary`: 输入是已被 `dq-read-model` (逐条 `:dq_required_met` / `:dq_stale` 等) 与 `dq-deliverable-voided-model` (逐条 `:dq_deliverable_voided`) 富化过的 `:dqs` 向量, 按 `:status` 频次聚合输出 `available / total / approved / in-review / ready / draft / rejected / required-met / stale / voided / closure-pct` (`closure-pct` 为 `approved/total` 四舍五入整数百分比, 分母为全部 DQ 数, `total` 为 0 时给 0). `attach-dq-summary` 以整图函数在 `->` 读模型线程里 `(update :dqs ...)` 富化步骤之后 `assoc` 顶层 `:dq_summary` (下划线命名, 内层键用连字符), 不改变任何逐条 DQ 记录. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 不改变任何 DQ 状态机语义). 前端 `governance.cljs` 在"DQ与局部暂停"页签 DQ 台账之上新增 `dq-summary-section` 面板 "DQ 质量检查闭环汇总" (彩色标签 DQ 总数 / 已签认 N% / 必需项全通过 / 签认审批中 / 待提交 / 草稿 / 已退回 / 交付件失效 / 交付件作废; 计数为 0 的状态标签不渲染; 无 DQ 时空态 "暂无 DQ 关键任务, 建立 DQ 并逐项检查签认后可在此查看质量闭环概览."). 键名不带尾随 `?` 以免序列化为 JSON 字面键.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 79 tests / 1068 assertions, 0 failures/errors (新增 `dq-check-closure-summary-is-derived-read-only`: 纯函数用例对 mixed-status DQ 向量聚合四态计数与 `closure-pct`, 空向量 `available=false` / `total=0` / `closure-pct=0`; 集成用例经真实命令建 draft/ready/in_review/approved 四条 DQ 后 GET `/governance` 回显 `dq_summary` 各字段与逐条状态一致, 且 `dq_summary` 读取前后 `project_version` 不漂移证明纯读不落库) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 188 tests / 2107 assertions, 0 failures/errors; `attach-dq-summary` 挂到 `:dqs` 富化之后未造成既有 DQ 逐条徽标 / DQ 状态机 / Gate / 追踪链 / 会议行动 / 风险升级 回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/governance.md` 的 GET `/governance` 读模型更新: 新增只读汇总 `dq_summary` (available/total/approved/in-review/ready/draft/rejected/required-met/stale/voided/closure-pct), 明确由纯函数 `governance.quality/dq-summary` 对已富化 `:dqs` 读取时聚合, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-h10dq.spec.js` 1 passed (48.4s), 无未捕获 JS 错误: 界面登记四条 DQ (d1 草稿 / d2 待提交 / d3 签认审批中 / d4 已签认) -> "DQ与局部暂停"页签"DQ 质量检查闭环汇总"面板回显"DQ 总数 4 / 已签认 0% / 必需项全通过 3 / 签认审批中 2 / 待提交 1 / 草稿 1" (截图 h10dq-1-initial.png); 真实 HTTP GET `/governance` 回显 `dq_summary` 各字段一致; 独立签认人第二真实上下文对一条 in_review 作出 approved 决定 -> 面板翻转"已签认 25% (1/4)"且"签认审批中"降为 1, 逐条 DQ 状态不漂移 (截图 h10dq-2-approved.png); 空态: 新项目无 DQ -> 面板"暂无 DQ 关键任务"且 `available=false` / `total=0` / `closure-pct=0` (截图 h10dq-3-empty.png); 截图存 `reports/h10dq/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有已富化 `:dqs` 的纯函数聚合, 不落任何新表/列); 四态计数与签认率口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: H10 整行保持 `partial` — 本项只是把已有逐条 DQ 徽标升为一个项目级签认闭环健康度的只读汇总子能力, 与"检查通过""必需检查就绪度""版本失效""交付件作废"内联列互补, 一个只读子能力不改变整行状态; 全项目质量计划 (一份覆盖全部关键任务的计划实体) 与企业适用模板库仍未实现, 汇总不构成任何门控 (不阻断 DQ 登记/检查/提交/签认), 不新增写路径/kind/命令/路由/表结构.

## B09 关口验收签核闭环汇总只读派生 (本轮增补, 2026-09-30)

设计与口径: 关口 (Gate) 台账此前只有逐条关口实例的"检查就绪度"内联徽标 (`gate-read-model` 的 `ready_to_sign` / `blocking_checks` 与 `gate-evidence-voided-model` / `gate-evidence-release-model` 的"证据已作废""证据待发布"两色标), 模板层另有 `gate-progress` 按模板下钻实例进展, 但整个项目"到底多少关口实例已签核闭环, 多少还在评审/待提交/草稿, 多少仍被必需检查阻断, 多少证据待发布或已作废"这一关口实例层的健康度此前无从一眼可读. 本项在 `governance.gates` 新增只读汇总纯函数 `gate-closure-summary`: 输入是已被 `gate-read-model` / `gate-evidence-voided-model` / `gate-evidence-release-model` 逐条富化过的 `:gates` 向量, 按 `:status` 频次聚合输出 `available / total / approved / waived / in-review / ready / draft / rejected / signed / blocked / evidence-voided / evidence-pending / closure-pct` (`signed` = `approved + waived` 与 `gate-progress` 的 `#{"approved" "waived"}` 口径一致, `blocked` 为 `ready_to_sign` 为假者数, `evidence-pending`/`evidence-voided` 分别计 `gate_evidence_unreleased`/`gate_evidence_voided` 为真者数, `closure-pct` 为 `signed/total` 四舍五入整数百分比, `total` 为 0 时给 0). `attach-gate-closure-summary` 以整图函数在 `->` 读模型线程里三条 `(update :gates ...)` 富化步骤之后 `assoc` 顶层 `:gate_closure` (下划线命名, 内层键用连字符), 不改变任何逐条关口记录. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 不改变任何关口状态机语义, 关口 `submit!`/`decide!`/`evidence-ready!` 的硬校验不受影响). 前端 `governance.cljs` 在"Gate评审"页签模板层"Gate进展汇总"之下新增 `gate-closure-summary-section` 面板 "关口验收签核闭环汇总" (彩色标签 关口总数 / 已签核 N% (signed/total · 批准 A 豁免 W) / 签核评审中 / 待提交 / 草稿 / 已驳回 / 被必需检查阻断 / 证据待发布 / 证据已作废; 计数为 0 的状态标签不渲染; 无关口时空态 "尚无关口实例, 发起 Gate 检查并逐项签核后可在此查看验收闭环概览."). 键名不带尾随 `?` 以免序列化为 JSON 字面键.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 80 tests / 1092 assertions, 0 failures/errors (新增 `gate-closure-summary-is-derived-read-only`: 纯函数用例对空向量给 `available=false` / `total=0` / `closure-pct=0` / `blocked=0`, 对五条 mixed-status 关口向量聚合 `signed=2` / `closure-pct=40` / `blocked=3` / `evidence-pending=1` / `evidence-voided=1`; 集成用例经真实命令建一个含单个必需检查项的关口模板并派生 draft/ready/in_review(绑未发布证据)/in_review(绑已发布证据) 四条实例后 GET `/governance` 回显 `gate_closure` total=4/approved=1/in-review=1/ready=1/draft=1/signed=1/closure-pct=25/blocked=1/evidence-pending=2/evidence-voided=0 与逐条状态一致, 且读取前后 `project_version` 不漂移证明纯读不落库) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 189 tests / 2131 assertions, 0 failures/errors; `attach-gate-closure-summary` 挂到 `:gates` 三条富化之后未造成既有"检查就绪度"内联列 / Gate 状态机 / 证据作废与待发布标注 / DQ / 追踪链 / 会议行动 / 风险升级 回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/governance.md` 的 GET `/governance` 读模型更新: 新增只读汇总 `gate_closure` (available/total/approved/waived/in-review/ready/draft/rejected/signed/blocked/evidence-voided/evidence-pending/closure-pct), 明确由纯函数 `governance.gates/gate-closure-summary` 对已富化 `:gates` 读取时聚合, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-b09gcs.spec.js` 1 passed (48.3s), 无未捕获 JS 错误: 界面建未发布与已发布两份交付件并由独立签发人真实 HTTP 发布其一 -> 建一个含 1 必需检查项的关口模板派生 4 个实例推进 draft/ready(绑未发布证据)/in_review(绑未发布证据)/in_review(绑已发布证据) -> "Gate评审"页签"关口验收签核闭环汇总"面板回显"关口总数 4 / 已签核 0% / 签核评审中 2 / 待提交 1 / 草稿 1 / 被必需检查阻断 1 / 证据待发布 2 / 证据已作废 0" (截图 b09gcs-1-initial.png); 真实 HTTP GET `/governance` 回显 `gate_closure` 各字段一致; 独立签核人第二真实上下文对绑已发布证据者作出 approved 决定 -> 面板翻转"已签核 25% (1/4 · 批准 1)"且"签核评审中"降为 1 而"证据待发布"仍 2, 逐条关口状态 (g1 draft/g2 ready/g3 in_review/g4 approved 且 decided_by 为独立签核人) 不漂移 (截图 b09gcs-2-approved.png); 空态: 新项目无关口 -> 面板"尚无关口实例"且 `available=false` / `total=0` / `closure-pct=0` (截图 b09gcs-3-empty.png); 截图存 `reports/b09gcs/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有已富化 `:gates` 的纯函数聚合, 不落任何新表/列); 六态计数, 已签核/阻断/待发布/作废聚合与闭环率口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: B09 整行保持 `implemented / local` 与"待 主机交付清单业务口径"不变 — 本项只是把已有逐条关口徽标与模板层 `gate-progress` 升为一个关口实例层的项目级签核闭环健康度只读汇总子能力, 与逐条"检查就绪度""证据待发布""证据已作废"内联列互补, 一个只读子能力不改变整行状态; 是否据"证据待发布/被必需检查阻断"进一步阻断关口批准属"待 主机交付清单业务口径", 汇总不构成任何门控 (不阻断关口登记/检查/提交/签核), 不新增写路径/kind/命令/路由/表结构.

## C09 问题闭环率与严重度分布只读派生汇总 (本轮增补, 2026-09-30)

设计与口径: 问题 (issue) 台账此前只有逐条"逾期"只读预警徽标 (`collaboration/issue-read-model` 派生 `issue_overdue`) 与风险侧 H08/H09 升级门控, "风险与问题"页签另有"风险升级处置汇总"面板, 但整个项目"到底多少问题已闭环, 多少还待处理/验证中/已驳回, 其中几条逾期, 几条是未关闭的阻断级, 各严重度如何分布"这一问题处理生命周期健康度此前无从一眼可读. 本项在 `governance.collaboration` 新增只读汇总纯函数 `issue-closure-summary`: 输入是 `(:issues data)` 全量问题, 复用 `store/latest` 以每个业务编码 `code` (缺省取自身 `id`) 的最新有效修订版为统计单位, 按 `:status` 与逐条已富化的 `:issue_overdue`/`:severity` 聚合输出 `available / total / closed / open / pending / in-review / rejected / overdue / blocker-open / by-severity / closure-pct` (`closed` 为状态属 `closed` 者数, `open` 为 `total - closed`, `pending`/`in-review`/`rejected` 按 `open`/`in_review`/`rejected` 计数, `overdue` 复用既有逐条 `issue_overdue` 为真者数, `blocker-open` 为 `severity = "blocker"` 且状态非 `closed` 者数, `by-severity` 为固定顺序阻断/严重/一般三元向量按最新有效版本全量各计一次, `closure-pct` 为 `closed/total` 四舍五入整数百分比, `total` 为 0 时给 0). `attach-issue-closure-summary` 以整map函数在 `->` 读模型线程里风险/问题逐条富化步骤之后 `assoc` 顶层 `:issue_closure` (下划线命名, 内层键用连字符), 不改变任何逐条问题记录. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 问题 `resolve!`/`verify!`/`decision` 的硬校验与独立验证人门控不受影响). 前端 `governance.cljs` 在"风险与问题"页签"风险升级处置汇总"之下新增 `issue-closure-summary-section` 面板 "问题闭环与严重度分布汇总" (彩色标签 问题总数 / 已闭环 N% (closed/total) / 待处理 / 验证中 / 已驳回 / 逾期未关闭 / 未关闭阻断级, 另以"按严重度分布:"一行 geekblue 阻断·严重·一般 三色标签呈现全量分布; 计数为 0 的状态标签不渲染; 无问题时空态 "暂无项目问题, 登记后可在此查看闭环率与严重度分布."). 键名不带尾随 `?` 以免序列化为 JSON 字面键.

| 证据 | 实际记录 |
|---|---|
| 治理单命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-governance-test'` 通过 81 tests / 1113 assertions, 0 failures/errors (新增 `issue-closure-summary-is-derived-read-only`: 集成用例经真实命令建六条问题覆盖 open / 逾期 open / in_review / closed / rejected / blocker, 其中 closed/in_review/rejected 走真实 resolve + 独立审批人 decision 推进后 GET `/governance` 回显 `issue_closure` total=6/closed=1/open=5/pending=3/in-review=1/rejected=1/overdue=1/blocker-open=1/closure-pct=17/by-severity=[阻断1,严重2,一般3] 与逐条状态一致, 读取前后 `project_version` 不漂移证明纯读不落库; 纯函数直测对空向量给 `available=false` / `total=0` / `closure-pct=0`, 对三条同 `code` 带 `revision` 的映射验证 `store/latest` 去重后 total=2/closed=1/overdue=1/closure-pct=50) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 190 tests / 2152 assertions, 0 failures/errors; `attach-issue-closure-summary` 挂到风险/问题逐条富化之后未造成既有"风险升级处置汇总""会议行动闭环率""DQ 质量检查闭环汇总""关口验收签核闭环汇总"汇总面板 / 问题状态机 / 逾期与阻断级预警 / 责任负载 / 追踪链 回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/governance.md` 的 GET `/governance` 读模型更新: 新增只读汇总 `issue_closure` (available/total/closed/open/pending/in-review/rejected/overdue/blocker-open/by-severity/closure-pct), 明确由纯函数 `governance.collaboration/issue-closure-summary` 对全量 `:issues` 读取时聚合, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-c09ic.spec.js` 1 passed (50.1s), 无未捕获 JS 错误: 界面"风险与问题"页签登记六条问题 (含一条逾期与一条阻断级), 其中 in_review/closed/rejected 经真实 HTTP resolve + 独立验证人第二真实上下文 decision 推进 -> "问题闭环与严重度分布汇总"面板回显"问题总数 6 / 已闭环 17% (1/6) / 待处理 3 / 验证中 1 / 已驳回 1 / 逾期未关闭 1 / 未关闭阻断级 1"及"按严重度分布: 阻断 · 1 严重 · 2 一般 · 3" (截图 c09ic-1-initial-open.png / c09ic-2-mixed-closure.png); 真实 HTTP GET `/governance` 回显 `issue_closure` 各字段一致且逐条问题状态不漂移 (closed 者 decided_by 为独立验证人), `ws.project_version` 与基线同源; 空态: 新项目无问题 -> 面板"暂无项目问题"且 `available=false` / `total=0` / `closure-pct=0` (截图 c09ic-3-empty.png); 截图存 `reports/c09ic/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有全量 `:issues` 的纯函数聚合, 不落任何新表/列); 六态计数, 逾期/阻断级/严重度分布聚合与闭环率口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: C09 整行保持既有状态不变 — 本项只是把已有逐条问题逾期徽标与"风险升级处置汇总"面板升为一个项目级问题处理闭环健康度与严重度分布的只读汇总子能力, 一个只读子能力不改变整行状态; 汇总不构成任何门控 (闭环率高低与未关闭阻断级数不阻止任何问题登记/提交/验证/驳回), 不新增写路径/kind/命令/路由/表结构; 逾期与阻断级提醒的外部投递仍属 C11.

## F04 工时审核闭环汇总只读派生 (本轮增补, 2026-09-30)

设计与口径: 研发工时 (F04) 此前已有逐张提交, 独立审核 (`review!`), 批准后更正链 (`correct!` 使原单转 `corrected` 并生成独立待审更正单, 驳回恢复原单) 与期间封期门控, 但"整个项目到底有多少工时单待审, 多少已批准/驳回/更正, 有几张更正还压着待审, 审核闭环推进到什么程度, 各状态分别占多少小时"这一工时审核生命周期健康度此前无从一眼可读. 本项在 `domain.pms.finance-time` 新增只读汇总纯函数 `timesheet-review-summary`: 输入整项目 `:time_entries` (工时单无修订链, 故统计全部行不取 `store/latest`, 与问题/风险类"取最新修订"不同), 按互斥状态 `submitted`/`approved`/`rejected`/`corrected` 计数, 另算 `processed` (= `approved + rejected`, 已作出终局决定的单数), `correction-pending` (带 `corrects_entry_id` 且 `status = "submitted"` 的更正待审单数), `review-pct` (= `processed / total` 四舍五入整数百分比, `total` 为 0 时给 0), 及三档小时分布 `hours-total`/`hours-approved`/`hours-pending` (分别对全部, 已批准, 待审核行的整数分钟求和后 `money/hours` 归一为两位小数字符串, 与状态计数正交可各自独立解读). `attach-timesheet-review-summary` 以整map函数把结果 `assoc` 到财务概览读模型顶层 `:timesheet_review` (下划线命名, 内层键连字符, 不带尾随 `?`), `finance/overview` 在合成成本/工时/分摊数据后追加该纯读步骤, 不改变任何逐条工时单. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 提交/审核/更正/封期的硬校验与跨项目 24 小时日容量约束均不受影响). 前端 `finance.cljs` 在项目费用页"实际工时"页签顶部 `time-section` 之上新增 `timesheet-review-section` 面板 "工时审核闭环汇总" (彩色标签 待审核 blue / 已批准 green / 已驳回 red / 已更正 default / 更正待审 orange 仅 `pos?` 时渲染, 另以一行显示"共 N 张工时单, 已作决定 M 张, 审核完成率 P%"与一行显示"工时: 合计 Th / 已批准 Ah / 待审核 Ph"); 无工时单时空态提示"尚无人提交工时单...". 该面板与四算, 承诺预算控制, 封期读模型 (`:locked_periods`) 正交.

| 证据 | 实际记录 |
|---|---|
| 组合/财务命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-portfolio-test'` 通过 8 tests / 126 assertions, 0 failures/errors (新增 `timesheet-review-summary-is-derived-read-only`: 纯函数直测对五张合成工时单 (approved 120m / submitted 180m / rejected 60m / corrected 240m / submitted 90m 且带 corrects_entry_id) 断言 available=true, total=5, approved=1, submitted=2, rejected=1, corrected=1, processed=2, correction-pending=1, review-pct=40, hours-total "11.50" / hours-approved "2.00" / hours-pending "4.50"; attach 后 `:time_entries` 逐条不漂移且 `:timesheet_review total`=5; 空向量给 available=false/total=0/review-pct=0/hours-total "0.00"; 端到端在真实执行态项目上经提交 2h+3h -> 独立批准 1 -> 更正已批准单, GET `/finance` 回显 `timesheet_review` 从 total2/review-pct50 演进到 total3/corrected1/correction-pending1/review-pct0) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 191 tests / 2192 assertions, 0 failures/errors; `attach-timesheet-review-summary` 挂到 `finance/overview` 合并之后未造成既有工时更正与封期 / 四算拉通 / 跨项目研发费用池 / 承诺预算控制 / 追踪与治理各只读汇总面板回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 compiled (缓存) / 0 warnings |
| HTTP 合同 (契约) | `contracts/finance-closure.md` 的工时节更新: GET `/finance` 读模型新增顶层只读派生键 `timesheet_review` (available/total/submitted/approved/rejected/corrected/processed/correction-pending/review-pct/hours-total/hours-approved/hours-pending), 明确由纯函数 `finance_time/timesheet-review-summary` 对全部工时单读取时聚合 (工时无修订链不取 latest), 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-f04tr.spec.js` 1 passed (28.9s), 无未捕获 JS 错误: 主操作者经真实命令把项目推进到执行态后, 界面"项目费用 -> 实际工时"页签先显示空态"尚无人提交工时单" (截图 f04tr-1-empty.png); 提交两张待审工时单 (2h + 3h) -> 面板回显"待审核 2 / 共 2 张工时单, 已作决定 0 张, 审核完成率 0% / 工时: 合计 5.00h / 已批准 0.00h / 待审核 5.00h" (截图 f04tr-2-pending.png); 独立审核人 (第二真实浏览器上下文) 批准 e1 驳回 e2, 填报人再提交 e3 (4h) 并批准后对其发起更正 (5h) -> 面板翻转为"待审核 1 / 已批准 1 / 已驳回 1 / 已更正 1 / 更正待审 1 / 共 4 张工时单, 已作决定 2 张, 审核完成率 50% / 工时: 合计 14.00h / 已批准 2.00h / 待审核 5.00h" (截图 f04tr-3-mixed.png); 三态均另用真实 HTTP GET `/finance` 核验 `timesheet_review` 各键与界面同源; 截图存 `reports/f04tr/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有全量工时单的纯函数聚合, 不落任何新表/列); 五态计数, 更正待审判定, 三档小时分布与审核完成率口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: F04 整行保持既有 `implemented / local` 状态不变 — 本项只是把已有的逐张工时审核与更正链升为一个项目级工时审核闭环健康度与小时分布的只读汇总子能力, 一个只读子能力不上行整行状态; 汇总不构成任何门控 (审核完成率高低与更正待审数不阻止任何工时提交/审核/更正/封期), 不新增写路径/kind/命令/路由/表结构; 工时批准后的追溯更正与封期门控此前已具备, 权威工时口径与外部考勤/ERP 集成仍待合同.

## F06 四算版本审批闭环汇总只读派生 (本轮增补, 2026-09-30)

设计与口径: 四算成本版本 (F06) 此前已有逐版新建 (`create!` 草稿), 明细维护 (`add-entry!`), 提交冻结快照 (`submit!`), 独立批准/驳回 (`review!` 需 `finance:approve` 且非提交者), 修订复制新草稿 (`revise!`), 受控取消 (`cancel!` 仅 `draft`/`rejected`) 与期间封期门控, 以及四算拉通只读对比 (`four-count-comparison`), 但"整个项目到底有多少费用版本还压在草稿/审批中, 多少已批准/驳回/取消, 概算-预算-核算-决算各口径分别推进到什么程度, 独立审批闭环完成率多少"这一四算审批生命周期健康度此前无从一眼可读. 本项在 `domain.pms.finance` 新增只读汇总纯函数 `cost-review-summary`: 输入整项目 `:cost_versions` (版本修订另建新 id 而非原地改, 故统计全部版本行不取 `store/latest`), 按互斥状态 `draft`/`submitted`/`approved`/`rejected`/`cancelled` 计数 (状态互斥故每个版本恰落入一个桶), 另算 `processed` (= `approved + rejected`, 已作出终局决定的版本数), `pending` (= `draft + submitted`, 尚未终局的版本数), `review-pct` (= `processed / total` 四舍五入整数百分比, `total` 为 0 时给 0), 以及 `by-kind` 向量按 `estimate`/`budget`/`actual`/`settlement` 四口径各给出 `total`/`approved`/`pending` 分布 (与整体状态计数正交, 可各自独立解读某一口径审批推进度). `attach-cost-review-summary` 以整map函数把结果 `assoc` 到财务概览读模型顶层 `:cost_review` (下划线命名, 内层键连字符, 不带尾随 `?`), `finance/overview` 在 `->` 线程末尾紧随 `time/attach-timesheet-review-summary` 之后追加该纯读步骤, 不改变任何逐条费用版本. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 提交/审核/修订/取消的硬校验与封期门控均不受影响). 前端 `finance.cljs` 在项目费用页"成本与分摊"页签顶部 `four-count-section` 之上新增 `cost-review-section` 面板 "四算版本审批闭环汇总" (彩色标签 版本总数 geekblue / 已批准 green / 审批中 blue / 草稿 default / 已驳回 red / 已取消 orange 仅 `pos?` 时渲染, 另以一行显示"共 N 个四算版本, 已作决定 P 个, 待处理 Q 个, 审批完成率 R%"与一行按口径显示"概算·总x 批准y 待z"等标签); 无费用版本时空态提示"尚无四算费用版本...". 该面板与四算拉通, 工时审核闭环汇总 (`:timesheet_review`), 承诺预算控制, 封期读模型 (`:locked_periods`) 正交.

| 证据 | 实际记录 |
|---|---|
| 组合/财务命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-portfolio-test'` 通过 9 tests / 162 assertions, 0 failures/errors (新增 `cost-review-summary-is-derived-read-only`: 纯函数直测对六张合成费用版本 (estimate approved / estimate draft / budget submitted / budget rejected / actual cancelled / settlement draft) 断言 available=true, total=6, draft=2, submitted=1, approved=1, rejected=1, cancelled=1, processed=2, pending=3, review-pct=33, by-kind 四口径分别 estimate{2,1,1} budget{2,0,1} actual{1,0,0} settlement{1,0,1}; attach 后 `:cost_versions` 逐条不漂移且 `:cost_review total`=6; 空向量给 available=false/total=0/review-pct=0 且 by-kind 各口径 total 均为 0; 端到端在真实执行态项目上建 5 版 (概算提交->独立批准, 预算提交->独立驳回, 核算提交后停留审批中, 决算仅草稿, 概算草稿后取消), GET `/finance` 回显 `cost_review` total5/approved1/rejected1/submitted1/draft1/cancelled1/processed2/pending2/review-pct40 且概算口径 by-kind{2,1,0}, 逐条版本状态不漂移) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 192 tests / 2228 assertions, 0 failures/errors; `attach-cost-review-summary` 挂到 `finance/overview` 线程末尾未造成既有工时审核闭环汇总 / 工时更正与封期 / 四算拉通 / 跨项目研发费用池 / 承诺预算控制 / 追踪与治理各只读汇总面板回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 5 compiled / 0 warnings |
| HTTP 合同 (契约) | `contracts/finance-closure.md` 的四算节更新: GET `/finance` 读模型新增顶层只读派生键 `cost_review` (available/total/draft/submitted/approved/rejected/cancelled/processed/pending/review-pct/by-kind), 明确由纯函数 `finance/cost-review-summary` 对全部费用版本读取时聚合 (版本修订另建新 id 故不取 latest), 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-f06cr.spec.js` 1 passed (45.7s), 无未捕获 JS 错误: 主操作者经真实命令把项目推进到执行态后, 界面"项目费用 -> 成本与分摊"页签先显示空态"尚无四算费用版本" (截图 f06cr-1-empty.png); 建三份不同口径草稿 -> 面板回显"版本总数 3 / 草稿 3 / 共 3 个四算版本, 已作决定 0 个, 待处理 3 个, 审批完成率 0%" (截图 f06cr-2-draft.png); 推进为混合生命周期 (概算提交后独立批准, 预算提交后独立驳回, 核算提交后停留审批中, 决算仅草稿, 概算草稿后取消) -> 面板翻转为"版本总数 5 / 已批准 1 / 审批中 1 / 草稿 1 / 已驳回 1 / 已取消 1 / 共 5 个四算版本, 已作决定 2 个, 待处理 2 个, 审批完成率 40% / 概算·总2 批准1 待0 / 决算·总1 批准0 待1" (截图 f06cr-3-mixed.png); 三态均另用真实 HTTP GET `/finance` 核验 `cost_review` 各键与 `cost_versions` 逐条状态与界面同源; 截图存 `reports/f06cr/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有全量费用版本的纯函数聚合, 不落任何新表/列); 五态计数, 已作决定/待处理口径, 审批完成率与四口径 by-kind 分布由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: F06 整行保持既有 `partial / 待规则` 状态不变 — 本项只是把已有的逐版四算独立审批生命周期升为一个项目级四算审批闭环健康度与四口径分布的只读汇总子能力, 一个只读子能力不上行整行状态; 汇总不构成任何门控 (审批完成率高低不阻止任何版本提交/审核/修订/取消), 不新增写路径/kind/命令/路由/表结构; 权威收入/累计回款/开票封账口径与 ERP/财务对账集成仍待合同.

## F05 研发费用分摊闭环汇总只读派生 (本轮增补, 2026-10-01)

设计与口径: 研发费用分摊 (F05) 此前已有逐批次新建 (`allocate!` 对已批准成本版本在指定期间内按任务分钟权重用最大余数法精确分摊), 输入工时/金额/版本/输出结果与 SHA256 持久化, 幂等键同内容返回旧结果不同内容 409, 无批准工时 409, 且分摊恒总额守恒 (`distribute` 最大余数法把差额确定地分给最高余数任务), 但"整个项目到底跑了多少笔费用分摊, 冻结的费用池总额与实际摊出的总额是否一致, 一共生成了多少条人工成本条目, 覆盖了多少个任务, 有几个任务因舍入只摊到 0 金额 (未生成成本条目)"这一分摊闭环健康度此前无从一眼可读. 本项在 `domain.pms.finance` 新增只读汇总纯函数 `allocation-review-summary`: 输入整项目 `:allocations` (每条为 `allocation/dto` 后的批次, 含批次级 `:amount_minor` 冻结池金额与 `:rows` 逐任务分摊行), 逐批次累加得 `pool-minor` (冻结费用池合计) 与 `allocated-minor` (各行摊出合计), 派生 `available` (是否有分摊批次), `total` (批次数), `pool-amount`/`allocated-amount` (`money/money` 两位小数规范化字符串), `entry-count` (`amount_minor` 为正的行数, 即真正生成了人工成本条目的任务数), `zero-task-count` (`amount_minor` 为 0 被舍入到无成本条目的任务数), `task-count` (`:task_id` 去重覆盖任务数), `conserved` (= `pool-minor == allocated-minor`, 因 `distribute` 恒守恒故不变为 true). `attach-allocation-review-summary` 以整map函数把结果 `assoc` 到财务概览读模型顶层 `:allocation_review` (下划线命名, 内层键连字符, 不带尾随 `?`), `finance/overview` 在 `->` 线程末尾紧随 `attach-cost-review-summary` 之后追加该纯读步骤, 不改变任何逐条分摊批次. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 分摊的幂等/冻结版本/批准工时/封期硬校验均不受影响). 前端 `finance.cljs` 在项目费用页"成本与分摊"页签把 `[allocation-review-section context]` 挂在 `cost-section`/`ledger-section` 之后, 新增面板 "研发费用分摊闭环汇总" (彩色标签 分摊批次 geekblue / 覆盖任务 blue / 生成成本条目 green / 总额守恒 green·不守恒 red 仅 `pos?` 时渲染, 另以一行显示"冻结费用池合计 X 元, 实际摊出 Y 元"与"生成 N 条人工成本, 覆盖 M 个任务, 舍入未摊到 Z 个"等); 无分摊批次时空态提示"尚无费用分摊批次...". 该面板与成本台账, 分摊历史, 四算拉通, 四算审批闭环汇总 (`:cost_review`), 工时审核闭环汇总 (`:timesheet_review`) 正交.

| 证据 | 实际记录 |
|---|---|
| 组合/财务命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-portfolio-test'` 通过 10 tests / 190 assertions, 0 failures/errors (新增 `allocation-review-summary-is-derived-read-only`: 纯函数直测对两张合成批次 (批次一冻结池 10000 分摊 t1 6000/t2 4000, 批次二冻结池 5000 分摊 t3 5000/t4 0) 断言 available=true, total=2, pool-amount="150.00", allocated-amount="150.00", entry-count=3, zero-task-count=1, task-count=4, conserved=true; attach 后 `:allocations` 逐条不漂移且 `:allocation_review total`=2/pool-amount="150.00"; 空批次给 available=false/total=0/pool-amount="0.00"/allocated-amount="0.00"/entry-count=0/task-count=0 且 conserved=true; 端到端在真实执行态项目提交并独立批准 6h 工时后对核算草稿版本分摊 1000.00 元, GET `/finance` 概览 `allocation_review` available=true/total=1/pool-amount=allocated-amount="1000.00"/conserved=true/entry-count=1/task-count=1/zero-task-count=0, 未分摊前 available=false) |
| 全量 PMS 回归 (CLI SQLite) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms.*-test'` 通过 193 tests / 2256 assertions, 0 failures/errors; `attach-allocation-review-summary` 挂到 `finance/overview` 线程末尾未造成既有四算审批闭环汇总 / 工时审核闭环汇总 / 跨项目研发费用池分摊与守恒 / 承诺预算控制 / 封期读模型 / 追踪与治理各只读汇总面板回归 |
| 前端编译 | `npx shadow-cljs compile app` 4035 files / 0 warnings (无未编译改动残留) |
| HTTP 合同 (契约) | `contracts/finance-closure.md` 的费用分摊节更新: GET `/finance` 读模型新增顶层只读派生键 `allocation_review` (available/total/pool-amount/allocated-amount/entry-count/zero-task-count/task-count/conserved), 明确由纯函数 `finance/allocation-review-summary` 对全部分摊批次读取时聚合, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-f05ac.spec.js` 1 passed (47.9s), 无未捕获 JS 错误: 主操作者经真实命令把项目推进到执行态并建第二任务, 界面"项目费用 -> 成本与分摊"页签先显示空态"尚无费用分摊批次" (截图 f05ac-1-empty.png); 任务一批准 6h 后建核算版本并分摊 1000 元 -> 面板回显"分摊批次 1 / 覆盖任务 1 / 生成成本条目 1 / 总额守恒 / 冻结费用池合计 1000.00 元, 实际摊出 1000.00 元" (截图 f05ac-2-one-panel.png); 任务二批准 4h 后建第二版本并分摊 800 元 (跨两任务) -> 面板翻转为"分摊批次 2 / 覆盖任务 2 / 生成成本条目 3 / 冻结费用池合计 1800.00 元, 实际摊出 1800.00 元" (截图 f05ac-3-two-panel.png); 三态均另用真实 HTTP GET `/finance` 核验 `allocation_review` 各键与逐条批次不漂移且与界面同源; 截图存 `reports/f05ac/` |

本轮未执行 (如实记录): MySQL 迁移与回归 (本地无可用实例, 本轮完全免迁移不新增 DDL, 汇总为读取时对既有的全部分摊批次 (含逐任务分摊行) 的纯函数聚合, 不落任何新表/列); 批次数, 冻结池/摊出金额, 生成条目数, 覆盖任务数, 舍入零头与守恒口径由后端 SQLite 用例确定性覆盖, 浏览器侧核验界面可见标签与服务端回显同源.

边界: F05 整行保持既有 `partial / 待规则` 状态不变 — 本项只是把已有的逐批次费用池按批准工时最大余数法分摊升为一个项目级分摊闭环健康度与守恒/覆盖/零头可见性的只读汇总子能力, 一个只读子能力不上行整行状态; 汇总不构成任何门控 (守恒/覆盖情况不阻止任何分摊/幂等/版本操作), 不新增写路径/kind/命令/路由/表结构; 跨项目共享研发池的人工费率有效期核算与外部 ERP/成本系统对账口径仍待合同.

## F08 成本毛利看板只读派生 (本轮增补, 2026-10-01)

设计与口径: 成本/毛利/净利看板 (F08) 此前已有逐条费用版本的 `margin` 金额字段 (收入减成本的毛利金额, 零收入也能算出负毛利), 以及四算拉通只读对比 (`four-count-comparison`) 与四算审批闭环汇总 (`cost_review`), 但"概算-预算-核算-决算各口径的最新已批准版本到底毛利是多少, 毛利率 (百分比) 是多少, 零或负收入时不该给出伪利润率"这一毛利健康度此前无从一眼可读, 且既有 `margin` 只给金额不给比率、对零收入会给出误导性的负毛利而不加标注. 本项在 `domain.pms.finance` 新增只读派生纯函数 `margin-summary`: 输入整项目 `:cost_versions`, 先过滤 `status=approved`, 对 `estimate`/`budget`/`actual`/`settlement` 四口径各取 `version_no` 最大的最新已批准版本 (版本修订另建新 id 故按 `version_no` 排序取首), 逐口径输出 `present` (是否有最新已批准版本), 有则回显 `version_no`/`period`/`currency`/`name` 与规范化金额 `revenue`/`cost`/`margin` (沿用逐条版本既有金额口径, 不重算), 附 `computable` (= `revenue_minor > 0`, 收入为正才可计算利润率) 与 `margin-pct` (= `round(100 × margin / revenue)` 整数百分比, `computable=false` 时为 `nil`); 顶层给 `available` (是否有任一已批准版本) 与 `comparable` (已批准各口径币种去重后是否唯一, 不一致时前端提示不可横向比较). 关键区别于既有 `margin` 金额字段: 补齐"毛利率百分比"并对零或负收入显式标注 `computable=false`/`margin-pct=null`, 不以伪利润率或伪零误导. `attach-margin-summary` 以整map函数把结果 `assoc` 到财务概览读模型顶层 `:cost_margin` (下划线命名, 内层键连字符, 不带尾随 `?`), `finance/overview` 在 `->` 线程末尾紧随 `attach-allocation-review-summary` 之后追加该纯读步骤, 不改变任何逐条费用版本. 免迁移 / 免新 kind / 免新命令 / 免新路由 / 不构成任何门控 (只读呈现, 提交/审核/修订/取消的硬校验与封期门控均不受影响). 前端 `finance.cljs` 在项目费用页"成本与分摊"页签把 `[cost-margin-section context]` 挂在 `cost-review-section` 与 `four-count-section` 之间, 新增面板 "成本毛利看板" (各口径一行"收入/成本/毛利" + `毛利率 N%` green/red 徽标, 零或负收入 orange "不可算 (零或负收入)" 徽标, 无已批准版本口径灰显"尚无已批准版本", 币种不一致时 red 徽标提示); 无任一已批准版本时整体空态提示"尚无已批准的四算版本...". 该面板与逐条 `margin`, 四算拉通, 四算审批闭环汇总 (`:cost_review`), 分摊闭环汇总 (`:allocation_review`), 工时审核闭环汇总 (`:timesheet_review`), 封期读模型 (`:locked_periods`) 正交.

| 证据类型 | 结果 |
|---|---|
| 组合/财务命名空间 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.pms-portfolio-test'` 通过 11 tests / 221 assertions, 0 failures/errors (新增 `margin-summary-is-derived-read-only`: 纯函数直测对合成费用版本 (两版概算均批准取 v2 margin-pct 20, 预算 v1 pct 25, 核算 revenue_minor 0 → present=true/computable=false/margin-pct nil, 决算 status submitted → present=false) 断言各口径取值; attach 后 `:cost_versions` 逐条不漂移且 `[:cost_margin :by-kind 0]` 与纯函数一致; 空版本向量给 available=false/comparable=true/by-kind 四口径均 present=false; 混币种给 comparable=false; 端到端在真实执行态项目建概算 (收入 1000, material 600 → 毛利 400.00 pct 40) 与决算 (收入 0, labor 200 → present=true/computable=false/margin-pct nil) 各自独立批准后 GET `/finance` 回显 `cost_margin` 匹配且预算/核算 present=false) |
| 全量 PMS 回归 (冷 JVM) | `clojure -M:test -d test/clj -r 'com\.ruoyi\.'` 全绿 194 tests / 2287 assertions, 0 failures/errors (较 F05 基线 193/2256 增 1 test / 31 assertions, 全部来自本 `margin-summary` deftest) |
| 前端编译 | `npx shadow-cljs compile app` 通过 4035 files / 0 warnings |
| HTTP 合同 (契约) | `contracts/finance-closure.md` 的费用分摊节后新增: GET `/finance` 读模型新增顶层只读派生键 `cost_margin` (available/comparable/by-kind[present/version_no/period/currency/name/revenue/cost/margin/computable/margin-pct]), 明确由纯函数 `finance/margin-summary` 对各口径最新已批准版本读取时派生, 零或负收入 `computable=false`/`margin-pct=null`, 免迁移/免新命令/不构成门控 |
| 浏览器 E2E (隔离 `:3100` 独立空库) | `pms-f08mb.spec.js` 1 passed (55.0s), 无未捕获 JS 错误: 主操作者经真实命令把项目推进到执行态后, 界面"项目费用 -> 成本与分摊"页签先显示空态"尚无已批准的四算版本" (截图 f08mb-1-empty.png / f08mb-1-empty-panel.png); 概算 (收入 1000 成本 600) 独立批准后 -> 面板回显"概算 (v1) 收入 1000.00 成本 600.00 毛利 400.00" + "毛利率 40%" green 徽标, 预算/核算灰显"尚无已批准版本" (截图 f08mb-2-estimate.png / f08mb-2-estimate-panel.png); 决算 (收入 0 成本 200) 独立批准后 -> 面板追加"决算 (v1) 收入 0.00 成本 200.00 毛利 -200.00" + orange "不可算 (零或负收入)" 徽标 (截图 f08mb-3-mixed.png / f08mb-3-mixed-panel.png); 三态均另用真实 HTTP GET `/finance` 核验 `cost_margin` 各键 (estimate computable=true/margin-pct=40, settlement computable=false/margin-pct=null, comparable=true) 与 `cost_versions` 逐条状态 (均 approved) 不漂移且与界面同源; 截图存 `reports/f08mb/` |
| MySQL | 本轮未执行 (本地无 MySQL 实例); 本项为纯读模型派生, 不涉及任何迁移或表结构变更, 与 SQLite/MySQL 无关 |

边界: F08 整行保持既有 `partial / 待规则` 状态不变 — 本项只是把已有的逐条 `margin` 金额字段升为一个跨四口径最新已批准版本的毛利金额 + 毛利率百分比 + 零收入不可算标注的项目级只读看板子能力, 一个只读子能力不上行整行状态; 看板不构成任何门控 (毛利率高低不阻止任何版本提交/审核/修订/取消), 不新增写路径/kind/命令/路由/表结构; 税额/收入确认/汇率换算/税后净利公式/跨产品线完整经营分析/汇总下钻权限验收仍待合同, 本看板仅同币种毛利金额与毛利率, 不做隐式换汇或净利推算.

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
