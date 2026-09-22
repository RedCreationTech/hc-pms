# 工时,四算与收尾合同

所有路径以 `/api/pms/projects/:id` 为前缀. 复用实时JWT身份,功能权限与项目范围. 所有写命令包含当前项目 `version`,响应 `data.result` 与 `data.project_version`. 陈旧版本409,未授权403,所有写入与审计同事务.

## 工时

GET `/time-entries`: project:query,仅本人工时/待本人审核工时,管理员可见全部. 不附带成本金额.

POST `/time-entries`: project:edit,项目必须execution;字段task_id/work_date/hours/note/reviewer_id. 必须当前项目非汇总任务,实际日期不能晚于今天,审核人为另一个有效项目成员. 小时最多两位小数且必须恰好换成完整分钟. 整数分钟持久化,跨项目同人同日提交及批准工时总和不超过24小时. 事务预留容量,驳回释放.

POST `/time-entries/:entry_id/review`: time:approve,独立指定审核人,decision为approved/rejected,拒绝须reason. 已处理决定不能覆盖. 当前不提供批准工时的追溯更正或封期,需作为下一项财务规则扩展,不能以重新报工假装冲销.

## 四算

GET `/finance`: finance:query与项目阅读范围,返回cost_versions/time_entries/allocations/summary/summary_context. 普通项目成员无此权限不能查询金额. summary只比较同一期间及币种的最新已批准口径,缺失口径不填伪零. 当前margin字段为收入减成本的毛利金额,零收入也可计算负毛利;不伪称利润率或税后净利.

POST `/cost-versions`: finance:edit,kind为estimate/budget/actual/settlement;字段period(YYYY-MM),currency(CNY/USD/EUR/GBP/HKD),name,revenue,reviewer_id. 版本号按项目/口径/期间递增. 金额最多两位小数,按整数最小货币单位存储,不做隐式换汇.

POST `/cost-versions/:cost_id/entries`: category(material/labor/manufacturing/travel/other/change_loss),label,amount,可选source_ref. 相同版本同来源唯一,允许负数调整及明确零费用确认. 只有draft可编辑. DELETE `/cost-versions/:cost_id/entries/:entry_id` 仅删除草稿中非分摊生成项.

POST版本路径的 `/submit` 冻结快照; `/review` 需finance:approve且指定非提交者,字段decision/reason; `/revise` 字段reviewer_id,从approved/rejected复制新草稿保留旧版; `/cancel` 字段reason,仅draft/rejected可取消. 不直接覆盖已批准账目. 通用项目审计仅保留财务事件类型/对象/状态,审批意见保存在有财务授权的成本模型.

POST `/cost-versions/:cost_id/allocate`: amount/from_date/to_date/idempotency_key/label. 仅当前费用期间内已批准工时参与,按任务分钟权重用最大余数法精确分摊,差额确定地分给最高余数任务,总额守恒. 没有批准工时409. 输入工时ID/金额/版本/输出结果与SHA256持久化,重复幂等键同内容返回旧结果,不同内容409. 生成的labor条目不可单独删除. 当前是单项目费用池到任务的分配,不声称完成跨项目共享研发池或人工费率有效期核算.

## 收尾和关闭

GET `/closure`: project:query,返回checks/handoffs/lessons/approval/reopen_request/blockers/ready/project_version. 不包含成本金额或批准快照正文. ready只表示业务材料齐备,最终closed仍须独立批准.

POST `/closure/checks`: title,required(boolean). POST `/closure/handoffs`: title,owner_id,due_date,required. 至少一个必需清单,所有移交必须完成. POST对应集合的 `/:item_id/complete`: evidence_ref(同项目真实文档版本ID),comment. 移交必须由指定接收人亲自确认. POST `/closure/lessons`: title/category/content.

项目execution->closing要求叶级任务全部done. POST `/closure/submit` 需reviewer_id并且项目closing,清单/移交全部完成,交付链实际齐套/装配/适用试验/签收/售后已完成,required closure Gate通过且无blocker问题,已有批准决算且无费用草稿/待审工时.

POST `/closure/review`: project:close,指定独立审核人,decision/reason. 审批固定当前任务/计划/治理/交付/财务/收尾内容快照与SHA256. `/transition` 到closed时重新计算缺口及摘要;内容变化则必须重新提交. 归档时间持久化,终态只读,重开合同见integration.md. 暂停保留原状态;恢复还原已获准状态,新的跨项目资源冲突继续在计划工作台提示并待协调.

## 验证边界

真实SQLite/MySQL和浏览器的本轮结果以verification.md为准. 测试包括整数金额/余差守恒/零工时/独立审核/同源幂等/审计失败回滚/成员撤权/实际物料至签收至关闭/关闭快照失效/独立重开. 模块不代替ERP财务总账,不宣称已接收真实收入/回款/税额合同或生产客户数据.
