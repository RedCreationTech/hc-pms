(ns com.ruoyi.frontend.pages.pms.form
  "项目基本信息编辑抽屉."
  (:require
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))

(defn user-options
  "把组织用户映射成表单选项."
  [users]
  (mapv #(hash-map :value (:user_id %) :label (str (or (:nick_name %) (:user_name %))
                                                  " / " (:user_name %))) users))

(defn- text-field
  "标准文本输入项."
  [name label required? placeholder]
  [antd/form-item {:name name :label label
                  :rules (when required? [{:required true :whitespace true :message (str "请输入" label)}])}
   [antd/input {:placeholder placeholder :maxLength 200}]])

(defn- identity-fields
  "项目唯一标识与业务来源."
  []
  [:<>
   [text-field "project_no" "项目编号" true "例如 HC-2026-001"]
   [text-field "name" "项目名称" true "填写便于团队识别的项目名称"]
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap 16}}
    [antd/form-item {:name "project_type" :label "项目类型" :rules [{:required true}]}
     [antd/select {:options shared/project-types}]]
    [text-field "contract_no" "合同编号" false "关联合同"]]
   [text-field "customer" "客户名称" false "客户或业主单位"]])

(defn- ownership-fields
  "项目责任人与计划时间."
  [options]
  [:<>
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap 16}}
    [antd/form-item {:name "manager_id" :label "项目经理" :rules [{:required true :message "请选择项目经理"}]}
     [antd/select {:showSearch true :optionFilterProp "label" :placeholder "选择项目经理"
                   :options (user-options (:users options))}]]
    [antd/form-item {:name "dept_id" :label "所属部门" :rules [{:required true :message "请选择所属部门"}]}
     [antd/select {:showSearch true :optionFilterProp "label" :placeholder "选择所属部门"
                   :options (mapv #(hash-map :value (:dept_id %) :label (:dept_name %)) (:depts options))}]]]
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap 16}}
    [antd/form-item {:name "start_date" :label "计划开始日期"}
     [antd/input {:type "date" :aria-label "计划开始日期"}]]
    [antd/form-item {:name "end_date" :label "计划完成日期"}
     [antd/input {:type "date" :aria-label "计划完成日期"}]]]])

(defn- submit-project!
  "校验日期范围后携带版本提交项目."
  [project values set-error! run!]
  (let [data (js->clj values :keywordize-keys true)
        start (:start_date data) end (:end_date data)
        id (:project_id project)]
    (if (and (seq start) (seq end) (pos? (compare start end)))
      (set-error! "计划完成日期不能早于计划开始日期")
      (do (set-error! nil)
          (run! (if id :put :post) (str "/projects" (when id (str "/" id)))
                (cond-> data id (assoc :version (:version project)))
                (if id "项目信息已更新" "项目草稿已创建"))))))

(defn project-form
  "创建或编辑项目,只有服务端成功后才关闭."
  [{:keys [project options on-close on-saved]}]
  (let [[form] (antd/form-use-form)
        [validation set-validation!] (hooks/use-state nil)
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    (hooks/use-effect
      (fn [] (.setFieldsValue form (clj->js (merge {:project_type "equipment"
                                                   :manager_id (:currentUserId options)} project)))
        js/undefined) [])
    [antd/drawer {:title (if (:project_id project) "编辑项目" "创建项目") :open true
                  :size "min(560px, 100vw)" :destroyOnHidden true
                  :onClose on-close :mask {:closable (not busy?)}
                  :extra (r/as-element
                           [antd/button {:type "primary" :loading busy? :on-click #(.submit form)} "保存项目"])}
     [:p {:style {:color "#7b8798" :marginTop 0 :marginBottom 24}}
      "明确项目范围与责任人,保存后可继续建立项目结构和团队."]
     (when (or validation error) [shared/error-panel (or validation error) nil])
     [antd/form {:form form :layout "vertical" :disabled busy?
                 :onFinish #(submit-project! project % set-validation! run!)}
      [identity-fields]
      [ownership-fields options]]]))
