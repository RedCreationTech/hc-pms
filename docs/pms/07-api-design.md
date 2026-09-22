# API 设计 V1

基础路径 `/api/pms`. JSON字段使用 snake_case, `options.currentUserId` 为首批兼容例外. 复用模板 Bearer JWT. 不在浏览器构造管理员身份或信任请求中的 `created_by`.

## 公共语义

```json
{"code":200,"msg":"操作成功","data":{"rows":[],"total":0}}
```

列表使用 `page` 从1开始且不超过1,000,000, `size` 为1..100; 空列表返回 rows 空数组. 错误同时设置真实HTTP状态和同值code. 400为字段或日期错误, 401为未登录, 403为缺功能权限或项目访问权限, 404为对象不存在, 409为重复/版本冲突/状态冲突, 500为不泄露SQL和堆栈的内部错误.

UUID标识作为字符串处理. 写命令白名单提取字段. 项目编辑与状态转换要求当前 `version`, 禁止客户端直接修改状态,创建者或审计数据. 请求成功后重新读取对象, 不以本地乐观显示替代服务端结果.

## 首批接口

| 方法与路径 | 输入 | data | 功能权限 |
|---|---|---|---|
| GET /dashboard | 无 | total, active, overdue, draft | pms:dashboard:query |
| GET /options | 无 | users, depts, currentUserId | project:list/query/add/edit或member:edit之一 |
| GET /projects | page,size,q,status | rows,total | pms:project:list |
| POST /projects | 项目可写字段 | 新项目 | pms:project:add |
| GET /projects/:id | UUID | 项目 | pms:project:query |
| PUT /projects/:id | 项目字段 + version | 更新后项目 | pms:project:edit |
| POST /projects/:id/transition | status,version,reason | 更新后项目 | pms:project:transition |
| GET /projects/:id/nodes | UUID | rows,tree | pms:project:query |
| POST /projects/:id/nodes | parent_id,node_type,node_code,name | 新节点 | pms:node:add |
| GET /projects/:id/members | UUID | rows | pms:project:query |
| POST /projects/:id/members | user_id,role | 成员结果 | pms:member:edit |
| GET /projects/:id/events | UUID | rows | pms:project:query |

以上权限之外还必须通过项目范围检查. 具有功能权限但不属于该项目的用户不能查看其详情,树,成员,事件或统计. 项目经理或manager/editor成员可写, 同时必须拥有对应功能权限; 创建者默认获editor成员资格, 仅创建者身份本身不自动授予写权限. 前端隐藏按钮只是交互便利, 不能替代后端检查.

### 创建项目

```json
{
  "project_no":"PMS-2026-001",
  "name":"示例装备交付项目",
  "customer":"示例客户",
  "contract_no":"HT-2026-001",
  "project_type":"equipment",
  "manager_id":1,
  "dept_id":100,
  "start_date":"2026-09-22",
  "end_date":"2026-12-31"
}
```

manager_id/dept_id 必须来自真实有效的 options 结果, 示例数字不能用于假定任何环境都存在. 返回包含 project_id,status,version,manager_name,dept_name. 创建时自动产生主节点, 由服务器确定创建者.

### 项目结构

```json
{"parent_id":"<main-node-id>","node_type":"sub","node_code":"BU-01","name":"装备事业部交付"}
```

创建单机时 parent_id 指向 sub, node_type 为 machine. 同项目节点编号唯一. API不允许客户端创建main或引用其他项目父节点.

### 状态与版本

```json
{"status":"initiated","version":1,"reason":"完成项目基础信息登记"}
```

首批允许 draft -> initiated -> planning; 非终态可取消并填写理由. `planning -> execution` 暂不开放, 返回409提示等待计划基线和Gate能力. 首批立项仅为项目业务登记, 不是质量批准或已接入OA审批. 任何不允许的跳转不得写入事件.

### 指标口径

- total: 当前用户有权查看的项目数量.
- active: 尚未关闭或取消的非草稿项目数量.
- overdue: 未关闭/取消且计划结束日早于组织当前日期的项目数量.
- draft: 当前可见草稿数量.

无计划结束日的项目不判定为逾期. 当前未计算挣值, 健康指数或预测延期天数, 页面不得填造这些指标.

## 后续接口族与命令边界

以下为设计契约, 当前不会返回假成功. 实现时需增加 schema/OpenAPI 与对应权限迁移.

| 领域 | 查询接口 | 写命令及输入 | 成功前提 / 失败结果 |
|---|---|---|---|
| 计划 | GET /projects/:id/plans, /plans/:id/revisions | POST /plans, /plans/:id/revisions, /revisions/:id/submit-baseline | 同项目任务,有效日历,无依赖环; 环或旧版409 |
| WBS | GET /revisions/:id/tasks | POST /tasks, PUT /tasks/:id, POST /tasks/:id/progress | 草稿可改计划, 执行进度另记事实; 状态/时间不合规400 |
| Gate | GET /projects/:id/gates, /gates/:id/checks | POST /gates/:id/submit, /gates/:id/decisions | 证据快照,必检项,审批权限; 缺证据409 |
| URS | GET /projects/:id/requirements, /traceability | POST /requirements, /requirements/:id/verifications | 受控需求版本,验证方法,文件修订; 跨项目404 |
| 偏差 | GET /projects/:id/deviations | POST /deviations, /deviations/:id/close | 责任人,严重度,处置和关闭证据; 关键偏差阻Gate |
| 文档 | GET /projects/:id/documents | POST /documents, /documents/:id/revisions, /revisions/:id/issue | 先上传检查再登记, 签发版本不可覆盖 |
| 成本 | GET /projects/:id/cost-sheets?kind=budget | POST /cost-sheets, /cost-sheets/:id/approve, /allocations/:id/run | 同币种精度,来源唯一,规则版本和授权 |
| 风险/问题 | GET /projects/:id/risks, /issues | POST /risks, /risks/:id/materialize, /issues/:id/close | 风险转问题保留来源, 关闭需证据 |
| 变更 | GET /projects/:id/changes | POST /changes, /changes/:id/submit, /changes/:id/apply | 影响评估和批准后产生新基线 |
| 集成 | GET /integrations/:system/runs | POST /integrations/:system/events, /runs/:id/retry | source/event_id幂等, 签名/认证, 来源版本校验 |
| AI | GET /ai/runs/:id, /suggestions | POST /projects/:id/ai/analysis, /suggestions/:id/accept | 只产草稿, 接受动作仍调用普通领域权限和校验 |

后续异步任务返回202与run_id及状态查询路径, 不能在请求内无限等待. 计划批准, Gate决定, 外部事件和AI建议采用 `Idempotency-Key` + 主体/动作/请求hash幂等; 重复相同payload返回原结果, 同key不同payload为409. 首批创建接口依赖项目编号唯一约束防重复, 尚未实现通用幂等键.

## 分页与扩量

首批项目列表分页. 结构树/成员/事件用于试运行规模, 扩量时节点分支按需加载, 事件使用 `(created_at,event_id)` 游标分页. 外部批量同步按来源游标分页并记录成功检查点, 重试不能跳过失败记录.
