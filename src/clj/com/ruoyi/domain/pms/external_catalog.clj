(ns com.ruoyi.domain.pms.external-catalog
  "外部接口配置目录 (只读): 逐项登记仍依赖真实外部系统合同/规则的能力, 供集成配置人员对照准备字段, 认证, 责任人与测试环境.
   本目录为随代码发布的静态清单, 读取时不查库, 不落库, 不做连通性测试, 不代表任何真实集成已通过; 每条能力给出所需接口字段与当前依赖状态 (待合同 / 待规则).")

(def ^:private capability-statuses
  "受控的依赖状态取值, 与验收矩阵保持一致."
  #{"待合同" "待规则"})

(def capabilities
  "全部依赖外部系统的能力清单. 键义: :key 稳定标识, :capability 能力名, :system 外部系统归类, :group 归类 (adapter 系统适配器 / business 业务对接点),
   :matrix-rows 来源矩阵行, :direction 数据方向, :required-fields 需外部提供的接口字段 (占位清单), :owner 责任方, :status 依赖状态, :notes 说明."
  [{:key "crm-adapter" :capability "CRM 适配器" :system "CRM" :group "adapter"
    :matrix-rows "G07, F01, B04" :direction "双向"
    :required-fields ["订单号" "订单行" "概算版本/币种/口径" "交期" "现场事实" "发布计划引用" "交底引用" "外部业务键" "事件版本" "幂等键" "对账回执"]
    :owner "待确认 (CRM 系统负责人)" :status "待合同"
    :notes "对接订单/概算/交期/现场事实并接收发布计划与交底引用, 外部键/版本/回执可对账."}
   {:key "oa-adapter" :capability "OA 适配器与组织同步" :system "OA" :group "adapter"
    :matrix-rows "G08, A05, D01, D02, D03, D04, D05, B17, E06" :direction "双向"
    :required-fields ["组织主数据" "人员主数据" "审批业务键" "OA 真实审批结论" "停用人员事件" "拒绝/撤回事件"]
    :owner "待确认 (OA/HR 系统负责人)" :status "待合同"
    :notes "组织/人员及各申请审批同步, PMS 发起带业务键, 仅 OA 真实结论才改变审批投影."}
   {:key "erp-adapter" :capability "ERP/SAP 适配器" :system "ERP" :group "adapter"
    :matrix-rows "G09, A06, B04, D07, F02, F03" :direction "双向"
    :required-fields ["项目结构" "物料主数据" "物料匹配算法结果" "采购需求/订单" "成本账项" "预算科目" "出站批准计划" "出站现场任务" "字段权威矩阵"]
    :owner "待确认 (ERP/SAP 系统负责人)" :status "待合同"
    :notes "外部项目结构/物料/采购/成本入站, 批准计划/现场任务出站, 需字段权威矩阵明确各方主数据."}
   {:key "plm-adapter" :capability "PLM 适配器" :system "PLM" :group "adapter"
    :matrix-rows "G10" :direction "双向"
    :required-fields ["设计任务下发" "设计进度反馈" "受控交付件引用+版本" "权威文件标识"]
    :owner "待确认 (PLM 系统负责人)" :status "待合同"
    :notes "设计任务下发, 进度反馈与交付物归档引用带版本, PMS 不得覆盖 PLM 权威文件."}
   {:key "mes-adapter" :capability "MES 适配器" :system "MES" :group "adapter"
    :matrix-rows "G11, E01, E02, E03" :direction "双向"
    :required-fields ["批准的装配任务" "批准的测试任务" "工单" "齐套事实" "装配回传" "测试回传" "相关发货事实" "重试幂等键"]
    :owner "待确认 (MES 系统负责人)" :status "待合同"
    :notes "接收批准的装配/测试任务, 回传工单/齐套/装配/测试/相关发货事实, 重试不重复下发."}
   {:key "srm-adapter" :capability "SRM 适配器" :system "SRM" :group "adapter"
    :matrix-rows "G12, D07" :direction "双向"
    :required-fields ["供应商承诺日期" "交付进度" "采购订单关联" "直发调整" "失败补偿事件" "人工处理入口"]
    :owner "待确认 (SRM 系统负责人)" :status "待合同"
    :notes "供应商承诺/交付进度与采购订单关联, 直发调整协调, 失败可补偿和人工处理."}
   {:key "sales-service-adapter" :capability "销服物料系统适配器" :system "销服" :group "adapter"
    :matrix-rows "G13, E07" :direction "入站"
    :required-fields ["装箱清单+版本" "装车清单+版本" "缺件信息" "相关入库信息" "每日更新" "字段权威确认"]
    :owner "待确认 (销售服务系统负责人)" :status "待合同"
    :notes "装箱/装车/缺件/相关入库信息回传并保留清单版本, 与 MES 发货来源冲突可解释."}
   {:key "bi-adapter" :capability "BI 适配器" :system "BI" :group "adapter"
    :matrix-rows "G14, F07" :direction "双向"
    :required-fields ["经确认的变更损失入站" "分析数据入站" "授权指标出站" "数据模型/口径" "取数时点"]
    :owner "待确认 (BI/财务分析负责人)" :status "待合同"
    :notes "经确认的变更损失/分析数据入站, 授权指标按约定出站, 模型/口径和取数时点可追溯."}
   {:key "integration-ops" :capability "API 管理, 消息通知与集成运维" :system "集成运维" :group "adapter"
    :matrix-rows "G15, C11" :direction "双向"
    :required-fields ["入出站认证映射" "接口版本" "限流策略" "inbox/outbox 去重键" "重试/死信/重放" "对账服务等级" "运维动作权限审计"]
    :owner "待确认 (集成平台负责人)" :status "待合同"
    :notes "通用协议/重试/死信基础已在本地实现, 八套企业系统认证映射及业务回执仍待真实验证."}
   {:key "presales-handover" :capability "合同/售前资料交接与产品线资料检查" :system "CRM/OA" :group "business"
    :matrix-rows "A04" :direction "入站"
    :required-fields ["适用产品线应交文件清单" "缺资料退回事件" "已批准立项申请关联"] :owner "待确认 (售前/产品线负责人)" :status "待合同"
    :notes "按适用产品线校验应交文件, 缺资料退回, 完整资料随已批准立项申请关联项目."}
   {:key "auto-project-provision" :capability "外部立项审批与订单建立后自动建项目" :system "OA/CRM/ERP" :group "business"
    :matrix-rows "A05" :direction "入站"
    :required-fields ["审批+订单建立事实回执" "项目/结构/基本团队初始化" "重复消息去重" "缺字段隔离与恢复"] :owner "待确认 (PMO/集成负责人)" :status "待合同"
    :notes "审批及订单建立事实到达后建项目/结构/团队; 重复消息不重复建, 缺字段隔离并可恢复."}
   {:key "order-line-ingest" :capability "订单/订单行接收与物料匹配任务" :system "ERP" :group "business"
    :matrix-rows "A06" :direction "双向"
    :required-fields ["真实订单行" "PM 待办生成" "ERP 匹配结果回传" "错误物料案例"] :owner "待确认 (ERP/制造负责人)" :status "待合同"
    :notes "订单导入生成 PM 待办, 在 ERP 完成匹配后回 PMS 确认并留来源; 不假设 PMS 已有 ERP 匹配算法."}
   {:key "plan-baseline-sync" :capability "主计划发布与外部同步" :system "ERP/CRM/研发计划" :group "business"
    :matrix-rows "B04" :direction "出站"
    :required-fields ["不可变发布基线" "同步回执" "部分失败重试对账"] :owner "待确认 (计划/集成负责人)" :status "待合同"
    :notes "本地基线冻结与独立批准已完成, 仅发布版同步 ERP/CRM/研发计划及回执仍待真实合同."}
   {:key "handover-crm-push" :capability "项目交底资料签交并推 CRM" :system "CRM" :group "business"
    :matrix-rows "B14" :direction "出站"
    :required-fields ["发货到截止日计算" "配置期限" "版本签交" "文件清单推 CRM 回执"] :owner "待确认 (交付/CRM 负责人)" :status "待合同"
    :notes "发货事实触发任务, 在配置期限内完成资料签交并将文件清单推 CRM."}
   {:key "change-unfreeze-sync" :capability "计划/合同/客户交期变更与解冻同步" :system "OA/CRM/ERP" :group "business"
    :matrix-rows "B18" :direction "双向"
    :required-fields ["来源变更标识" "前后值/需求/计划/成本影响" "解冻指令" "新基线多系统同步对账"] :owner "待确认 (变更/集成负责人)" :status "待合同"
    :notes "本地多维影响批准后关联新基线已完成, 外部解冻/回执/成本联动仍待合同."}
   {:key "approval-cancel-compensation" :capability "审批驱动项目/单机取消与外系统补偿" :system "OA + 外系统" :group "business"
    :matrix-rows "B17" :direction "出站"
    :required-fields ["OA 批准依据" "取消原因与受影响对象" "停止后续下发" "外发已执行补偿/对账"] :owner "待确认 (变更/OA 负责人)" :status "待合同"
    :notes "已批准外部变更关联取消原因和受影响对象, 停止后续下发并完成外系统补偿/对账."}
   {:key "notification-delivery" :capability "通知/预警/待办外部投递" :system "外部消息渠道" :group "business"
    :matrix-rows "C11" :direction "出站"
    :required-fields ["授权收件人" "到期提醒" "去重/已读/处理" "撤权处理" "外部消息回执" "跨项目隔离"] :owner "待确认 (集成/运维负责人)" :status "待合同"
    :notes "项目事件产生授权收件人的待办或提醒, 重试不重复通知且不暴露跨项目内容."}
   {:key "material-purchase-feedback" :capability "原材料/长周期/直发申请采购回写" :system "OA/采购/供应商" :group "business"
    :matrix-rows "D01, D02, D04, D05" :direction "双向"
    :required-fields ["OA 审批回写" "采购需求/计划关联" "供应商日期" "采购进度" "无订单/已工单/已发运冲突补偿"] :owner "待确认 (采购/供应链负责人)" :status "待合同"
    :notes "本地申请与独立批准已完成, OA/采购/供应商真实回执未接入."}
   {:key "supplier-progress" :capability "采购分类追踪与供应商进度" :system "SRM/ERP" :group "business"
    :matrix-rows "D07" :direction "入站"
    :required-fields ["各来源工单/订单关联" "承诺与实际日期分离" "延期回写" "陈旧数据提示"] :owner "待确认 (采购/SRM 负责人)" :status "待合同"
    :notes "长周期/关键件/外协/自制/采购/原材关注项与源工单/订单关联, 承诺和实际日期分离."}
   {:key "export-deliverables" :capability "国际项目出口申请与交付物" :system "OA" :group "business"
    :matrix-rows "E06" :direction "双向"
    :required-fields ["国际/国内适用性判定" "出口表触发" "审批资料" "交付物回项目"] :owner "待确认 (出口/合规负责人)" :status "待合同"
    :notes "国际项目触发出口表及审批资料, 国内项目不误触发, 审批结果和交付物回到项目."}
   {:key "packaging-approval-flow" :capability "包材申请审批流对接" :system "OA" :group "business"
    :matrix-rows "D03" :direction "双向"
    :required-fields ["包材业务字段口径" "OA 流程定义" "状态回写"] :owner "待确认 (包材/工艺负责人)" :status "待规则"
    :notes "本地包材申请类型/规格必填/结果按来源任务回写已完成, 包材业务字段与 OA 流程口径待签收."}
   {:key "budget-subject-mapping" :capability "企业预算科目与 ERP 映射" :system "ERP" :group "business"
    :matrix-rows "F02" :direction "双向"
    :required-fields ["企业预算科目" "人工/材料/制造等类别" "已批准预算与概算分离" "变更不覆盖旧预算"] :owner "待确认 (财务/ERP 负责人)" :status "待规则"
    :notes "本地 budget 独立版本/批准/修订已完成, 企业预算科目及 ERP 合同口径待确认."}])

(defn- distribution
  "对一个受控维度取值求频次, 返回按给定顺序的 [{:value :count}] 及覆盖度汇总."
  [rows values]
  (let [freq (frequencies (map :system rows))
        by (mapv (fn [v] {:value v :count (get freq v 0)}) values)]
    by))

(defn catalog
  "外部接口配置目录只读视图: 汇总各能力数量, 依赖状态分布与外部系统分布, 并返回完整清单. 只读, 不查库, 不落库, 不做连通性测试."
  [svc actor]
  (let [rows capabilities
        total (count rows)
        status-freq (frequencies (map :status rows))
        group-freq (frequencies (map :group rows))
        system-values (distinct (map :system rows))
        status-values (vec (sort capability-statuses))]
    {:available (pos? total)
     :total total
     :adapter-count (get group-freq "adapter" 0)
     :business-count (get group-freq "business" 0)
     :contract-count (get status-freq "待合同" 0)
     :rule-count (get status-freq "待规则" 0)
     :by-status (mapv (fn [s] {:value s :count (get status-freq s 0)}) status-values)
     :by-system (distribution rows system-values)
     :rows rows
     :note "本目录为静态配置清单, 逐条列出仍依赖真实外部合同/规则的能力及所需接口字段, 不代表任何真实集成已通过; 完成字段/认证/责任人/沙箱与对账样例后方可验收."}))
