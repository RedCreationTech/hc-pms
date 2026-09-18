# BPM 差距补全实施规格（对齐 ruoyi-office-vben / 芋道 BPM）

> 参考仓库：https://github.com/yuqing2026/ruoyi-office-vben （纯前端，Vben+Vue3，功能源自芋道 ruoyi-vue-pro）
> 本文档是 Phase 1-4 全部实现的规格约定，所有子任务必须遵循。
>
> ✅ **实施状态（2026-09-18）：Phase 1-4 全部完成并提交**（commit 09a3d13 / 002de55 / 6342f6e / 9ed5610），测试 372 项全绿。

## 0. 全局约定

### 0.1 代码结构
- 后端：`web/routes/business.clj` 加路由 → `web/controllers/business/bpm.clj` 加 handler → `domain/business/bpm.clj` 加业务 → `bpm/core.clj` 加 Flowable 引擎封装 → `resources/sql/business.sql` 加 HugSQL 查询
- 前端：`pages/business/bpm_*.cljs` 页面 + `components/` 组件；API 调用集中在 `api.cljs`（看现有 bpm 相关部分的惯例）
- 分页参数：`page` / `size`（不是 pageNum/pageSize）

### 0.2 数据库迁移（重要！）
- 新表同时写 `resources/migrations-sqlite/` 和 `resources/migrations/`（MySQL 版）
- **每条 SQL 语句之后必须紧跟一行 `--;;`**（migratus 要求，JDBC 单语句执行；漏写只执行第一条且不报错，见 AGENTS.md 踩坑记录）
- SQLite 自增 `INTEGER PRIMARY KEY`，MySQL `BIGINT AUTO_INCREMENT PRIMARY KEY`
- 迁移文件名：`YYYYMMDDHHMM-<name>.up.sql` / `.down.sql`，两目录同步

### 0.3 API 响应格式
- 成功 `{"code":200,"msg":"操作成功","data":...}`；列表 `data: {"rows":[...],"total":N}`
- 权限：管理类端点（管理员取消、任务管理已有先例）沿用 `wrap-jwt-auth` + perms 检查惯例

### 0.4 状态约定
- 流程实例 status 扩展：`RUNNING` / `APPROVED` / `REJECTED` / `CANCELED`（已有 RUNNING/APPROVED/REJECTED，新增 CANCELED）
- 任务节点配置存在 BPMN 的 `<flowable:property name="nodeConfig">` JSON 里（现有机制），新配置项一律进 nodeConfig，不改表结构

### 0.5 现有实现关键位置（子代理必读）
- Flowable 封装：`src/clj/com/ruoyi/bpm/core.clj`（approve! reject! transfer! delegate! claim!/unclaim! history-of task-history-of suspend!/activate! terminate! 等）
- 任务监听器（create 事件解析候选人/跳过）：`bpm/core.clj` 的 resolver（约 208-233 行）
- 节点树→BPMN 生成：`src/clj/com/ruoyi/domain/business/bpm_flow.clj`
- 设计器：`src/cljs/com/ruoyi/frontend/components/bpm_flow_designer.cljs`
- 抄送节点当前生成 userTask：`bpm_flow.clj:164-167`
- 身份同步：`bpm/core.clj:126-193`

---

## Phase 1 — 审批闭环

### 1.1 加签/减签
- `POST /api/business/bpm/task/create-sign` `{taskId, userIds[], type: "before"|"after", reason}`
  - 实现：`taskService.newTask()` 创建子任务，parentTaskId=当前任务，assignee=各加签人，name=当前任务名；reason 存任务局部变量
  - before/after 仅影响前端展示语义（子任务都须先完成）；父任务 approve 前校验无未完成子任务，否则报错"加签任务未完成"
- `DELETE /api/business/bpm/task/delete-sign` `{taskId, userIds[], reason}`
  - 删除指定子任务（`taskService.deleteTask(childId, true)`），记录 reason 到父任务评论
  - 校验：只能减签 parentTaskId=该任务、未完成的子任务
- `GET /api/business/bpm/task/sign-list?taskId=` → 该任务的加签子任务列表（含 assignee、status、reason）
- 前端：审批弹窗（bpm_todo.cljs）加「加签」按钮（多选用户+前/后加签选择+意见）；有子任务时显示「减签」下拉；任务详情显示加签子任务

### 1.2 取消（发起人/管理员）
- `DELETE /api/business/bpm/instance/cancel` `{id(实例id), reason}` —— 发起人或管理员
  - 实现：`runtimeService.deleteProcessInstance(instanceId, reason)`；`biz_bpm_instance.status` → `CANCELED`
  - 前端：我的流程列表 + 实例详情加「取消」按钮（确认+原因）；实例管理（bpm_ops）加管理员「取消」

### 1.3 撤回
- `PUT /api/business/bpm/task/withdraw` `{taskId}` —— 审批人撤回自己刚审完的任务
  - 实现：`createChangeActivityStateBuilder().moveActivityIdTo(nextActivityId, currentActivityId)` 把 token 移回；要求下一节点任务未完成
- `PUT /api/business/bpm/task/withdraw-to-start` `{processInstanceId}` —— 发起人撤回到起始节点重新编辑
  - 实现：changeState 移回发起人节点（删除中间任务）
- 前端：已办列表加「撤回」；我的流程审批中加「撤回」

### 1.4 抄送闭环
- 新表 `biz_bpm_copy`（copy_id, user_id, process_instance_id, activity_id, activity_name, reason, create_by, create_time）
- `POST /api/business/bpm/task/copy` `{processInstanceId, userIds[], reason, activityId?, activityName?}` — 插入抄送记录（通知语义：接收人在"抄送我的"列表可见）
- `GET /api/business/bpm/task/copy/page?page&size` — 我的抄送分页（当前登录用户）
- 设计器抄送节点改造：生成 userTask 时在 nodeConfig 标 `nodeType: "COPY_TASK"`；TaskListener create 时若为 COPY_TASK → 为每个候选人插 biz_bpm_copy 记录 + 自动 complete 任务
- 前端：审批弹窗加「抄送」按钮；新增「抄送我的」页面（菜单 + 路由 + 页面，仿待办列表）

### 1.5 可退回节点列表
- `GET /api/business/bpm/task/return-list?taskId=` → 当前任务之前已完成的用户任务节点列表（从历史活动实例收集，排除当前及之后节点、排除网关/开始）
- 前端：驳回弹窗的下拉从"全部节点"改为调用此接口

### Phase 1 验收
- bb test 全绿；新增测试命名空间覆盖新 API
- curl 冒烟：部署内置请假流程 → 发起 → 加签 → 子任务完成 → 通过 → 已办撤回 → 发起人取消另一实例 → 抄送接口写入并分页可查 → return-list 非空

---

## Phase 2 — 节点配置补全

### 2.1 候选人策略扩展（`bpm_flow.clj` + 设计器 `bpm_flow_designer.cljs` + `bpm.clj` resolver）
现有 8 种基础上新增（值与芋道对齐）：
- `INITIATOR_SELF`(36) 发起人本人 → assignee=发起人
- `USER_GROUP`(40) 用户组（消费 `biz_bpm_user_group` 表，group 内 user_ids 展开为 candidateUsers）
- `FORM_USER`(50) 表单内用户字段（nodeConfig.formUserField，发起时该字段值为用户名）
- `FORM_DEPT_LEADER`(51) 表单内部门负责人（nodeConfig.formDeptField，值为部门 id，解析其 leader）
- `EXPRESSION`(60) 流程表达式（nodeConfig.expression，TaskListener 内求值解析为用户名列表）
- 多人审批方式新增 `RANDOM`(1) 随机一人：TaskListener create 时随机选一个候选设为 assignee 并清候选

### 2.2 审批人为空策略（nodeConfig.emptyHandler）
- `AUTO_PASS` / `AUTO_REJECT` / `ASSIGN_USER`(指定成员) / `TO_ADMIN`(转交流程管理员，现有行为)
- TaskListener create 解析候选为空时按策略执行

### 2.3 审批人拒绝策略（nodeConfig.rejectHandler）
- `TERMINATE`（现有：走网关 approved=false 终止）
- `RETURN_NODE`（驳回时默认退回到 nodeConfig.rejectReturnNode 指定节点；前端驳回弹窗默认选中该节点）

### 2.4 操作按钮配置（nodeConfig.buttons）
- 每节点可配：`approve/reject/transfer/delegate/add-sign/return` 的 `{enable: bool, displayName: string}`
- task-detail API 返回当前节点 buttons 配置；前端审批弹窗按配置显隐/改名

### 2.5 手写签名（nodeConfig.signEnable）
- 审批弹窗出现签名画布（用 canvas 手写板组件），确认后上传（复用现有文件上传接口）得 signPicUrl
- 任务 complete 时 signPicUrl 存任务局部变量；时间轴/流转记录展示签名图

### 2.6 审批意见必填（nodeConfig.reasonRequire）
- 前端校验 + 后端 approve/reject 校验

### 2.7 超时处理生效
- `engine.clj` 开启 asyncExecutor
- `bpm_flow.clj` 生成 userTask 时若 nodeConfig.timeout 配置 → 挂边界定时事件（boundary timer，timeDuration），委托到统一 TimeoutHandler（JavaDelegate）：按 timeout.action（REMINDER 记录提醒日志 / AUTO_PASS 变量置 approved=true complete / AUTO_REJECT 同理）

### Phase 2 验收
- bb test 全绿；新策略/新配置项每个至少一个集成测试
- 冒烟：含 FORM_USER/USER_GROUP/RANDOM 节点的流程完整走通；超时 AUTO_PASS 用短定时器（10s）实测

---

## Phase 3 — 治理能力

### 3.1 流程定义版本页
- `GET /api/business/bpm/definition/page?page&size&modelKey` → Flowable 定义分页（version/suspensionState/deployTime/form 绑定）
- `PUT /api/business/bpm/definition/restore {definitionId}` → 把历史定义 BPMN 反写回模型（bpmn_xml），可重新编辑部署
- 前端：模型页「历史」入口 → 定义版本列表页；操作：查看 XML、恢复

### 3.2 模型启停/清理/复制
- `PUT /api/business/bpm/model/state {id, state}` — 挂起/激活该 key 最新定义（repositoryService.suspendProcessDefinitionByKey）
- `DELETE /api/business/bpm/model/clean?id` — 删除该流程所有历史实例+部署（historyService.deleteHistoricProcessInstances + 清 deployments by key）
- `POST /api/business/bpm/model/copy?id` — 复制模型（名称+“副本”，key+`_copy`）

### 3.3 流程编号规则（模型字段 process_id_rule JSON）
- 模型表加列或存 form_json 扩展；发起时生成单号：前缀+日期中缀+流水号（长度≥5，当日自增，存 biz_bpm_instance.bill_code 新列）
- 实例列表/详情返回 bill_code

### 3.4 自动去重（模型级 auto_approval_type）
- `NONE`（默认）/ `APPROVE_ONCE`（同一审批人只审一次，后续节点自动通过——TaskListener 检查该用户在已审任务中出现过则 AUTO_PASS）/ `CONSECUTIVE`（仅连续重复节点）

### 3.5 自定义标题与摘要（模型级 name_rule / summary_fields）
- 发起时按模板渲染实例名（支持 {字段} / {发起人} / {发起时间} 变量）
- summary：模型配 summary_fields（表单字段 id 列表），实例列表/抄送/待办返回 summary [{key,value}] 展示

### 3.6 打印
- 模型级 `print_template_enable` + `print_template_html`（存模型表新列或 form_json 扩展）
- `GET /api/business/bpm/instance/print-data?id=` → 实例+任务记录+签名图，前端拼 HTML 打印（浏览器打印即可）

### Phase 3 验收
- bb test 全绿 + 冒烟：版本页数据正确、恢复后可再部署、启停后不可发起、编号递增、标题渲染、打印数据完整

---

## Phase 4 — 进阶能力

### 4.1 Webhook（模型级 webhooks JSON：4 个钩子）
- process_start / process_end / task_start / task_end，每个 `{url, headers[], bodyParams[]}`（值支持固定值或 ${字段}）
- 实现：全局 Flowable event listener（引擎级）或在我们的 approve!/start! 等封装点统一触发，HTTP POST（clj-http），失败仅记日志不影响流程

### 4.2 触发器节点语义
- 设计器触发器节点 4 种类型：HTTP 请求（url/headers/body，response 回写表单字段）/ HTTP 回调（等待外部触发——本期降级为仅记录）/ 修改表单数据（条件+字段=值）/ 删除表单数据（条件+字段）
- `bpm_flow.clj` 生成 serviceTask + JavaDelegate 统一入口 `TriggerDelegate`，按 nodeConfig 分发执行（能同步做的同步做；回调类记日志跳过）

### 4.3 子流程节点
- child-process 节点配置：选择子流程定义、主→子变量映射（in: [{source,target}]）、子→主变量映射（out）、子流程发起人策略
- `bpm_flow.clj` 生成 callActivity，in/out 参数映射；前端子流程节点配置面板补齐

### 4.4 路由分支节点（router-node）
- 设计器支持"路由分支"：多组（目标节点+条件规则），`bpm_flow.clj` 展开为排他网关结构（等价于条件分支的语法糖，生成时展开）

### 4.5 节点监听器接线
- 设计器每节点（userTask）可配 Create/Assign/Complete 三个 HTTP 回调（`nodeConfig.listeners[{event,url,params}]`）
- TaskListener 对应事件触发 POST（参数含 taskId/实例/表单字段值）

### Phase 4 验收
- bb test 全绿 + 冒烟：webhook 收到回调（本地起 httpbin 或后端自建测试端点记录）、触发器 HTTP 请求回写变量、子流程变量传递、路由分支按条件走线、节点监听器三事件触发
