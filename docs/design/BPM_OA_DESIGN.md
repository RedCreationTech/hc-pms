# RuoYi-Clojure · 办公一体化能力设计文档（BPM / OA / HRM / CRM）

> **状态**：进行中 · **路线**：混搭（Clojure 应用 + 内嵌 Flowable Java 引擎做 BPM）
> **最后更新**：见文末 Changelog
> **目标**：为当前纯系统管理后端（无业务模块）引入 ruoyi-office 的办公子系统能力子集。

---

## 1. 背景与目标

当前项目是 RuoYi-Clojure：Kit 框架 + Integrant + Reitit + HugSQL + SQLite/MySQL 双库，
前端 Reagent 2 + antd 6。已具备完整系统管理（RBAC 用户/角色/菜单/部门/岗位/字典/参数/
通知/日志/定时任务/在线/缓存）与监控、仪表盘。**当前没有任何业务模块**（历史 biz_* 表已被清理）。

参考项目 `ruoyi-office`（Java/SpringCloud 微服务，17+ 子系统，基于芋道 yudao-cloud）
与其前端 `ruoyi-office-vben`（Vue3 + Vben Admin）。

### 1.1 规模对比与取舍（决定"能做什么"）

| | 当前项目 | ruoyi-office 后端 | vben 前端 |
|---|---|---|---|
| 后端代码 | 55 文件 / ~5k 行 | 4,257 文件 / **322k 行** | — |
| 前端代码 | 45 文件 / ~10k 行 | — | **582k 行**（Vue+TS） |
| 建表 | ~30 张 | **552 张** | — |

**结论**：全量移植不可行。**只取子集**，用当前 Clojure 栈 + 内嵌 Flowable 重实现。

### 1.2 模块取舍清单

| 决策 | 模块 | 参考后端规模 | 落地策略 |
|---|---|---|---|
| ✅ **必做** | **BPM 审批流** | 22,553 行 / 257 文件 | **内嵌 Flowable**，保留标准 BPMN 2.0 |
| ✅ **必做** | **OA 协同办公** | 9,402 行 | Clojure 重写（通知/日程/会议/公文模板） |
| 🟡 高价值 | **HRM 人力资源** | 7,481 行 | Clojure 重写（员工/考勤/薪资/请假） |
| 🟡 高价值 | **CRM 客户管理** | 18,812 行 | Clojure 重写（客户/商机/跟进/合同） |
| 🟡 高价值 | **数据报表** | 1,201 行 | Clojure 重写（统计看板） |
| 🔴 不移植 | mall 商城 / iot 物联网 / pay / mp / member | 46k 行 | 明确排除（与栈不匹配、价值低） |
| 🔴 按需 | erp / wms / asset / ai | 46k 行 | 大概率不做；ai 仅做 API 集成 |

---

## 2. 核心技术决策（路线 2：内嵌 Flowable）

### 2.1 为什么选混搭（Clojure + 内嵌 Flowable）

- BPM 是 ruoyi-office 核心差异化，而当前是 **Clojure(JVM)**，Flowable 是 Java 库，
  可直接 interop，**保留完整 BPMN 2.0 标准**（部署 .bpmn、任务引擎、流程实例）。
- 不重写 22,553 行 BPM 引擎；Clojure 只写业务壳 + API 封装。
- 前端建模器可用 **bpmn-js**（纯前端 JS，可直接被 shadow-cljs/Reagent 引入），
  与后端引擎解耦——前端画 .bpmn，后端 Flowable 跑。

### 2.2 可行性 Spike 结论（已验证）

在 JDK 26 上做了端到端 spike（`/tmp/fspike`）：

| 项 | 结果 |
|---|---|
| Flowable 版本 | **7.2.0 / 8.0.0 均可**（推荐 8.0.0） |
| JDK 26 兼容 | ✅ 引擎可构建、部署、启动流程、任务流转、结束 |
| **H2 版本** | **必须 H2 1.4.200**（Flowable 的 `IDENTITY` 方言不兼容 H2 2.x） |
| 异步执行器 | ✅ 可开（timer/async 任务可用），JDK 26 无阻断问题 |
| 数据库解耦 | ✅ Flowable 用独立 H2 文件，与 app 业务库（SQLite/MySQL）无关 |

> ⚠️ **H2 1.4.200 安全说明**：该版本有 CVE-2022-23221（RCE，仅当 JDBC URL 可被不可信方
> 控制时触发）。本项目 Flowable 的 JDBC URL 由系统配置硬编码，**不受影响**。若需更高安全
> 可改用外部 MySQL/PostgreSQL 作为 Flowable 引擎库（同一套引擎代码）。

### 2.3 数据库双轨架构

```
┌───────────────────────────────┐     ┌──────────────────────────────┐
│  App 业务库                    │     │  Flowable 引擎库 (独立)        │
│  SQLite (默认) / MySQL         │     │  H2 1.4.200 文件 (flowable.db) │
│  sys_* 系统表                  │     │  ACT_* 引擎表 (Flowable 自建)  │
│  biz_* 业务表                  │     │                                │
│  biz_oa_* / biz_hrm_* ...      │     │  BPMN 流程定义、流程实例、任务 │
└───────────────────────────────┘     └──────────────────────────────┘
        │  (业务记录 <-> 流程实例)               ▲
        └────────── 通过 business_key / 外键关联  │
```

- **业务数据**（请假单、合同、OA 表单）存 app 库，含 `process_instance_id` 关联 Flowable 实例。
- **流程引擎状态**（定义、实例、任务、变量、历史）全在 Flowable 的 H2。
- 这样 **app 库仍是 SQLite/MySQL 双兼容**，Flowable 库独立，互不污染。
- 生产部署可把 Flowable 库切到 MySQL/PostgreSQL（仅改 JDBC URL）。

### 2.4 依赖

```clojure
;; deps.edn 新增
org.flowable/flowable-engine {:mvn/version "8.0.0"}
com.h2database/h2            {:mvn/version "1.4.200"}
```

---

## 3. 架构分层（扩展现有 5 层）

完全沿用现有分层，新增 `business/` 横向模块层与 `bpm/` 引擎封装：

```
route (web/routes/business.clj)              ← 新增，挂 /api/business/*，走 auth 中间件
  → controller (web/controllers/business/{bpm,oa,hrm,crm,report}.clj)
    → domain service (domain/business/{bpm,oa,hrm,crm}.clj)   ← Integrant 组件
      → HugSQL (resources/sql/business/*.sql)                  ← SQLite+MySQL 双兼容
        → infra/db.clj
bpm/engine.clj     ← Flowable ProcessEngine 的 Integrant 组件封装（唯一接触引擎处）
bpm/core.clj       ← 高层 API：部署/发起/审批/驳回/转办/待办/已办/流程图
```

- **权限**：复用现有 `sys_menu` 动态菜单 + `sys_role_menu` + `require-perms`（遵守 AGENTS 7.4）。
  每个新模块的菜单/按钮权限都插入 `sys_menu`，前端从接口动态加载。
- **分页**：前端传 `page`/`size`（遵守 AGENTS 7.3）。
- **迁移**：业务表 `biz_*`，双库同步（`migrations-sqlite/` 与 `migrations/`），迁移号顺延。

### 3.1 Integrant 组件接线

```
:app.bpm/engine        ← 新增：构建 Flowable ProcessEngine（H2 1.4.200）
:app.business/oa-service     { :query-fn #ig/ref :db.sql/query-fn
                               :db       #ig/ref :db.sql/connection
                               :bpm      #ig/ref :app.bpm/engine }
:app.business/hrm-service   ...  (同构)
:app.business/crm-service   ...  (同构)
:reitit.routes/api   ← 注入 business routes
```

---

## 4. 数据模型设计

### 4.1 BPM（业务侧，app 库）——记录与 Flowable 的映射

| 表 | 用途 | 关键列 |
|---|---|---|
| `biz_bpm_category` | 流程分类（请假/报销/合同/采购…） | `category_id,name,code,status` |
| `biz_bpm_model` | 流程模型（bpmn XML + 表单 schema） | `model_id,key,name,form_type,form_json,bpmn_xml,deployment_id,status,version` |
| `biz_bpm_form` | 动态表单定义 | `form_id,name,form_json,status` |
| `biz_bpm_instance` | 流程实例业务映射 | `instance_id,process_instance_id,model_id,form_data_json,starter_id,status,current_task,create_time` |

> Flowable 引擎侧自管：`ACT_RE_DEPLOYMENT`、`ACT_RE_PROCDEF`、`ACT_RU_*`、`ACT_HI_*`。
> 业务记录通过 `process_instance_id` 关联。

### 4.2 OA 协同办公

| 表 | 用途 |
|---|---|
| `biz_oa_notice` | 公告（可复用/增强现有 sys_notice） |
| `biz_oa_calendar` | 日程/日历 |
| `biz_oa_meeting` | 会议管理 |
| `biz_oa_document` | 公文/文档（含附件 `biz_attachment`） |
| `biz_oa_todo` | 待办聚合视图（或直接查 Flowable 任务） |

### 4.3 HRM 人力资源

| 表 | 用途 |
|---|---|
| `biz_hrm_employee` | 员工档案（关联 sys_user / sys_dept） |
| `biz_hrm_attendance` | 考勤 |
| `biz_hrm_leave` | 请假（走 BPM 审批） |
| `biz_hrm_salary` | 薪资 |

### 4.4 CRM 客户管理

| 表 | 用途 |
|---|---|
| `biz_crm_customer` | 客户 |
| `biz_crm_contact` | 联系人 |
| `biz_crm_business` | 商机 |
| `biz_crm_followup` | 跟进记录 |
| `biz_crm_contract` | 合同（走 BPM 审批） |

### 4.5 通用附件

| 表 | 用途 |
|---|---|
| `biz_attachment` | 通用附件表，所有模块复用（可对接现有 `system/file` 文件管理） |

---

## 5. BPM 核心 API 设计（Flowable 封装）

`com.ruoyi.bpm.core` 提供高层函数，Controller 只调这些：

```clojure
;; 流程定义
(bpm/deploy! engine bpmn-xml key name)      ; 部署
(bpm/list-definitions engine)               ; 已部署定义

;; 流程实例
(bpm/start! engine definition-key business-key vars)  ; 发起
(bpm/instance-list engine)                  ; 运行中实例
(bpm/history engine business-key)           ; 历史轨迹

;; 任务
(bpm/todo-list engine user)                 ; 待办
(bpm/done-list engine user)                 ; 已办
(bpm/claim! engine task-id user)            ; 认领
(bpm/complete! engine task-id user vars)    ; 审批通过（含驳回变量）
(bpm/reject! engine task-id user comment)   ; 驳回/打回
(bpm/transfer! engine task-id from to)      ; 转办
(bpm/delegate! engine task-id ...)          ; 委派

;; 流程图
(bpm/diagram engine process-instance-id)    ; 生成流程图(高亮)
```

---

## 6. 前端策略

- **不复用 Vben 代码**（Vue3 → Reagent 不可移植），只移植 UI 语义，用 antd 6 重画。
- 页面放在 `src/cljs/com/ruoyi/frontend/pages/business/`。
- **BPM 建模器**：集成 **bpmn-js**（npm 包，经 shadow-cljs 引入），
  前端画图存 .bpmn XML → 后端 Flowable 部署。审批面板 + 流程高亮用 antd + bpmn-js 渲染。
- 遵守前端规范：React Hooks（禁用 reagent/atom）、antd 6 新 API、动态权限菜单。

---

## 7. 分阶段实施路线

### Phase 0 — 底座（✅ 完成）
- [x] Flowable 可行性 spike（JDK 26 + H2 1.4.200，端到端跑通）
- [x] 加 Flowable + H2 依赖（deps.edn）
- [x] 建 `bpm/engine.clj` Integrant 组件并接线 system.edn
- [x] BPM 业务表迁移 + 动态菜单（`biz_bpm_*`/`biz_attachment`，SQLite+MySQL 双份）
- [x] BPM 后端垂直切片完成（分类/模型/表单/实例/任务），REST 实测通过

### Phase 1 — 旗舰：BPM 审批流 + OA（✅ 后端完成）
- [x] BPM 核心 API 封装（`bpm/core.clj`）：部署/发起/待办/审批/驳回/转办/已办/历史
- [x] BPM 单元测试（内存 H2，22 断言全过）
- [x] 动态菜单（办公目录含 BPM/HRM/OA）
- [x] OA：日程 + 会议（`biz_oa_calendar`/`biz_oa_meeting`）
- [ ] bpmn-js 建模器前端 / 审批面板（前端待做）

### Phase 2 — HRM / CRM / 报表
- [x] HRM：员工档案（`biz_hrm_employee`，含部门联表）
- [ ] HRM：考勤/请假(审批)/薪资
- [x] CRM：客户管理（`biz_crm_customer`）
- [ ] CRM：商机/跟进/合同(审批)
- [ ] 数据报表统计看板

### Phase 1 — 旗舰：BPM 审批流 + OA（核心差异化）
- [ ] BPM 完整 API 封装（部署/发起/审批/驳回/转办/待办/已办）
- [ ] bpmn-js 建模器前端
- [ ] OA：待办中心、日程、会议、公文模板
- [ ] 请假/报销标准审批模板跑通

### Phase 2 — HRM / CRM / 报表
- [ ] HRM：员工/考勤/请假(审批)/薪资
- [ ] CRM：客户/商机/跟进/合同(审批)
- [ ] 数据报表统计看板

### Phase 3 — 生产化
- [ ] Flowable 库切外部 MySQL/PG（可选）
- [ ] E2E + 覆盖率
- [ ] 文档补全

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Flowable 在 JDK 26 有隐性不兼容 | 已做 spike 验证核心路径；异步执行器待更充分测试；必要时给 Flowable 单独用 JDK 21 子进程 |
| H2 1.4.200 有历史 CVE | 引擎库 URL 硬编码不受影响；生产可切外部 MySQL/PG |
| BPM 复杂度高 | 先跑通一条标准审批流，再扩展会签/条件/子流程 |
| 双库迁移同步 | 遵守现有 SQLite/MySQL 兼容规则，每表双份 |
| Vben 前端不可复用 | 只移语义用 antd 重画；bpmn-js 独立可复用 |

---

- **2026-08-26 (56)** **P-C 组件库 18→20 种：上传/图片上传**：
  · 组件库 +文件上传(upload)/图片上传(upload-image)，antd Upload + /common/upload 通用上传 API
  · 值模型 url 数组；文件/图片(卡片)两种展示；onRemove 删除
  · 修复：Reagent kebab props 不匹配 antd camelCase(fileList/customRequest/treeData 等)
  · 修复 bpm_start on-success 缺括号导致 bpm-get-form 回调不执行(fn-arity 误判)
  · tree-select 改用 antd/select 拉平树选项(带层级缩进)——antd v6 TreeSelect 在 modal 内渲染异常
  · 实测：文件/图片上传控件渲染、部门选择层级缩进、字典按类型过滤

- **2026-08-26 (55)** **P-C 组件库齐平（14→18 种）**：
  · 新增 滑块(slider)/级联(cascader)/部门树选择(tree-select)/字典选择(dict-select)
  · 发起页数据注入：tree-select 加载部门树，dict-select 按 dict-type 拉取系统字典选项
  · 修复系统 bug：dict 接口 query-params string keys 导致 dict_type 过滤失效(keywordize)
  · 实测：滑块渲染；部门树展开；字典选项按类型过滤(是/否)；字段联动/分割线/校验前序完成
  · 组件清单：input/textarea/number/date/date-range/datetime/time/radio/checkbox/select/switch/rate/
    slider/cascader/tree-select/dict-select/user/dept/divider = 18 种

- **2026-08-26 (54)** **P-C 字段联动显隐（对齐 vben showControl）**：
  · 字段 props.relation={field,value}：该字段值等于 value 时显示本字段，否则隐藏
  · 设计器属性面板加"显示条件(联动)"：字段 select + 等于值 input
  · 实测：请假类型选"出差"→天数显示，选"事假"→天数隐藏

- **2026-08-26 (53)** **P-C 分割线组件（表单分组排版）**：
  · 组件库 14→15 种(+分割线)；form_render 渲染 antd Divider(标题左对齐)
  · 设计器添加分割线 → 发起页渲染"审批信息"分隔线，不占表单字段
  · 实测设计器添加/发起渲染正常

- **2026-08-26 (52)** **P-B 为空/与提交人相同处理（运行时）**：
  · TaskListener resolver 扩展：与提交人相同(SKIP 移除发起人 / ASSIGN_DEPT_LEADER 转部门负责人)、
    为空处理(ASSIGN_USER 指定用户 / TRANSFER_ADMIN 转管理员)
  · SKIP 且候选人为空时 listener 自动 complete 跳过节点（避免任务卡住）
  · 实测：SKIP 发起人=审批人自动跳过(任务0)；为空 ASSIGN_USER 指定 ry 可见

- **2026-08-26 (51)** **P-D 审批操作按钮：转办/委派**：
  · 待办行加"转办/委派"按钮 → 目标用户选择弹窗（复用 list-users）
  · 后端新增 delegate 路由/controller（transfer 已有）；api.cljs 加 bpm-transfer-task/bpm-delegate-task
  · 实测：转办给 ry 成功，任务 assignee 变为 ry

- **2026-08-26 (50)** **P-C 表单校验规则（对照 vben @form-create 补强）**：
  · 差距分析：vben 用 @form-create(25+组件/规则/联动/布局)，我们 14 组件+基础属性；核心闭环已通
  · 表单设计器属性面板加"校验规则"：手机号/邮箱/6位数字/自定义(暂占位)
  · form_render Form.Item 支持 rules(required/pattern)；发起页提交前手动校验(必填+正则，错误 message 阻止提交)
  · 修复 CLJS return 不支持 → if-let 重构
  · 实测：错误手机号提交被拦截+提示"手机号格式不正确"，modal 保持打开；正确后提交成功

- **2026-08-26 (49)** **P-C 条件表达式字段选择器**：
  · 条件规则编辑器字段从文本框改为下拉：模型关联表单字段 + approved/startUserId 常用变量
  · 与表单字段联动（days/reason 等可直接选），减少手输错误

- **2026-08-26 (48)** **P-C 节点级字段权限：审批节点配置字段可见性，运行时审批应用**：
  · 设计器审批节点配置抽屉加"表单字段权限"（加载模型关联表单字段，每字段 可编辑/只读/隐藏）→ 存 config.fields-permission
  · 后端 task-detail 返回任务节点字段权限（修复：传 Task 对象而非 map，node-config-of 才能读 BPMN）
  · 审批弹窗 form-render 应用 field-permissions（修复 keyword/字符串 key 匹配）
  · 实测：节点配置 days=hidden → 审批弹窗只显示请假事由，天数隐藏
  · 附带：node-config-of 改 public、task->map* 公开、NREPL 7000 被 ControlCenter 占用改用 7001

- **2026-08-26 (47)** **P-A3 多实例审批运行时（或签/会签/比例）**：
  · tree->bpmn 生成 multiInstanceLoopCharacteristics：collection 按节点 id(approverList_<id>)、
    elementVariable=approver、assignee=${approver}、completionCondition(ANY 任一完成/ALL 全部/RATIO 比例)
  · 发起时 collect-multi-nodes 收集多实例节点，candidate-names 按候选策略展开用户 → 注入集合变量
  · 修复 collect-multi-nodes walk nil 无限递归/StackOverflow
  · 实测：会签2人(admin/ry)生成2实例任务 assignee 正确，admin审批后等ry，全部审批后流程结束
  · 测试：审批弹窗多 form 导致 strict violation，改用 .last()

- **2026-08-26 (46)** **P-A 集成断点：表单字段→流程变量 + 审批表单回显**：
  · 发起时表单字段展开为流程变量（days/reason 等可直接用于条件表达式 ${days > 3}），formData 保留完整 JSON
  · 新增 task-detail API：任务信息+实例表单数据+表单 schema；审批弹窗显示只读"申请表单"
  · 修复 events bpm/todo-set-form 两个 assoc-in 独立导致 form-data 丢失
  · 实测：API 发起后 days=5/reason 变量生效；审批弹窗显示请假事由/天数只读回显

- **2026-08-26 (45)** **动态表单补强：日期范围/日期时间组件 + 字段禁用/隐藏属性**：
  · 组件库 12 → 14 种（+日期范围/日期时间）；date-range 双 input 组合值 "start~end"
  · 属性面板 + 禁用/隐藏开关（写入 props.disabled/props.hidden）
  · form_render 支持字段级 disabled（props.disabled）与 hidden（props.hidden），与字段权限叠加
  · 实测：设计器添加日期范围/属性开关；发起页 4 字段渲染、days 禁用、日期范围双 input

- **2026-08-26 (44)** **流程详情流程图改用 HTML/flex 只读设计器（与编辑界面一致）**：
  · bpm-flow-designer 增加只读模式(read-only?)：隐藏添加节点/删除/编辑/保存，标题"流程追踪"
  · 节点高亮：is-active(红)/is-completed(绿)，capsule 同样支持（对齐 bpmn-viewer 高亮语义）
  · 详情抽屉流程图 block 改用 bpm-flow-designer 渲染（model-tree + active/completed ids）
  · 后端 instance-history :model 补 model_id
  · 实测：详情页节点卡片+active(部门经理审批红)/completed(start绿)高亮，0 报错

- **2026-08-26 (43)** **覆盖率 + 收尾**：Cloverage 行62.57%/分支74.75%(target/coverage/index.html)；
  双库(SQLite/MySQL) 349 测试 0 失败；BPM E2E 5 通过；开发环境恢复 SQLite。
- **2026-08-26 (42)** **MySQL 双库测试通过 + 兼容性修复**：bpm-mgmt 迁移 MySQL 版 TEXT NOT NULL DEFAULT
  → VARCHAR(255)；202608260001 迁移按 `--;;` 分隔每条 ALTER(MySQL JDBC 单语句)；list-users dept_ids
  空数组→[0] 哨兵(HugSQL :v* 空数组生成 IN () 语法错误)；MySQL 本地实例 349 测试 0 失败。
- **2026-08-26 (41)** **字段权限：模型表单字段 隐藏/只读/编辑**：biz_bpm_model 加 fields_permission 列；
  模型表单 tab 字段权限配置表格；form_render 支持 :field-permissions(hidden 不渲染/readonly 禁用)；
  发起页应用；修复 js->clj keywordize 导致权限 key 与字段名不匹配；实测 readonly 禁用/hidden 隐藏。
- **2026-08-26 (40)** **条件规则编辑器 + 网关配置入口修复**：条件分支配置支持表达式/规则双模式
  (字段+运算符+值，&& 组合自动生成表达式)；修复重构后网关失去编辑入口(条件标签可点击打开配置)。
- **2026-08-26 (39)** **发起全链路(二)：审批人自选**：发起页检测 START_USER_SELECT 节点渲染用户多选；
  提交注入 startUserSelected；后端注入 startUserId/startUserSelected 变量；TaskListener resolver
  ids→user_name(修正 sequential? 对 ArrayList 为 false)；实测被选用户待办可见。
- **2026-08-26 (38)** **流程详情页：表单回显+审批历史+流程图追踪**：bpm_instance 重做详情抽屉
  (Descriptions 基本信息/form_render 表单回显/审批历史时间线含意见/流程图高亮)；instance-history 增强；
  新增 task-history-of(comment/approved 任务局部变量)；修复 r/with-let 中 dataSource 传 atom 崩溃。
- **2026-08-26 (37)** **发起流程全链路：通用发起页+动态表单渲染**：bpm_start.cljs(菜单302"发起流程"
  挂 path=bpm/start)；模型列表→发起弹窗→动态表单渲染(form_render 复用)→填表提交；form_type=1 按
  form_id 从表单库加载 schema；修复 CLJS System/currentTimeMillis→js/Date.now。
- **2026-08-26 (36)** **BPMN 修改流程自洽(二)：默认线+抄送+驳回+动态策略**：网关 default 属性+无条件
  默认线(此前条件不满足卡住)；抄送节点 candidateUsers/Groups 运行时；驳回 RETURN_USER_TASK 用
  ChangeActivityStateBuilder 迁移回目标节点(驳回到自身=重新激活)；动态策略 TaskListener(engine 注册
  bpmTaskListener bean + bpm-service 注入 resolver，发起人部门负责人实测)；修复 nodeConfig 解析用本地名
  properties/property；DelegateTask 用 Flowable8 新包名。
- **2026-08-26 (35)** **BPMN 修改流程自洽：process id + Flowable identity 同步**：
  · tree->bpmn 支持 model-key 参数：BPMN process id 用模型 key（此前固定 "p" 导致多模型部署 key 冲突）
  · 候选策略运行时映射：USER→candidateUsers(用户名)，ROLE/DEPT_MEMBER/POST→candidateGroups(role:id/dept:id/post:id)，
    DEPT_LEADER/MULTI_LEVEL→candidateGroups(dept-leader:id)
  · 新增 Flowable identity 同步（bpm/core.clj sync-identity!）：部署时把系统用户/角色/部门/岗位及 membership
    同步到 Flowable identity 表（幂等 create-or-skip）；用户 id 用 user_name 与任务查询对齐
  · system.sql 新增 list-user-roles/list-user-posts/list-all-depts；bpm.clj 新增 load-identity-data
  · 修复迁移文件 `;--;;` 行内分隔符导致 form_id/form_custom_create_path 列丢失（sqlite+mysql 同步修正）
  · 修复 bpm_model.cljs 自定义表单 tab `(doall [[..][..]])` 导致 antd "Key must be integer"（改 fragment）
  · 实测 ROLE 策略全链路：保存→部署(candidateGroups=role:1)→发起→admin待办可见→审批→条件分流→财务审核→结束
  · BPM E2E 5通过；后端349测试0失败

- **2026-08-26 (34)** **流程设计器视觉与交互优化**：
  · 分支布局改为 vben 风格：网关以左侧"添加条件"小按钮呈现，条件分支用虚线分组容器横向排列
  · 条件节点默认命名"条件1/条件2"（不再取子节点名造成重复）；条件标签改为蓝色/绿色胶囊，显示表达式小字
  · 连线加粗至 2.5px、颜色加深，底部箭头改为 CSS 三角，更清晰
  · 节点卡片副标题实时推导：审批人策略/自动审批/延迟/抄送等，未配置时显示"请配置审批人"
  · 新增"添加条件"按钮实时插入条件分支（已验证 before 4 → after 5）
  · 修复：render-node/render-branch/render-card 签名传递 show-text-fn；add-condition! 正确更新 :condition-nodes
  · 实测：流程图结构清晰；BPM E2E 5通过；后端349测试0失败



- **2026-08-25 (33)** **流程模型对齐 vben：表单自定义（设计器+关联+预览）**：
  · 后端：biz_bpm_model 加 form_id/form_custom_create_path/form_custom_view_path（sqlite+mysql
    迁移，migratus 需 --;; 分隔）；model CRUD 支持；form-list 返回 form_json 且支持 status 过滤；
    update-model 用 COALESCE 保护 bpmn_xml（元数据保存不覆盖流程 XML）
  · **表单设计器** form_designer.cljs（对齐 vben @form-create 的 conf/fields JSON）：
    三栏布局（组件库12种/画布/属性配置），画布卡片可选中/上移/下移/复制/删除，
    属性含标题/字段名/占位符/必填/默认值/选项编辑；流程表单列表行加"设计"按钮
  · **表单渲染器** form_render.cljs：conf/fields → antd 表单（input/textarea/number/date/time/
    radio/checkbox/select/switch/rate/user/dept），供预览与后续发起流程复用
  · **模型表单 Tab 重做**（对齐 vben form-design.vue）：表单类型 Radio(无/动态/自定义) +
    动态表单从表单库选择 + 只读预览 + 自定义表单提交路由/查看地址
  · 模型编辑 modal 顶部加"保存"按钮（保存元数据，与流程树保存独立）
  · 修复：antd 加 checkbox/time-picker/rate/modal-confirm!；form_json 兼容字符串/对象
    (row->json 已解析)；get-in 传数字路径崩溃；模型 tab 保存按钮/流程设计器与元数据保存分离
  · 实测：设计器添加3组件→保存→重开持久化；模型选动态表单→预览3字段→保存→DB form_id=1；
    BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (32)** **HTML/flex 流程设计器——节点配置抽屉（移植 vben nodes-config 核心）**：
  · 点击节点打开右侧配置抽屉（antd Drawer），按类型渲染配置表单
  · **审批人节点**：审批类型(人工/自动通过/自动拒绝)、审批人设置(指定用户/角色/部门成员/
    部门负责人/岗位/发起人部门负责人及上级+向上层级)、多人审批方式(依次/或签/会签/按比例)、
    审批人拒绝时(终止/驳回到指定节点)、超时未处理(开关/自动提醒/通过/拒绝+时长+提醒次数)、
    审批人为空时(自动通过/拒绝/转交管理员/指定用户)、审批人与提交人相同时、签名/审批意见、
    跳过表达式
  · **抄送节点**：抄送人(用户/角色多选)；**条件分支**：条件名称+表达式(如 ${days} > 3)；
    **延迟器**：时长+单位；发起/结束/触发器/分支：改名
  · **配置持久化 round-trip**：config 以 <flowable:property name="nodeConfig" value="JSON"/>
    内嵌 BPMN 元素；审批人落地 flowable:candidateUsers/Groups 运行时可用；条件表达式解析
    (修复 with-body 正则误吞自闭合 sequenceFlow 的 bug + XML 实体 unescape 保证往返稳定)
  · 修复：antd v6 Drawer width→size、opt-* 迭代 atom 而非 deref(ISeqable 崩溃)、
    list-roles/depts/posts 响应为数组(兼容 rows-or-vec)、字段 snake_case 键名
  · 实测：配置指定角色→选中超级管理员→保存→卡片显示"指定角色"→后端 role-ids [1] 持久化；
    BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (31)** **HTML/flex 流程设计器完善工具栏/居中/添加节点**：
  · 流程整体水平居中（.bpm-flow-root align-items:center + width:100%，实测卡片/胶囊中心=页面中心）
  · 顶部工具栏(.bpm-toolbar)：流程标题 + 添加节点/缩放±/百分比/重置/保存按钮
  · 实现添加节点：工具栏"添加"或连线蓝色"＋" → 弹窗列 9 种节点(圆形图标+文字，对齐 vben handler-item)，
    选中在末尾/该位置插入新节点(原节点作为其 child)
  · 修复："添加"用 find-end 定位末尾，避免错误替换 root；
    修正误判为"靠左/缺分支"——实为 E2E 遗留 itLeave 简化模型排前所致
  · 修复：`index.html` 漏引入 `bpm-designer.css` 导致画布/添加节点弹窗样式全部未生效，已补上

 start→审批→gw0(横向财务/驳回)→end，居中，
    添加节点 1→2；BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (30)** **HTML/flex 流程设计器样式完善（对齐 vben node-box）**：
  · 卡片加内容区(.bpm-node-content)：显示 showText 或"请配置X"提示(对齐 vben node-content)
  · 卡片 hover 显示工具栏(.bpm-node-toolbar)：红色删除按钮(可删除节点，子节点上提)
  · 条件分支节点改为独立分支卡片(.bpm-branch-card 150px圆角)，横向展开条件
  · 修正删除逻辑(删除节点用其 child-node 上提)
  · 实测：内容区/分支卡片/删除按钮齐全，删除 2→1 生效；BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (29)** **流程编辑器改用纯 HTML/CSS flex 模式（对齐 vben simple-process-design）**：
  · 放弃 bpmn-js 渲染，改用 vben 式 HTML 卡片节点 + flex 垂直布局
  · 后端新增 `bpm_flow.clj`：BPMN XML ↔ 流程节点树双向转换（childNode 主链 +
    conditionNodes 分支），网关多条件出线→条件分支，默认线目标已被条件覆盖则不重复；
    `GET/POST /api/business/bpm/model/:id/tree` 读写树
  · 前端新增 `bpm-flow-designer` 组件：发起/结束椭圆胶囊、审批卡片(200px圆角+彩色图标)、
    灰线箭头连线+蓝色#0089ff圆钮(对齐vben)、条件分支横向展开、节点点击改名称、
    保存(树→BPMN)。流程设计 tab 已替换 bpmn-js
  · CSS `bpm-designer.css` 增 flex 布局（垂直 column + 卡片 + 连线 + 分支横向）
  · 实测：reimburseApproval 垂直渲染 start→审批→条件分支(横向财务/驳回)→end；
    BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (28)** **内置流程模型改为 vben 式从上到下垂直布局**：
  · 重写 leaveApproval/reimburseApproval 的 BPMNDI：主链节点从上到下垂直排列
    （start→审批→网关→…→end，X 居中，Y 递增），驳回终点(rejectEnd)放右侧侧边
  · 连线改为垂直（上节点底→下节点顶），驳回分支水平折线到侧边终点
  · 同步更新 SQLite + MySQL 迁移文件，新环境部署同样为垂直布局
  · 实测：leave 9节点/ reimburse 7节点从上到下依次排列，rejectEnd 侧边，import 0 错误；
    BPM E2E 5通过（语义不变仅视觉）；后端349测试0失败

- **2026-08-25 (27)** **画布节点样式与布局参照 vben simple-process-design**：
  · 新增 `style-nodes!`：import 后遍历 elementRegistry，按 BPMN 类型给每个 shape 加
    `bpmn-node-<type>` 类（UserTask/Gateway/Event/CallActivity/SubProcess 等）
  · CSS 节点配色对齐 vben：审批人/办理人/抄送(橙 #ff943e)、条件(绿 #67c23a)、
    并行(紫 #626aef)、包容(蓝 #345da2)、延迟(红 #e47470)、触发(蓝 #3373d2)、
    子流程(棕 #996633)、开始/结束(灰 #676565)；UserTask/CallActivity/SubProcess
    圆角 8px 卡片；节点 hover 边框蓝 #0089ff
  · 插入/导入/重绘后重新调用 style-nodes!，新节点同样着色
  · 布局固定：插入节点固定尺寸(任务100x80/网关50x50/事件36x36)且位于连线中点
  · 实测：所有节点含 class 并正确着色(审批人橙/网关绿/事件灰)，新插入网关也着色；
    BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (26)** **修复连线上'+'浮层菜单与插入节点两个 bug**：
  · 浮层菜单显示时间太短：改为 hide-timer(800ms) + 菜单自身 hover 保持显示、
    离开菜单才延迟隐藏（原 300ms setTimeout，鼠标从'+'移到菜单时易消失）
  · 插入节点位置错误：insert-node! 改用连线 waypoints 端点计算中点，
    替代原 shape 左上角坐标（不同节点高度导致中心偏移），新节点现正确
    绘制在连线中点（实测 start→approve 中点插入，中心坐标吻合）
  · 实测：hover 菜单 900ms 保持/移到菜单 1200ms 保持；插入节点中心在连线中点上；
    BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (25)** **连线上'+'操作过程对齐 vben/yudao `simple-process-design`**：
  · '+'由 click→居中 Modal 改为 **hover→'+'旁弹出 320px 圆形彩色图标浮层菜单**（对齐 vben
    node-handler 的 Popover）：10 种节点——审批人/办理人/抄送/条件分支/并行分支/包容分支/
    延迟器/路由分支/触发器/子流程，每个为 50px 圆形图标(用 bpmn-js 自带图标配 vben 配色)+
    文字，点击直接拆分连线插入(源→新节点→目标)
  · 节点类型与 vben 对齐(含"办理人"，原来只有"经办人")，图标配色对应 vben
    (approve橙/条件绿/并行紫/包容蓝/延迟红/路由红/触发蓝/子流程棕等)
  · 修复内置模型 BPMNDI：网关 shape 的 bpmnElement 与语义 id 不匹配
    (gwapprove/gwleader/gwhr/gwfinance → gw0/gw1/gw2)，画布现在完整显示所有网关节点
    (leaveApproval 9节点 / reimburseApproval 7节点，import 0 错误)
  · 实测：hover 弹出 10 图标菜单、点击插入 6→8；leave/reimburse 画布完整无报错；
    BPM E2E 5通过；后端349测试0失败

- **2026-08-25 (24)** **流程画布视觉样式对齐 vben bpmn-process-designer**：
  · 新增 `css/bpmn-designer.css`：画布 40px 网格纸背景(与 vben 同款 base64 SVG)、
    调色板白底/1px边框/圆角2px/阴影、调色板条目 hover 右侧 title 提示
  · 画布高度 620→780px（vben 为 800px）
  · 查看器高亮配色对齐 vben(theme/index.scss)：进行中→蓝 #409eff(原红)、
    已完成→绿 #4eb819、新增被拒绝→红 #f56c6c、已取消→灰 #909399，连线随状态变色
  · 实测：网格背景生效、设计器节点选中/属性面板/连线上+加节点(6→8)均正常；
    构建 0 警告；BPM E2E 5 通过；模型未污染

- **2026-08-25 (23)** **流程设计画布工具栏对齐 vben `bpmn-process-designer`**：
  · 顶部工具栏重构为 vben 分组：文件控制(打开/下载XML·SVG·BPMN/预览XML·JSON)、
    对齐控制(左/右/上/下/水平/垂直居中)、缩放控制(缩小/百分比/放大/重置)、
    撤销/恢复/重新绘制
  · 新增服务：`save-svg!`/`export-bpmn!`(下载)/`align-elements!`(选中多元素对齐，
    不足 2 个提示)、`import-local-file!`(打开本地 XML)/`new-diagram!`(重新绘制)/
    `preview`(XML 高亮弹窗 + JSON 节点概况)
  · 画布缩放实时百分比显示（`canvas.viewbox.changed` → on-zoom-change）
  · 修复 React error#130：图标须用原始 `[:> IconOutlined]`（ns 直接 refer），
    不能用 `[:> antd/icon-wrapper]`（适配类对象引发）；`alignElements.trigger` 已实测
  · 实测：工具栏 6 按钮组全渲染、预览 XML/JSON 弹窗、下载 diagram.xml、
    无选择对齐弹警告、重新绘制；后端 349 测试 0 失败

- **2026-08-25 (22)** **流程设计画布对齐 yudao/vben 连线上"+"节点添加**：
  · 画布仍用 bpmn-js；每条连线中点加"+"浮层按钮，点击弹出 10 种节点菜单
    （经办人/审批人/抄送→UserTask，条件/并行/包容分支→Exclusive/Parallel/InclusiveGateway，
    延时器→IntermediateCatchEvent，触发器→CallActivity，子流程→SubProcess）
  · 选类型后在连线中间拆分：源→新节点→目标，重算 BPMNDI 后 XML 重新导入
  · 关键取舍：bpmn-js `modeling.createShape` 在 v18.25.1 报 `BpmnOrderingProvider`
    "reading 'children'"，故改为**保存当前 XML→字符串插节点+拆边→重新 importXML**（可靠）
  · 插入后自动重建全部"+"浮层，可连续添加；保存/重载均持久化
  · 实测：节点添加 6→8 元素、浮层重建、保存成功、重载持久化；后端 349 测试 0 失败

- **2026-08-25 (21)** **修复 2 个前端 bug（对齐 yudao 模型编辑器）**：
  · 修流程图高亮 `Cannot read markers` 报错（addMarker 前校验元素在注册表）
  · 流程模型编辑器重构为 **4 Tab**：基本信息(名/Key/分类/表单类型) / 表单设计(JSON) /
    流程设计(工具栏+画布+属性面板) / 更多设置(备注)，保存整合全字段（参考 yudao model editor）
  实测 4-tab 渲染 / 有效实例流程图 7 元素无报错；构建 0 警告
- **2026-08-25 (20)** **BPM 管理套件完整落地（10 个菜单全部可访问）**：
  · 新增 4 表：用户分组/流程监听器/流程表达式/流程设置（SQLite+MySQL）
  · 通用 CRUD 服务 `bpm-mgmt` + 路由 `/business/bpm/:module`（数据驱动复用）
  · 流程实例管理 / 流程任务管理 / 流程实例运维（挂起/激活/终止 + 全部任务）
  · 前端：通用 BPM 管理 CRUD 页 + 实例/任务/运维页，10 菜单全部可访问
  · 10 菜单：流程设置/模型/表单/分类/用户分组/监听器/表达式/实例/任务/实例运维
  实测各模块 CRUD/任务列表/运维操作/页面渲染正常；构建 0 警告
- **2026-08-25 (19)** **BPM 设计器对齐参考系统 + 多级审批流程**：
  · 请假流程改为**三级审批**（部门经理→分管领导→HR），报销为二级（部门经理→财务），各级可驳回
  · 内置模型补齐 BPMNDI，修正边 ID/startEvent，实测多级审批链路 + 设计器可渲染
  · **设计器新增右侧属性面板**：选中审批节点可编辑 节点名称 + 审批人(candidateUsers)，
    应用到节点→modeling.updateProperties，保存→XML 持久化（实测 admin→admin,manager）
  · E2E 适配多级审批（单级审批后仍为审批中）。全量 E2E 41 通过 / 后端 349 测试 0 失败
- **2026-08-25 (18)** **Playwright E2E 完整通过**：新增 `business-flow.spec.js`（登录→发起请假→待办审批→
  状态变已通过 + 办公报表加载），全量 E2E **38 passed / 0 failed**。修复 2 个前端 bug：
  · `app.cljs` init 从 localStorage 同步恢复 token（否则刷新后首屏请求 401 空表）
  · `bpm_todo.cljs` 4 处订阅名与 subs 不一致（`bpm/todo-*` → `bpm-todo/*`）导致 ErrorBoundary 崩溃
  补缺失 `dom-helper.js`（修复 config-crud 模块找不到）。
- **2026-08-25 (17)** **uberjar 生产构建验证完成**：`shadow-cljs release`(5.4MB前端) + `clojure -T:build all`
  打包出 101MB 独立 jar（含 Flowable/H2/前端静态资源）。实测在干净临时目录以 prod 启动：
  健康检查、前端资产、登录、迁移+内置模型、请假流程、报表、动态菜单全部正常。
  修复 db.clj 未 require migratus.core 导致的 uberjar 编译失败。**平台可交付为独立 jar**。
- **2026-08-25 (16)** **办公报表看板**：`GET /business/report/stats` 聚合请假/报销/流程/员工/客户统计，
  前端 Statistic 卡片页（13 项指标）+ 菜单。antd.cljs 补 Statistic/Row/Col。
  修复报销总额计算。shadow-cljs 0 警告。
- **2026-08-25 (15)** **MySQL 双库验证完成**：用 brew MySQL 26.7 实测，全量测试套件在 MySQL 上
  **349 测试 / 882 断言 / 0 失败**。修复多个 SQLite-only 问题：
  · `business.sql` 22处 `datetime('now')`(SQLite-only)→`CURRENT_TIMESTAMP`(双库通用)
  · 补缺失的 MySQL 迁移 `add-notice-content`
  · 修 `obsolete-tool-menus` 迁移中文乱码 + `--;;` 分隔符
  · 修 reimburse MySQL 迁移：TEXT带DEFAULT(MySQL8拒绝)→VARCHAR/CHAR；`CREATE INDEX IF NOT EXISTS`(MySQL不支持)→去掉
  实测迁移/登录/请假/报销/HRM/OA/CRM/部署/流程图全通过。**双库兼容地基已夯实**。
- **2026-08-25 (14)** **报销模块收尾 + 关键修复**：新增 `business/util kquery` 修复所有业务列表的
  分页/过滤失效（系统 `:query-params` 是 string key，服务用 keyword 读取 → 分页/搜索一直没生效）。
  6 个业务控制器改用 `bu/kquery`。bpm 集成测试加固（兼容历史遗留流程，修 BPMN key 与 model_key 不一致 bug）。
- **2026-08-25 (13)** **报销审批模块**：复用"业务记录+BPM+前端+流程图"链路，新增 `biz_oa_reimburse`
  + 内置 `reimburseApproval` 模型(502)。发起入流/惰性状态同步/删除，前端页+路由+菜单。
  修 `active-activity-ids` 处理已结束流程（execution 不存在）。实测 发起→审批通过=2。
- **2026-08-25 (12)** **审批流程图高亮**：我的流程页"流程图"弹窗 → bpmn-js Viewer 渲染 BPMN
  + canvas.addMarker 高亮（进行中=红 / 已完成=绿）。后端 `instance-diagram` 端点返回 BPMN+节点状态，
  `bpm/core` 增加 active/completed-activity-ids。请假发起同步写 `biz_bpm_instance`。
  实测运行中实例 active=['approve'] completed=['start','f1']。
- **2026-08-25 (11)** **bpmn-js 流程建模器完成**：流程模型页新增"设计"按钮 → 弹窗 bpmn-js 在线画布
  （importXML/saveXML），保存回写模型。规避 shadow-cljs Closure 无法输出 ES2018 的限制
  （改用 UMD 外置加载 + 全局 BpmnJS）。CSS/UMD 服务到 public/vendor，0 警告。
- **2026-08-25 (10)** **前端页面完成**：办公目录 9 个业务模块全部有 Reagent/antd 页面
  （请假申请/我的待办+审批/我的已办/我的流程/流程模型+部署/员工/日程/会议/客户）。
  shadow-cljs 0 警告，后端服务新 bundle，动态菜单可导航。
- **2026-08-25 (9)** **请假申请 + BPM 审批流集成（旗舰示例）**：`biz_oa_leave` 业务表 + 内置
  `leaveApproval` 流程模型。发起请假→入 Flowable 审批流→审批通过/驳回后状态自动同步
  （已通过=2 / 已驳回=3）。REST 实测通过，证明“业务记录 + 工作流”集成模式。
- **2026-08-25 (8)** **全量测试套件转绿**：修复 obs 既有测试问题（`*trace*` 符号/结构错误/spans 多余括号
  /`replay` diff 逻辑/异常类名等），修复应用生命周期 bug（`stop-app` 停后重置 atom，解决多次
  start/stop 复用已关闭 HikariCP），`test_utils/system-state` 修正。结果 **349 测试 / 881 断言 / 0 失败**。
- **2026-08-24 (7)** CRM 客户模块完成；办公菜单含 BPM/HRM/OA/CRM；BPM REST 集成测试（15 断言全过）。
- **2026-08-24 (6)** OA 日程+会议模块、HRM 员工模块完成并实测；办公菜单含 BPM/HRM/OA。
  全量冒烟测试（BPM+HRM+OA）通过。BPM 单元测试 22 断言全过。
- **2026-08-24 (5)** BPM 动态菜单 + HRM 员工模块（`biz_hrm_employee`）。
- **2026-08-24 (4)** BPM 后端垂直切片完成并 REST 实测：分类→模型→部署(Flowable)→发起→待办→审批/驳回→已办→历史 全链路通过。
  新增 `bpm/core.clj` 高层 API、`domain/business/bpm.clj`、`controllers/business/bpm.clj`、`routes/business.clj`、
  `sql/business.sql`、`biz_bpm_*` 迁移与动态菜单。
- **2026-08-24 (3)** BPM 核心 API 完成并 nREPL 实测：`bpm/core.clj`（部署/发起/待办/审批/驳回/转办/历史）。
- **2026-08-24 (2)** Phase 0 集成完成并实测：Flowable 8.0.0 + H2 1.4.200 已加入 deps.edn，
  `bpm/engine.clj` 以 `:app.bpm/engine` Integrant 组件启动（独立 `./flowable.db`，
  async 默认关），system.edn + core.clj 已接线。nREPL 实测部署 BPMN、发起流程、
  查询待办任务成功。
- **2026-08-24** 初版。完成 Flowable 可行性 spike（JDK 26 + H2 1.4.200 端到端跑通），
  确定路线 2、数据库双轨架构、模块取舍清单，输出数据模型与 API 设计框架。
