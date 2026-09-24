(ns com.ruoyi.domain.pms.governance.templates
  "从已发布的平台项目模板实例化项目网络: 结构节点, Gate模板, 阶段/子项目/单机计划容器, 交付要求与收尾清单.
   实例保留模板版本快照, 模板后续修改不追溯覆盖已实例化项目 (A07/A09)."
  (:require [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.delivery.store :as d]
            [com.ruoyi.domain.pms.governance.store :as s]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.planning :as planning]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.time LocalDate]))

(defn- main-node
  [q project]
  (or (first (filter #(= "main" (:node_type %)) (q :pms/nodes {:project_id (:project_id project)})))
      (r/fail! 409 "项目缺少主节点")))

(defn- node!
  "按模板建立结构节点, 已存在同编号节点时复用."
  [q project parent node-type code name]
  (let [existing (q :pms/node-code {:project_id (:project_id project) :node_code code})
        id (or (:node_id existing) (k/id))]
    (when (and existing (not= node-type (:node_type existing)))
      (r/fail! 409 (str "节点编号已被其它类型占用: " code)))
    (when-not existing
      (q :pms/insert-node! {:node_id id :project_id (:project_id project) :parent_id (:node_id parent)
                            :node_type node-type :node_code code :name name}))
    (q :pms/node {:project_id (:project_id project) :node_id id})))

(defn- structure!
  "建立子项目与单机节点, 返回新建/复用的节点列表."
  [q project template]
  (let [main (main-node q project)]
    (vec (for [sub (:structure template)
               :let [sub-node (node! q project main "sub" (str (:project_no project) "-" (:suffix sub)) (:name sub))]
               node (cons sub-node (for [m (:machines sub)]
                                     (node! q project sub-node "machine" (str (:project_no project) "-" (:suffix m)) (:name m))))]
           node))))

(defn- gate-templates!
  "登记模板声明的 Gate 模板, 已存在同编号则跳过."
  [q project actor template]
  (let [existing (set (map :code (s/records q project "gate-template")))]
    (vec (for [gate (:gate_templates template) :when (not (existing (:code gate)))]
           (s/insert! q project actor "gate-template" (assoc gate :source "template") {:status "registered"})))))

(defn- container!
  "建立汇总任务作为阶段或结构节点的计划容器, 已存在同WBS编号则跳过."
  [q project actor start fields]
  (when-not (q :planning/task-code {:project_id (:project_id project) :wbs_code (:wbs_code fields)})
    (planning/create-task-record! q project actor (merge {:task_type "summary" :duration_days 0 :start_date start
                                                          :source_type "template" :description ""} fields))))

(defn- plan!
  "主计划按阶段建容器, 子项目/单机各建一个带节点映射的容器 (B03 主/子/单机计划)."
  [q project actor template nodes]
  (let [start (or (:start_date project) (str (LocalDate/now)))]
    (vec (remove nil?
                 (concat (for [stage (:stages template)]
                           (container! q project actor start {:wbs_code (:code stage) :name (:name stage) :stage_code (:code stage)}))
                         (for [node nodes]
                           (container! q project actor start {:wbs_code (:node_code node) :name (str (:name node) "计划")
                                                              :node_id (:node_id node)})))))))

(defn- delivery!
  "项目尚无交付配置时采用模板的适用环节与试验类型."
  [q project actor template]
  (when-not (seq (d/records q project "configuration"))
    (d/insert! q project actor "configuration"
               (assoc (:delivery template) :code "configuration" :source "project_template"
                      :reason (str "来自项目模板 " (:code template) " v" (:revision template))) "registered")))

(defn- closure!
  "按模板生成收尾检查清单, 已有同名事项不重复."
  [q project template]
  (let [existing (set (map :title (q :closure/items {:project_id (:project_id project)})))]
    (count (for [item (:closure_items template) :when (not (existing (:title item)))]
             (q :closure/insert-item! {:item_id (k/id) :project_id (:project_id project) :kind (:kind item)
                                       :title (:title item) :required (if (:required item) 1 0)
                                       :owner_id nil :due_date nil})))))

(defn instantiate!
  "把已发布模板一次性应用到执行前的项目, 每个项目只允许实例化一次并保留模板版本快照."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "template.instantiated"
    (fn [q project]
      (s/input! body [:template_id :reason])
      (when-not (contains? #{"draft" "initiated" "planning"} (:status project))
        (r/fail! 409 "执行开始后不可再实例化项目模板"))
      (when (seq (s/records q project "template-instance")) (r/fail! 409 "项目已实例化模板, 不能重复应用"))
      (let [template (config/record! q "project-template" (:template_id body))]
        (when-not (= "published" (:status template)) (r/fail! 409 "只能应用已发布的模板版本"))
        (when-not (some #{(:project_type project)} (:project_types template))
          (r/fail! 409 "模板不适用于当前项目类别"))
        (let [nodes (structure! q project template)
              gates (gate-templates! q project actor template)
              tasks (plan! q project actor template nodes)
              configuration (delivery! q project actor template)
              closure-count (closure! q project template)]
          (s/insert! q project actor "template-instance"
                     {:code (:code template) :template_config_id (:id template) :template_revision (:revision template)
                      :title (:title template) :stages (:stages template) :team_roles (:team_roles template)
                      :document_categories (:document_categories template)
                      :node_count (count nodes) :gate_template_count (count gates) :task_count (count tasks)
                      :delivery_configured (boolean configuration) :closure_item_count closure-count
                      :reason (s/optional-text! body :reason 500)}
                     {:status "registered" :revision (:revision template)}))))))

(defn stage-weights
  "读取项目实例化模板的阶段权重, 未实例化时返回空."
  [q project]
  (:stages (first (s/records q project "template-instance"))))
