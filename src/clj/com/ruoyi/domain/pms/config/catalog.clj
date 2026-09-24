(ns com.ruoyi.domain.pms.config.catalog
  "随代码发布的内置项目模板, Gate模板与编码规则目录 (工程默认值, 待业务签收), 导入后成为可版本化的平台配置."
  (:require [clojure.string :as str]))

(def gate-types
  "原蓝图展示的关口类型及其默认检查项; G1-G3 编号映射尚未确认, 仅作为模板候选."
  [{:gate_type "requirement-confirm" :title "需求确认Gate" :stage "execution" :page "19"
    :checks [{:code "REQ-1" :title "URS需求版本已全部确认并分类" :required true}
             {:code "REQ-2" :title "需求追踪矩阵无缺设计满足项" :required true}
             {:code "REQ-3" :title "客户/售前资料交接完整" :required false}]}
   {:gate_type "host-summary" :title "主机汇总Gate" :stage "design" :page "26"
    :checks [{:code "HOST-1" :title "主机设计交付物清单齐备" :required true}
             {:code "HOST-2" :title "DQ检查清单确认完成" :required true}
             {:code "HOST-3" :title "设计评审结论已形成" :required true}]}
   {:gate_type "attachment-summary" :title "附件汇总Gate" :stage "design" :page "27"
    :checks [{:code "ATT-1" :title "附件清单独立收集完成" :required true}
             {:code "ATT-2" :title "缺件与豁免已明确" :required true}]}
   {:gate_type "kitting" :title "零件齐套Gate (G4)" :stage "manufacturing" :page "29-30" :blocks ["assembly.start"]
    :checks [{:code "KIT-1" :title "冻结BOM逐行齐套率达到放行口径" :required true}
             {:code "KIT-2" :title "缺件处置方案与工单已明确" :required true}]}
   {:gate_type "assembly-test-handover" :title "装配与测试交接Gate (G5)" :stage "manufacturing" :page "36" :blocks ["test.SIT"]
    :checks [{:code "AT-1" :title "装配执行与交检完成" :required true}
             {:code "AT-2" :title "测试条件齐备并由接收责任人确认" :required true}]}
   {:gate_type "fat-confirm" :title "FAT确认Gate (G6)" :stage "delivery" :page "39-40" :blocks ["shipment.dispatch"]
    :checks [{:code "FAT-1" :title "FAT结果与报告满足验收条件" :required true}
             {:code "FAT-2" :title "阻塞整改已关闭" :required true}]}
   {:gate_type "handover" :title "项目交底Gate (G7)" :stage "delivery" :page "46-47"
    :checks [{:code "HO-1" :title "交底资料清单版本已确认" :required true}
             {:code "HO-2" :title "在配置期限内完成签交" :required true}]}
   {:gate_type "sat-confirm" :title "SAT条件与SAT确认Gate (G8)" :stage "closure" :page "48-49"
    :checks [{:code "SAT-1" :title "现场任务与SAT执行证据齐备" :required true}
             {:code "SAT-2" :title "客户/质量确认及遗留问题安排完整" :required true}]}
   {:gate_type "generic" :title "通用Gate" :stage "execution" :page "4"
    :checks [{:code "C1" :title "实际证据齐全" :required true}]}])

(defn gate-type
  "按类型读取目录中的关口候选."
  [type]
  (some #(when (= type (:gate_type %)) %) gate-types))

(def ^:private order-stages
  [{:code "S1" :name "设计准备" :weight 10} {:code "S2" :name "研发设计" :weight 20}
   {:code "S3" :name "齐套备料" :weight 10} {:code "S4" :name "装配调试" :weight 20}
   {:code "S5" :name "测试验证" :weight 15} {:code "S6" :name "发货交付" :weight 10}
   {:code "S7" :name "现场SAT" :weight 10} {:code "S8" :name "收尾归档" :weight 5}])

(def ^:private rd-stages
  [{:code "R1" :name "立项论证" :weight 15} {:code "R2" :name "方案设计" :weight 25}
   {:code "R3" :name "样机试制" :weight 25} {:code "R4" :name "测试验证" :weight 20}
   {:code "R5" :name "总结归档" :weight 15}])

(def ^:private dept-stages
  [{:code "D1" :name "任务立项" :weight 20} {:code "D2" :name "执行推进" :weight 60} {:code "D3" :name "总结关闭" :weight 20}])

(defn- gate-templates
  "按类型从目录展开模板内 Gate 定义."
  [types]
  (mapv (fn [type]
          (let [g (gate-type type)]
            {:code (str "GT-" (str/upper-case type)) :title (:title g) :gate_type type
             :stage (:stage g) :required true :blocks (or (:blocks g) []) :checks (:checks g)}))
        types))

(def project-templates
  "内置项目模板: 订单型 (设备/整线/服务) 与研发/部门事务型, 各自阶段/结构/Gate/交付/收尾不同."
  [{:code "TPL-EQUIPMENT" :title "单机设备订单项目" :description "单元-产品线-单机三类结构, 覆盖需求确认到SAT的完整关口"
    :project_types ["equipment"] :stages order-stages
    :structure [{:node_type "sub" :suffix "U1" :name "主机单元" :machines [{:suffix "M1" :name "主机#1"}]}
                {:node_type "sub" :suffix "U2" :name "附件单元" :machines []}]
    :gate_templates (gate-templates ["requirement-confirm" "host-summary" "attachment-summary" "kitting" "assembly-test-handover" "fat-confirm" "handover" "sat-confirm"])
    :team_roles ["项目经理" "机械负责人" "电气负责人" "软件负责人" "质量工程师" "采购接口人"]
    :document_categories ["立项" "设计" "DQ" "制造" "验证" "过程"]
    :delivery {:required_stages ["materials" "assembly" "quality" "shipment"] :required_test_types ["SIT" "FAT" "SAT"]}
    :closure_items [{:kind "check" :title "交付物清单归档" :required true} {:kind "check" :title "四算决算已批准" :required true}
                    {:kind "check" :title "遗留问题移交安排" :required true}]}
   {:code "TPL-LINE" :title "整线工程订单项目" :description "多单元多单机整线, 主/子/单机计划网络与分层齐套"
    :project_types ["line"] :stages order-stages
    :structure [{:node_type "sub" :suffix "U1" :name "前段单元" :machines [{:suffix "M1" :name "单机#1"} {:suffix "M2" :name "单机#2"}]}
                {:node_type "sub" :suffix "U2" :name "后段单元" :machines [{:suffix "M3" :name "单机#3"}]}]
    :gate_templates (gate-templates ["requirement-confirm" "host-summary" "attachment-summary" "kitting" "assembly-test-handover" "fat-confirm" "handover" "sat-confirm"])
    :team_roles ["项目经理" "整线工艺负责人" "机械负责人" "电气负责人" "软件负责人" "质量工程师" "现场经理"]
    :document_categories ["立项" "设计" "DQ" "制造" "验证" "过程" "现场"]
    :delivery {:required_stages ["materials" "assembly" "quality" "shipment"] :required_test_types ["SIT" "FAT" "SAT"]}
    :closure_items [{:kind "check" :title "整线交付物清单归档" :required true} {:kind "check" :title "四算决算已批准" :required true}
                    {:kind "check" :title "客户遗留问题移交" :required true}]}
   {:code "TPL-SERVICE" :title "技术服务项目" :description "无制造与发运环节, 以现场服务与验收为主"
    :project_types ["service"]
    :stages [{:code "V1" :name "需求确认" :weight 20} {:code "V2" :name "服务实施" :weight 60} {:code "V3" :name "验收关闭" :weight 20}]
    :structure []
    :gate_templates (gate-templates ["requirement-confirm" "sat-confirm"])
    :team_roles ["项目经理" "服务工程师" "质量工程师"]
    :document_categories ["立项" "过程" "验收"]
    :delivery {:required_stages ["materials" "assembly" "quality"] :required_test_types ["SAT"]}
    :closure_items [{:kind "check" :title "服务报告归档" :required true} {:kind "check" :title "决算已批准" :required true}]}
   {:code "TPL-NEW-PRODUCT" :title "新产品研发项目" :description "研发内部流程: 立项论证到样机测试与总结, 不套用订单模板"
    :project_types ["new_product" "new_technology"] :stages rd-stages
    :structure [{:node_type "sub" :suffix "P1" :name "样机" :machines [{:suffix "PT1" :name "样机#1"}]}]
    :gate_templates [{:code "GT-RD-REVIEW" :title "方案评审Gate" :gate_type "generic" :stage "execution" :required true :blocks []
                      :checks [{:code "RD-1" :title "研发方案评审通过" :required true} {:code "RD-2" :title "风险与资源已确认" :required true}]}
                     {:code "GT-RD-CLOSE" :title "研发总结Gate" :gate_type "generic" :stage "closure" :required true :blocks []
                      :checks [{:code "RC-1" :title "测试验证报告齐备" :required true} {:code "RC-2" :title "研发总结与经验教训归档" :required true}]}]
    :team_roles ["研发项目经理" "方案负责人" "测试负责人"]
    :document_categories ["立项" "方案" "样机" "测试" "总结"]
    :delivery {:required_stages ["materials" "assembly" "quality"] :required_test_types ["SIT"]}
    :closure_items [{:kind "check" :title "研发总结报告归档" :required true} {:kind "check" :title "研发费用决算已批准" :required true}]}
   {:code "TPL-SPECIAL-RD" :title "专题研发项目" :description "针对专题技术攻关, 阶段更短, 无结构节点"
    :project_types ["special_rd"] :stages rd-stages :structure []
    :gate_templates [{:code "GT-RD-REVIEW" :title "方案评审Gate" :gate_type "generic" :stage "execution" :required true :blocks []
                      :checks [{:code "RD-1" :title "研发方案评审通过" :required true}]}
                     {:code "GT-RD-CLOSE" :title "研发总结Gate" :gate_type "generic" :stage "closure" :required true :blocks []
                      :checks [{:code "RC-1" :title "专题总结报告归档" :required true}]}]
    :team_roles ["专题负责人" "技术骨干"]
    :document_categories ["立项" "方案" "测试" "总结"]
    :delivery {:required_stages ["materials" "assembly" "quality"] :required_test_types ["SIT"]}
    :closure_items [{:kind "check" :title "专题总结报告归档" :required true}]}
   {:code "TPL-DEPT-AFFAIRS" :title "部门事务项目" :description "部门内部事务类项目, 三阶段, 无制造/试验要求"
    :project_types ["dept_affairs"] :stages dept-stages :structure []
    :gate_templates [{:code "GT-DEPT-START" :title "任务确认Gate" :gate_type "generic" :stage "execution" :required true :blocks []
                      :checks [{:code "DA-1" :title "任务目标与责任已确认" :required true}]}
                     {:code "GT-DEPT-CLOSE" :title "任务关闭Gate" :gate_type "generic" :stage "closure" :required true :blocks []
                      :checks [{:code "DC-1" :title "任务结果已验收" :required true}]}]
    :team_roles ["事务负责人" "参与人"]
    :document_categories ["立项" "过程" "总结"]
    :delivery {:required_stages ["materials" "assembly" "quality"] :required_test_types ["SIT"]}
    :closure_items [{:kind "check" :title "事务总结归档" :required true}]}])

(def coding-rules
  "内置编码规则: 占位符 {YYYY} {YY} {MM} {TYPE} {PROJECT} {SEQ:n}; 版本规则统一为整数修订号."
  [{:code "RULE-PROJECT" :object_type "project" :pattern "PRJ-{YYYY}-{SEQ:4}" :enforced false
    :description "项目编号: 年份 + 四位流水" :version_rule "整数修订号, 编辑不改编号" :collection_rule "按项目唯一"}
   {:code "RULE-SUB" :object_type "sub" :pattern "{PROJECT}-U{SEQ:2}" :enforced false
    :description "子项目/单元: 项目编号 + U流水" :version_rule "不适用" :collection_rule "项目内唯一"}
   {:code "RULE-MACHINE" :object_type "machine" :pattern "{PROJECT}-M{SEQ:2}" :enforced false
    :description "单机: 项目编号 + M流水" :version_rule "不适用" :collection_rule "项目内唯一"}
   {:code "RULE-DOCUMENT" :object_type "document" :pattern "DOC-{PROJECT}-{SEQ:3}" :enforced false
    :description "证据文档: 项目编号 + 三位流水, 修订形成新版本" :version_rule "同编号递增 revision, 内容不可变"
    :collection_rule "关口按最新已发布版本收集"}
   {:code "RULE-REQUIREMENT" :object_type "requirement" :pattern "URS-{PROJECT}-{SEQ:3}" :enforced false
    :description "URS需求: 项目编号 + 三位流水" :version_rule "同编号递增 revision" :collection_rule "追踪按最新版本"}
   {:code "RULE-TASK" :object_type "task" :pattern "WBS-{SEQ:3}" :enforced false
    :description "WBS任务编号" :version_rule "随计划修订" :collection_rule "计划基线快照"}])
