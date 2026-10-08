# 接口运维与受控重开合同

所有路径以 `/api/pms/projects/:id` 为前缀. 所有写入携带当前 `version`, 标准信封返回 `data.result` 和 `data.project_version`. 没有实际企业沙箱的连接器不标记已联通; 当前提供通用消息封装和可验证的HTTP交付协议, 企业字段适配仍待真实合同.

## 接口工作台

GET `/integration` 需 `pms:integration:query` 和项目范围, 返回 `project_version, connectors, inbox, facts, outbox, attempts, reconciliation`.

- connectors: `{system, configured}`. 系统枚举 crm/oa/erp/plm/mes/srm/sales_material/bi, ERP包含SAP部署. 不向UI提供URL或凭据.
- inbox/facts/outbox 只返回元数据和payload_hash, 不包含财务或文档正文. 所有行 `id` 对应 `message_id` 或 `fact_id`.
- reconciliation: received/applied/ignored/queued/delivered/unconfirmed. queued是累计外发消息数,未确认由unconfirmed表示; 同时可看每条状态.
- attempts: attempt_id/message_id/attempt_no/status/http_status/receipt_id/receipt_hash/error_code/started_at/completed_at.

POST `/integration/inbox`: `source,event_id,entity_type,external_key,source_revision,data`. `entity_type` 为 order/material/field_progress/cost, data为非空JSON对象<=64KiB; 需integration:receive. 按来源事件幂等,同ID不同内容409;同对象低版本保留ignored,高版本更新来源事实投影. 投影不是PMS内部批准,不能直接改变任务/成本/审批状态.

POST `/integration/outbox`: `target,topic,source_id,idempotency_key`,需integration:edit. topic仅 `plan.published`/`cost.approved`/`document.registered`. source_id必须同项目已批准基线ID/已批准成本version_id/已登记真实document id. 正文从服务端固定源生成,不能由客户端伪造. 成本主题另需finance:query.

GET `/integration/outbox/:message_id` 返回元数据+payload,成本内容另需finance:query.

POST `/integration/outbox/:message_id/deliver`: 仅version,需integration:deliver. 未配置返回503且不假装成功. 成功事务先登记发送尝试,网络执行后记录实际回执. 只有响应 `{"accepted":true,"message_id":"原消息UUID","receipt_id":"远端回执号"}` 且HTTP2xx才标delivered. 其他为retry_wait,按2^attempt秒退避(上限300秒),每周期最多5次后dead_letter. 投递动作由操作员明确执行,当前无后台自动轮询.

POST `/integration/outbox/:message_id/retry`: `reason`,需integration:deliver. retry_wait/dead_letter人工启动新重试周期,发送租约超30秒才可恢复sending. 重试沿用原消息UUID与Idempotency-Key,保留全部尝试. 外部接收方必须实现这个键的幂等,不宣称跨系统恰好一次. 进程中断的旧尝试标indeterminate并保留.

部署 `PMS_CONNECTORS_JSON` 示例为 `{"crm":{"url":"https://sandbox.example.org/pms/events","token_env":"PMS_CRM_TOKEN"}}`. token_env引用部署密钥变量,不提交真实值. 仅测试可显式allow_loopback启用本地HTTP. 禁重定向,连接超时3秒/总请求10秒,回执<=64KiB. 发送前请按真实系统签订字段及回执合同.

## 受控重开

GET `/closure` 额外返回 `reopen_request` (最近一次申请,包含request_id/status/submitted_by/reviewer_id/reason/scope).

POST `/reopen-requests`: `reason,scope,reviewer_id`,仅closed项目,需project:reopen和项目编辑范围. reviewer为有效项目成员且不同于申请人.

POST `/reopen-requests/:request_id/review`: `decision:approved/rejected,reason`,需project:reopen,项目阅读范围和指定独立审批人. 批准后项目回到closing,保留历史关闭快照,清除当前归档标记. 必须对重开后依据重新提交关闭审批,旧关闭决定不能直接复用. cancelled不支持自动重开.

## 外部接口配置目录 (平台级只读, 免持久化)

面向集成配置人员的平台级只读目录页, 与项目无关, 路径前缀 `/api/pms` (非 `/api/pms/projects/:id`).

GET `/external-interfaces` 仅需登录 (`auth-middleware {:required? true}`, 未声明 `:perms`, 未登录 401), 返回信封 `data` 为 `{available, total, adapter-count, business-count, contract-count, rule-count, by-status, by-system, rows, note}`.

- 目录为随代码发布的静态清单 (`domain/pms/external-catalog/capabilities`), 读取时不查库, 不落库, 不做连通性测试, 不代表任何真实集成已通过. 免迁移新增数据表, 仅新增一条 C 型菜单种子 (外部接口配置, path `external-interfaces`, component `pms/external-interfaces/index`).
- `total` 恒等于 `rows` 数 (当前 22); `adapter-count` (系统适配器, 当前 9) + `business-count` (业务对接点, 当前 13) = `total`; `contract-count` (`待合同`, 当前 20) + `rule-count` (`待规则`, 当前 2) = `total`; `by-status` 与 `by-system` 的 `count` 之和各自等于 `total`. 计数字段命名一律 kebab 直穿 JSON.
- 每条 `rows` 含 `{key, capability, system, group, matrix-rows, direction, required-fields, owner, status, notes}` 十字段: `key` 稳定唯一标识, `matrix-rows` 来源验收矩阵行 (逗号分隔, 匹配 `[0-9A-Z]{2,4}(, ?[0-9A-Z]{2,4})*`), `required-fields` 为需外部提供的接口字段占位清单 (非空, 逐项非空白), `status` 受控取值 `#{待合同 待规则}` 与验收矩阵依赖状态一致, `owner` 为待确认责任方. `required-fields` 只是"对接前需准备什么"的清单, 不承诺字段合同已签订.
- 前端 `pages/pms/external_interfaces.cljs` 独立只读页 (路由 `pms/external-interfaces`, page key `:pms-external-interfaces`, 面包屑"首页/项目管理/外部接口配置"), 顶部五项指标卡 + "依赖外部系统的能力清单"表格; 页面标注只读性质, 不投递提醒, 不做任何写操作.
- 本目录只登记依赖状态, 不实现真实适配器; 八套企业系统适配器与业务对接点仍属 `planned / 待合同` 或 `planned / 待规则`, 不允许用本清单或界面冒充真实集成通过. 键名无尾随问号.
