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

## Changelog

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
