# BPM 生命周期盘点与补全清单 (2026-10-06)

用户诉求: "BPM 功能全部验证完整, 每个功能生成工作证据报告, 演示视频展示完整功能 (目前视频太短, 必须覆盖完整生命周期: 建模→发起→审批→跟踪→作废 等), 每个功能使用真实浏览器操作截图 (非 mock), 验证迁移脚本和权限配置正确执行."

本文档盘点 BPM 侧现状与缺口, 后续 #720 章节扩录屏, #721 隔离录制, #722 每功能证据报告, #723 交付.

---

## 一, 后端能力盘点 (已完整, 无需新增)

`src/clj/com/ruoyi/web/routes/business.clj` 声明 60+ BPM/OA 路由, 覆盖:

| 分组 | 路由要点 | 权限 |
|------|----------|------|
| 建模 | `/bpm/model` 列表/详情/新建/更新/删除/发布/复制, `/bpm/model/:id/xml` BPMN 源, `/bpm/model/:id/bpmn` 部署 | `bpm:model:list/edit/add/remove/deploy` |
| 定义 | `/bpm/definition/page`, `/bpm/definition/xml/:id`, `/bpm/definition/form/:id`, `/bpm/definition/state/:id` (挂起/激活), `/bpm/definition/history/:key` | `bpm:model:list` |
| 实例 | `/bpm/instance` 分页 (登录即可, 无管理权限时只返回本人), `/bpm/instance/history/:pid`, `/bpm/instance/diagram/:pid` (高亮图), `/bpm/instance/print-data` | `:login` |
| 任务 | `/bpm/todo`, `/done`, `/task/:id/detail/approve/reject/transfer/delegate/add-sign/remove-sign/return/copy/withdraw/withdraw-to-start/sign-list/return-list` | `:login` |
| 运维 | `/bpm/instance/cancel` (发起人或管理员), `/bpm/instance/:pid/suspend/activate/terminate` | `bpm:instance:suspend/activate/delete` |
| 分类 | `/bpm/category` CRUD | `bpm:category:list/add/edit/remove` |
| 表单 | `/bpm/form` CRUD, `/bpm/form/:id` 详情 | `bpm:form:list/add/edit/remove` |
| 表达式/监听器/用户组 | `/bpm/expression`, `/listener`, `/user-group` CRUD | `bpm:expression/listener/user-group:list/...` |
| 设置 | `/bpm/settings` GET/PUT | `bpm:settings:list/edit` |
| OA | `/oa/leave` POST 发起请假 (自动进 BPM 流), GET 列表/详情, DELETE; `/oa/reimburse` 同 | `oa:leave/reimburse:list/add/remove` |
| HRM/CRM/报表 | `/hrm/employee`, `/crm/customer`, `/report/summary` | `hrm/crm:employee/customer:list/...` |

**结论**: 后端 API 已完整覆盖用户提到的完整生命周期 (建模→发布→发起→审批→跟踪→作废). 无新增后端能力需求.

---

## 二, 迁移与权限盘点 (本轮已验证)

### 已落地的菜单/按钮 (SQLite `resources/migrations-sqlite/`)

| 迁移 ID | 内容 | 目标角色 |
|---------|------|----------|
| 202608240002-add-bpm-menus | 30 办公 (M) / 31 流程管理 (M) / 32 流程模型 (C) / 33 我的流程 (C) / 34 我的待办 (C) / 35 我的已办 (C); 按钮 300-305 (category:add, model:deploy, instance:start, task:approve/reject/transfer) | admin (1) |
| 202608250007-add-bpm-admin-menus | 43-51 (form/category/user-group/listener/expression/instance-manager/task-manager/instance-ops/settings); 按钮 320-327 | admin (1) |
| 202609180001-bpm-phase1-copy | 52 抄送我的 (C, perms `bpm:copy:list`); 按钮 328-330 | admin (1) |
| 202610050003-grant-office-selfservice-common | 授予 common (2): 菜单 40 请假申请 + 按钮 314 (发起请假) + 菜单 41 报销申请 + 按钮 315 (发起报销) | common (2) |

种子库还预置了: 36 报板? (实际未查), 37 日程管理 (oa:calendar:list), 38 会议管理 (oa:meeting:list), 39 HRM, 42 CRM 等. 具体见 `sys_menu` 查询结果 175 行.

### 本轮实测证据 (隔离 :3100, 独立 /tmp/hcpms-bpm.db)

```
$ POST /api/auth/login admin/admin123 → 200, token.
$ POST /api/system/user {user_name:"bpmemp_ok", roles:[2], dept_id:103} → 200, user_id=3
$ sqlite3 SELECT role_id FROM sys_user_role WHERE user_id=3 → 2 ✓
$ POST /api/auth/login bpmemp_ok/Bpm-2026 → 200.
$ 使用 EMP_TOKEN:
  /api/business/oa/leave       → HTTP 200  ✓ (202610050003 grant 生效)
  /api/business/oa/reimburse   → HTTP 200  ✓
  /api/business/bpm/todo       → HTTP 200  ✓
  /api/business/bpm/done       → HTTP 200  ✓
  /api/business/bpm/instance   → HTTP 200  ✓
  /api/business/bpm/task/copy/page → HTTP 200 ✓
$ POST /api/business/oa/leave (发起请假) → 200, {leave-process-instance-id:"5", business-key:"leave-1791246760718", model-key:"leaveApproval"}
$ POST /api/business/oa/reimburse → 200, {process-instance-id:"20", business-key:"reimburse-...", model-key:"reimburseApproval"}
```

**结论**: 迁移脚本执行正确, 权限配置生效, 员工自助闭环可用. (此前"403" 是我探测时误把 `role_ids` 当 payload key, API 实际字段是 `roles`, 与 RuoYi-Vue 参考实现一致. 记录进 memory.)

### 已知缺口 (本轮补迁移)

- **发起流程页** (`/office/bpm/start` → `business/bpm/start/index`, `bpm:instance:start`) 无 C 型菜单. 目前只有 302 按钮 (parent 33 我的流程) 提供入口. 建议补菜单以匹配参考实现.
- **流程定义页** (`/office/bpm/definition` → `business/bpm/definition/index`, `bpm:model:list`) 无 C 型菜单. 只有 `model-deploy` 后端路由.

新增迁移 (见 `202610060001-add-bpm-start-definition-menus.{up,down}.sql`, SQLite + MySQL 双库):
- 53 发起流程 C 型, path=bpm/start, parent=31, perms=bpm:instance:start, component=business/bpm/start/index
- 54 流程定义 C 型, path=bpm/definition, parent=31, perms=bpm:model:list, component=business/bpm/definition/index
- role_id=1 (admin) 授予两菜单; role_id=2 (common) 授予 53 (发起流程) — 让员工也能自助进入发起页.

---

## 三, 现有录屏覆盖 (scripts/demo-video/storyboard-bpm.js)

现有 6 章 / 17 镜头:

| 章节 | 镜头 | 覆盖 |
|------|------|------|
| 01 流程建模与设计 | 01-1..01-4 | 模型列表, 设计器渲染, 节点配置抽屉, 发布→运行时定义 |
| 02 请假审批链 | 02-1..02-5 | 员工发起 → 部门经理 → 分管领导 → HR → 员工查看已通过 |
| 03 报销审批链 | 03-1..03-3 | 员工发起 → 部门经理 → 财务结束 |
| 04 OA 日历与会议 | 04-1..04-2 | 新建日程, 预定会议 |
| 05 HRM/CRM | 05-1..05-2 | 新建员工, 新建客户 |
| 06 报板 | 06-1 | 报板汇总 |

成片约 3:02 (BPM 段). 用户明确指出 "太短, 必须覆盖完整生命周期".

---

## 四, 缺口清单 → 待补章节

按用户诉求 "建模→发布→发起→审批→跟踪→作废", 补以下章 (每章 2-4 镜, 全部真实浏览器操作, 无 mock):

### 07 流程实例跟踪 (bpm_instance)
- 07-1: admin → 我的流程 → 列表带 business_key/发起人/状态/当前节点, 点"详情".
- 07-2: 实例详情页 → 高亮流程图 (bpmn_viewer + 已完成节点绿色 + 当前节点蓝色).
- 07-3: 历史轨迹时间轴 (提交→审批→通过) + 打印视图.

### 08 已办与抄送我的 (bpm_done + bpm_copy)
- 08-1: 审批人"已办"列表, 点某行进入详情, 显示当时提交的表单+意见.
- 08-2: 抄送我的分页 (bpm/copy) 显示被 cc 的任务.

### 09 流程运维 (bpm_ops, admin only)
- 09-1: 实例运维页 → 挂起某实例 → 状态翻 "suspended".
- 09-2: 激活同实例 → 回到 running.
- 09-3: 终止同实例 → 状态翻 "terminated", 显示终止原因.

### 10 复杂审批 (驳回/转办/委派/加签/减签/撤回/退回)
- 10-1: 部门经理驳回 → 员工收到待办回到自己.
- 10-2: 部门经理转办给另一个用户 → 目标用户待办出现.
- 10-3: 部门经理加签给 HR → 子任务显示.
- 10-4: 部门经理撤回自己已办 → 回到"待办".

### 11 表单设计器 (form_designer)
- 11-1: 新建流程表单 → 拖字段 (文本/日期/下拉) → 保存.
- 11-2: 表单绑定到模型 → 员工发起时看到自定义字段渲染.

### 12 模型/分类/定义 CRUD
- 12-1: 分类管理新建 → "行政类".
- 12-2: 模型列表按分类过滤.
- 12-3: 复制现有 leaveApproval → new key → 编辑.

以上章节合计约 20+ 镜头, 预计扩录后总 BPM 段约 6-8 分钟, 满足"完整生命周期".

---

## 五, 交付计划

1. **#719** (本项): 迁移+权限正确性已实测通过 (见证据段). 补 53/54 菜单迁移 (SQLite + MySQL, 每条语句 `--;;`).
2. **#720**: 扩 storyboard + spec, 覆盖 07-12 六章, 每镜真实点击/输入.
3. **#721**: 隔离 :3100 冷启动 + 全量录屏 + compose 新 mp4 (5-8 分钟, 带声音).
4. **#722**: 每功能一份证据报告 (workspace `outputs/bpm-evidence/<feature>/report.md/html` + 真实截图 + HTTP 请求/响应片段 + 迁移 ID + 权限项 + Playwright 断言).
5. **#723**: 提交 → ff main → 推送 → present_files → 清理.
