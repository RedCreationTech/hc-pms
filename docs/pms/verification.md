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

## H18 需求/文档/干系人受控软作废 (本轮增补, 2026-09-23)

设计与关闭口径: 把"这条记录不再有效"表达为一次**可审计的状态迁移**(`discarded`), 而不是物理删除; 保留内容, 编号与既有版本链, 供追溯. 新领域 `governance/lifecycle.clj` 暴露 `discard!`, 由 governance 以 `approval-command lifecycle/discard! "<kind>" false` 注册为 `[:requirements :discard]`, `[:documents :discard]`, `[:stakeholders :discard]` 三条命令, 路由 `POST /<collection>/:record_id/discard`. 三层门控按序: `latest!` 只允许最新版本(陈旧 409), `status!` 只允许可作废状态(requirement=`registered`, document=`registered`/`rejected`, stakeholder=`active`, 否则 409; 提交进入 `in_review` 的文档因此不可直接作废), 再收集引用证据(requirement 被追踪指向, document 被追踪 target/会议会前 material_ids/问题与风险 evidence_ids/行动 evidence_ids 引用, stakeholder 被 RACI stakeholder_id 或沟通受众引用), 命中即 409 并在消息里列出前若干来源. 通过后 `change!` 写 `discarded` 并记 `discard_reason`/`discarded_by`/`discarded_on` + 追加 `workflow_history` 审计项(含作废前状态). 命令走 `pms:project:edit` 写权限与项目作用域, 无编辑权 403, 未知字段 400.

因 `pms_gov_record` 状态 CHECK 约束原不含 `discarded`, 本轮新增整表重建迁移 `202609220011-gov-status-discard`(SQLite + MySQL 各 up/down): SQLite 不能 ALTER CHECK, 按 `PRAGMA foreign_keys=OFF` -> 建新表(状态 CHECK 增列 `discarded`) -> `INSERT...SELECT` -> `DROP` 旧表 -> `ALTER...RENAME` -> 重建索引 -> `PRAGMA foreign_keys=ON`; MySQL 采用建表 -> 拷贝 -> `DROP` -> `RENAME` -> 建索引. 该表仅有指向 `pms_project` 的出向外键, 无入向外键引用, 重建安全.

本轮实际执行的验证:

| 验证 | 实际结果 | 说明 |
|---|---|---|
| 迁移 | SQLite 全新空库迁移成功含 `discarded` 状态 | down 迁移回退原状态集合并过滤 discarded 行 |
| 治理命名空间 SQLite | 35 tests / 378 assertions, 0 失败/错误 | 新增 `requirement-discard-is-soft-and-reference-guarded`(viewer 403 + 未知字段 400 + 作废→`discarded` 带 reason/by/on + workspace 回显 + 重复 409 + 被追踪引用 409), `document-discard-rejects-referenced-and-non-discardable-status`(会议资料引用 409 + 独立文档作废 + `in_review` 状态门控 409), `stakeholder-discard-rejects-referenced-record`(RACI 引用 409 + 独立作废 + 作废后再被 RACI 引用命中 active 守卫 409) |
| 全量 PMS 回归 SQLite | 87 tests / 709 assertions, 0 失败/错误 | 无回归 |
| 前端编译 | shadow-cljs 0 warnings | "作废"按钮, `discard-dialog`, 状态列"已作废"与发布标签一并编译 |
| Chrome 浏览器 (Playwright) | 2 passed | `pms-h18.spec.js`: 证据文档页签未被引用文档点"作废"->"已作废"且入口消失, 被会议会前资料引用文档 409 拒绝并在弹窗错误面板显示"记录仍被其它对象引用, 不能作废"且仍"已登记", 已作废文档再作废经真实 HTTP 命中状态守卫 409; URS与干系人页签未被引用需求/干系人作废成功, 被追踪需求与被 RACI 指派干系人被拒且仍"已登记/有效"; 截图存 `reports/h18/` (h18-1..h18-5) |

本轮未执行 (如实记录): MySQL 迁移与回归(本地无可用实例), 状态 CHECK 表重建迁移仅经 SQLite 往返实证, MySQL 版待有环境时补跑并做升级/回退演练.

边界: H18 原目标是"草稿清理与资料保留策略"整行, 本轮仅交付其中"不把取消当删除, 软作废需权限/引用校验, 作废行为可审计"这一子集, 故矩阵记 `partial`. 仍缺: 正式历史按保留策略归档, 撤销/恢复已作废记录及其审计, 级联影响预览, 把已作废文档从 `document_collection` 归集口径中剔除 (当前归集不区分状态, 已作废仍计入总数), 生产验收. `discarded` 为终态, 无恢复命令; 界面隐藏已作废记录的作废入口但保留记录可见.

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
