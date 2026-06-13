(ns com.ruoyi.frontend.pages.business
  "工程方案与资源管理页面。"
  (:require
   ["@ant-design/icons" :refer [FileTextOutlined PictureOutlined]]
   [clojure.string :as str]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-search :refer [page-search]]
   [com.ruoyi.frontend.components.page-toolbar :refer [page-toolbar]]
   [com.ruoyi.frontend.components.right-toolbar :refer [right-toolbar]]
   [com.ruoyi.frontend.components.pagination :refer [pagination]]
   [com.ruoyi.frontend.components.status-tag :refer [status-tag]]
   [com.ruoyi.frontend.components.action-menu :refer [action-menu]]
   [com.ruoyi.frontend.components.form-field :refer [form-field]]
   [com.ruoyi.frontend.components.form-section :refer [form-section] :rename {form-section generic-form-section}]
   [reagent.core :as r]
   [reagent.hooks :as hooks]))

(defn- ok?
  [result]
  (= 200 (:code result)))

(defn- rows
  [result]
  (get-in result [:data :rows] []))

(defn- total
  [result]
  (get-in result [:data :total] 0))

(defn- value
  [m k]
  (or (get m k) ""))

(defn- target-value
  [event]
  (.. event -target -value))

(defn- result-id
  [result]
  (get-in result [:data :id]))

(defn- file-name
  [file]
  (or (.-name file) (:original_name file) (:file_name file) (:name file) "未命名文件"))

(defn- handle-result!
  ([result success-msg success-fn]
   (handle-result! result success-msg success-fn "操作失败"))
  ([result success-msg success-fn fallback-msg]
   (if (ok? result)
     (do (when success-msg (antd/success! success-msg))
         (when success-fn (success-fn result)))
     (antd/error! (or (:msg result) fallback-msg)))))

(defn- page-card
  [title extra & children]
  [antd/card {:title title
              :extra (when extra (r/as-element extra))
              :style {:borderRadius 8}}
   (into [:<>] children)])

(defn- toolbar
  [children]
  [:div {:style {:display "flex" :gap 8 :alignItems "center" :marginBottom 16 :flexWrap "wrap"}} children])

(defn- input
  ([form set-form! k placeholder] (input form set-form! k placeholder false))
  ([form set-form! k placeholder readonly?]
   [antd/input {:value (value form k) :placeholder placeholder
                :disabled readonly?
                :style {:width "100%" :height 36}
                :on-change #(when-not readonly? (set-form! (assoc form k (target-value %))))}]))

(defn- textarea
  ([form set-form! k placeholder] (textarea form set-form! k placeholder false))
  ([form set-form! k placeholder readonly?]
   [antd/text-area {:value (value form k) :placeholder placeholder :rows 2
                    :disabled readonly?
                    :style {:width "100%"}
                    :on-change #(when-not readonly? (set-form! (assoc form k (target-value %))))}]))

(defn- select-status
  ([form set-form!] (select-status form set-form! false))
  ([form set-form! readonly?]
   [antd/select {:value (value form :status) :style {:width "100%"}
                 :disabled readonly?
                 :on-change #(when-not readonly? (set-form! (assoc form :status %)))}
    [antd/select-option {:value "0"} "正常"]
    [antd/select-option {:value "1"} "停用"]]))

(defn- select-input
  ([form set-form! k placeholder options] (select-input form set-form! k placeholder options false))
  ([form set-form! k placeholder options readonly?]
   [antd/select {:value (value form k)
                 :placeholder placeholder
                 :disabled readonly?
                 :style {:width "100%" :height 36}
                 :on-change #(when-not readonly? (set-form! (assoc form k %)))}
    (for [option options]
      ^{:key option} [antd/select-option {:value option} option])]))

(defn- unit-input
  ([form set-form! k placeholder unit] (unit-input form set-form! k placeholder unit false))
  ([form set-form! k placeholder unit readonly?]
   [:div {:style {:display "flex" :width "100%"}}
    [antd/input {:value (value form k)
                 :placeholder placeholder
                 :disabled readonly?
                 :style {:height 36 :borderRadius "4px 0 0 4px"}
                 :on-change #(when-not readonly? (set-form! (assoc form k (target-value %))))}]
    [:span {:style {:height 36
                    :minWidth 48
                    :padding "0 11px"
                    :display "inline-flex"
                    :alignItems "center"
                    :justifyContent "center"
                    :border "1px solid var(--ant-color-border, #d9d9d9)"
                    :borderLeft 0
                    :borderRadius "0 4px 4px 0"
                    :background "var(--ant-color-fill-quaternary, #fafafa)"
                    :color "var(--ant-color-text-secondary, #595959)"}}
     unit]]))

(defn- form-grid
  [& items]
  (into [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "10px 16px" :alignItems "start"}}] items))

(defn- form-item
  [label child]
  [:div {:style {:minWidth 0}}
   [:div {:style {:marginBottom 4 :fontWeight 500 :fontSize 13 :lineHeight "20px"}} label]
   child])


(defn- full-row
  [child]
  [:div {:style {:gridColumn "1 / -1"}} child])

(defn- upload-placeholder
  [text]
  [:div {:style {:height 86 :width "100%" :boxSizing "border-box"
                 :border "1px dashed var(--ant-color-primary-border, #91caff)"
                 :background "var(--ant-color-primary-bg, #f5fbff)"
                 :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                 :color "var(--ant-color-text-secondary, #8c8c8c)" :gap 6 :padding 12 :fontSize 13 :textAlign "center"}}
   [antd/upload-icon {:style {:fontSize 24 :color "var(--ant-color-primary, #1677ff)"}}]
   [:div text]])

(defn pending-upload-box
  [{:keys [files set-files! text readonly?]}]
  (let [content [:div {:style {:minHeight 96 :width "100%" :boxSizing "border-box"
                               :border "1px dashed var(--ant-color-primary-border, #91caff)"
                               :background "var(--ant-color-primary-bg, #f5fbff)"
                               :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                               :color "var(--ant-color-text-secondary, #8c8c8c)" :gap 8 :padding 12 :fontSize 13 :textAlign "center"
                               :cursor (if readonly? "default" "pointer")}}
                 [antd/upload-icon {:style {:fontSize 24 :color "var(--ant-color-primary, #1677ff)"}}]
                 [:div (if readonly? "附件" text)]
                 (if (seq files)
                   [:div {:style {:width "100%" :display "grid" :gap 4 :marginTop 4}}
                    (for [[idx file] (map-indexed vector files)]
                      ^{:key idx}
                      [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                                     :gap 8 :padding "4px 8px" :borderRadius 4
                                     :background "var(--ant-color-bg-container, #fff)"
                                     :border "1px solid var(--ant-color-primary-border, #d6e4ff)"
                                     :color "var(--ant-color-text, #1f2937)"}}
                       [:span {:style {:overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}} (file-name file)]
                       (when-not readonly?
                         [:button {:type "button"
                                   :style {:border 0 :background "transparent" :color "var(--ant-color-error, #ff4d4f)" :cursor "pointer"}
                                   :on-click (fn [e]
                                               (.stopPropagation e)
                                               (set-files! (vec (concat (subvec (vec files) 0 idx)
                                                                        (subvec (vec files) (inc idx))))))}
                          "移除"])])]
                   [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)"}} "暂无附件"])]]
    (if readonly?
      content
      [antd/upload {:showUploadList false
                    :multiple true
                    :beforeUpload (fn [file]
                                    (set-files! (conj (vec files) file))
                                    false)}
       content])))

(defn- attachment-list
  [{:keys [attachments readonly? on-delete]}]
  (let [attachments (vec (or attachments []))]
    [:div {:style {:display "grid" :gap 6 :marginTop 8}}
     (if (seq attachments)
       (for [attachment attachments]
         ^{:key (:id attachment)}
         [:div {:style {:display "flex"
                        :alignItems "center"
                        :justifyContent "space-between"
                        :gap 8
                        :padding "6px 10px"
                        :border "1px solid var(--ant-color-primary-border, #d6e4ff)"
                        :borderRadius 4
                        :background "var(--ant-color-bg-container, #fff)"}}
          [:span {:style {:minWidth 0 :overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}}
           (file-name attachment)]
          (when (and (not readonly?) on-delete)
            [antd/popconfirm {:title "确认删除该附件？" :okText "删除" :cancelText "取消"
                              :on-confirm #(on-delete attachment)}
             [antd/button {:type "link" :size "small" :danger true} "删除"]])])
       [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :fontSize 13}} "暂无已上传附件"])]))

(defn- upload-files!
  [{:keys [biz-type biz-id section-key file-purpose files on-uploaded on-done on-error]}]
  (let [files (vec files)
        total-count (count files)]
    (if (zero? total-count)
      (when on-done (on-done))
      (let [remaining (atom total-count)
            failed? (atom false)]
        (doseq [file files]
          (api/upload-business-attachment
           {:biz_type biz-type
            :biz_id biz-id
            :section_key section-key
            :file_purpose file-purpose}
           file
           (fn [result]
             (when-not (ok? result)
               (reset! failed? true)
               (antd/error! (or (:msg result) "附件上传失败")))
             (when (and (ok? result) on-uploaded)
               (on-uploaded result))
             (when (zero? (swap! remaining dec))
               (if @failed?
                 (when on-error (on-error))
                 (when on-done (on-done)))))
           (fn [_]
             (reset! failed? true)
             (antd/error! "附件上传失败")
             (when (zero? (swap! remaining dec))
               (when on-error (on-error))))))))))

(defn- status-label
  [status]
  (case status
    "1" "停用"
    "inactive" "停用"
    "done" "已完成"
    "draft" "草稿"
    "正常" "正常"
    "0" "正常"
    (or status "正常")))

(defn- modal-size
  [width]
  {:width width
   :style {:top 24 :maxWidth "calc(100vw - 32px)"}
   :styles {:body {:maxHeight "calc(100vh - 170px)" :overflowY "auto" :padding "14px 24px 18px"}
            :content {:overflow "hidden"}}})

(def project-extra-keys
  [:engineering_industry :engineering_nature :province :construction_scale :contract_scope
   :total_land_area :total_building_area :general_contractor_unit :main_subproject
   :quality_requirement :safety_requirement :technology_requirement :main_function
   :management_staff :subcontract_teams])

(def project-select-options
  {:engineering_industry ["房建" "基础设施" "市政公用" "装饰装修"]
   :engineering_nature ["医院/医疗卫生" "商业商场" "住宅建筑" "公共建筑"]
   :province ["山东省" "北京市" "上海市" "江苏省" "广东省" "浙江省" "四川省"]
   :title ["工程师" "高级工程师" "一级建造师" "安全员" "资料员" "助理工程师"]})

(def management-roles
  [{:role_key "project_manager" :role_name "项目经理"}
   {:role_key "chief_engineer" :role_name "项目总工"}
   {:role_key "professional_engineer" :role_name "专业工程师"}
   {:role_key "commercial_manager" :role_name "商务经理"}
   {:role_key "responsible_engineer" :role_name "责任工程师"}
   {:role_key "quality_director" :role_name "质量总监"}
   {:role_key "safety_director" :role_name "安全总监"}
   {:role_key "safety_engineer" :role_name "安全工程师"}
   {:role_key "technical_engineer" :role_name "技术工程师"}
   {:role_key "material_engineer" :role_name "物资工程师"}
   {:role_key "mechanical_admin" :role_name "机械管理员"}
   {:role_key "document_controller" :role_name "资料员"}
   {:role_key "survey_engineer" :role_name "测量工程师"}
   {:role_key "commercial_engineer" :role_name "商务工程师"}
   {:role_key "test_engineer" :role_name "试验工程师"}])

(defn- default-management-staff
  []
  (mapv (fn [{:keys [role_key role_name]}]
          {:role_key role_key :role_name role_name :person_name "" :title ""})
        management-roles))

(defn- default-project-form
  []
  {:status "0" :management_staff (default-management-staff) :subcontract_teams []})

(defn- parse-extra-json
  [s]
  (try
    (if (seq s) (js->clj (.parse js/JSON s) :keywordize-keys true) {})
    (catch :default _ {})))

(defn- hydrate-project
  [project]
  (let [hydrated (merge (parse-extra-json (:extra_json project)) project)]
    (-> hydrated
        (update :management_staff #(if (seq %) % (default-management-staff)))
        (update :subcontract_teams #(if (vector? %) % [])))))

(defn- project-payload
  [form]
  (let [extra (select-keys form project-extra-keys)]
    (-> form
        (as-> payload (reduce dissoc payload project-extra-keys))
        (assoc :extra_json (.stringify js/JSON (clj->js extra))))))

(def project-overview-fields
  [{:key :project_name :label "项目名称" :type :full-input :rules [{:required true :message "请输入项目名称"}]}
   {:key :engineering_industry :label "工程业态" :type :select :options (:engineering_industry project-select-options)}
   {:key :engineering_nature :label "工程性质" :type :select :options (:engineering_nature project-select-options)}
   {:key :project_address :label "工程地址" :type :input}
   {:key :construction_scale :label "建设规模" :type :input}
   {:key :province :label "所属省份" :type :select :options (:province project-select-options)}
   {:key :contract_scope :label "承包范围" :type :input}
   {:key :total_land_area :label "总占地面积" :type :unit :unit "万㎡"}
   {:key :total_building_area :label "总建筑面积" :type :unit :unit "万㎡"}
   {:key :construction_unit :label "建设单位" :type :input}
   {:key :survey_unit :label "勘察单位" :type :input}
   {:key :design_unit :label "设计单位" :type :input}
   {:key :supervision_unit :label "监理单位" :type :input}
   {:key :general_contractor_unit :label "总承包单位" :type :input}
   {:key :main_subproject :label "主要分包工程" :type :input}
   {:key :contract_period :label "工期" :type :unit :unit "天"}
   {:key :quality_requirement :label "质量" :type :input}
   {:key :safety_requirement :label "安全" :type :input}
   {:key :technology_requirement :label "科技" :type :input}
   {:key :start_date :label "开工时间" :type :input}
   {:key :end_date :label "竣工时间" :type :input}
   {:key :main_function :label "工程主要功能或用途" :type :textarea :full? true}])

(def project-fields
  [[:project_name "项目名称" :full-input]
   [:engineering_industry "工程业态" :select]
   [:engineering_nature "工程性质" :select]
   [:project_address "工程地址" :input]
   [:construction_scale "建设规模" :input]
   [:province "所属省份" :select]
   [:contract_scope "承包范围" :input]
   [:total_land_area "总占地面积" :area]
   [:total_building_area "总建筑面积" :area]
   [:construction_unit "建设单位" :input]
   [:survey_unit "勘察单位" :input]
   [:design_unit "设计单位" :input]
   [:supervision_unit "监理单位" :input]
   [:general_contractor_unit "总承包单位" :input]
   [:main_subproject "主要分包工程" :input]
   [:contract_period "工期" :days]
   [:quality_requirement "质量" :input]
   [:safety_requirement "安全" :input]
   [:technology_requirement "科技" :input]
   [:start_date "开工时间" :input]
   [:end_date "竣工时间" :input]
   [:main_function "工程主要功能或用途" :textarea]])

(defn- empty-subcontract-team
  []
  {:team_name "" :manager_name "" :technical_leader_name "" :safety_leader_name ""})

(defn- management-staff-table
  [{:keys [value readonly? on-change]}]
  (let [staff (or value (default-management-staff))]
    [:div {:style {:marginTop 14}}
     [:div {:style {:borderLeft "3px solid var(--ant-color-primary, #1677ff)"
                    :paddingLeft 10 :marginBottom 10 :fontWeight 600
                    :color "var(--ant-color-text, #1f2937)"}}
      "人员组织"]
     [:div {:style {:marginBottom 8 :fontWeight 600}} "总承包项目管理人员及职责分工"]
     [:div {:style {:border "1px solid var(--ant-color-border-secondary, #e5e7eb)" :borderRadius 4 :overflow "hidden"}}
      [:div {:style {:display "grid" :gridTemplateColumns "70px 1.2fr 1.4fr 1.4fr"
                     :background "var(--ant-color-fill-quaternary, #f8fafc)"
                     :fontWeight 600 :color "var(--ant-color-text, #374151)"}}
       (for [title ["序号" "岗位名称" "姓名" "职称（资质）"]]
         ^{:key title} [:div {:style {:padding "9px 12px" :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)"}} title])]
      (for [[idx row] (map-indexed vector staff)]
        ^{:key (:role_key row)}
        [:div {:style {:display "grid" :gridTemplateColumns "70px 1.2fr 1.4fr 1.4fr"
                       :borderTop "1px solid var(--ant-color-border-secondary, #e5e7eb)" :alignItems "center"}}
         [:div {:style {:padding "8px 12px" :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)" :textAlign "center"}} (inc idx)]
         [:div {:style {:padding "8px 12px" :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)"}} (:role_name row)]
         [:div {:style {:padding 8 :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)"}}
          [antd/input {:value (value row :person_name)
                       :placeholder "姓名"
                       :disabled readonly?
                       :style {:height 34}
                       :on-change #(when-not readonly?
                                     (on-change (assoc-in staff [idx :person_name] (target-value %))))}]]
         [:div {:style {:padding 8}}
          [antd/select {:value (value row :title)
                        :placeholder "职称（资质）"
                        :disabled readonly?
                        :style {:width "100%" :height 34}
                        :on-change #(when-not readonly?
                                      (on-change (assoc-in staff [idx :title] %)))}
           (for [title (:title project-select-options)]
             ^{:key title} [antd/select-option {:value title} title])]]])]]))

(defn- management-staff-form-item [form readonly?]
  (let [staff (or (js->clj (.getFieldValue form "management_staff") :keywordize-keys true)
                  (default-management-staff))]
    [antd/form-item {:name :management_staff :noStyle true}
     [management-staff-table
      {:value staff
       :readonly? readonly?
       :on-change #(.setFieldsValue form #js {:management_staff (clj->js %)})}]]))

(defn- subcontract-team-card
  [{:keys [teams idx readonly? on-change]}]
  (let [team (get teams idx)
        roles [{:label "项目经理" :field :manager_name}
               {:label "项目技术负责人" :field :technical_leader_name}
               {:label "项目安全负责人" :field :safety_leader_name}]
        cell-style {:padding "14px 16px" :borderBottom "1px solid var(--ant-color-border-secondary, #f1f3f7)"}]
    [:div {:style {:border "1px solid var(--ant-color-border-secondary, #eef0f4)"
                   :borderRadius 4 :overflow "hidden"
                   :background "var(--ant-color-bg-container, #fff)"}}
     [:div {:style {:display "grid" :gridTemplateColumns "30% 13% 57%"
                    :background "var(--ant-color-fill-quaternary, #fafafa)"
                    :fontWeight 700 :color "var(--ant-color-text, #3f4652)"}}
      [:div {:style {:padding "12px 16px"}} "分包队伍"]
      [:div {:style {:padding "12px 16px"}} "管理职务"]
      [:div {:style {:padding "12px 16px"}} "姓名"]]
     [:div {:style {:display "grid" :gridTemplateColumns "30% 70%" :minHeight 220}}
      [:div {:style {:padding "18px 16px" :borderRight "1px solid var(--ant-color-border-secondary, #eef0f4)"}}
       [antd/input {:value (value team :team_name)
                    :placeholder "请输入分包队伍名称"
                    :disabled readonly?
                    :style {:height 40 :width "100%"}
                    :on-change #(when-not readonly?
                                  (on-change (assoc-in teams [idx :team_name] (target-value %))))}]
       (when-not readonly?
         [antd/button {:type "link" :danger true :style {:padding 0 :marginTop 30}
                       :on-click #(on-change (vec (concat (subvec teams 0 idx)
                                                          (subvec teams (inc idx)))))}
          "删除本组"])]
      [:div
       (for [{:keys [label field]} roles]
         ^{:key label}
         [:div {:style {:display "grid" :gridTemplateColumns "18.6% 81.4%" :alignItems "center"}}
          [:div {:style (merge cell-style {:fontWeight 600})} label]
          [:div {:style cell-style}
           [antd/input {:value (value team field)
                        :placeholder "请输入姓名"
                        :disabled readonly?
                        :style {:height 40 :width "100%"}
                        :on-change #(when-not readonly?
                                      (on-change (assoc-in teams [idx field] (target-value %))))}]]])]]]))

(defn- subcontract-teams-list
  [{:keys [value readonly? on-change]}]
  (let [teams (vec (or value []))]
    [:div {:style {:marginTop 14}}
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 10}}
      [:div {:style {:borderLeft "3px solid var(--ant-color-primary, #1677ff)"
                     :paddingLeft 10 :fontWeight 600
                     :color "var(--ant-color-text, #1f2937)"}}
       "分包单位及岗位人员的安全职责表"]
      (when-not readonly?
        [antd/button {:type "primary"
                      :icon (r/as-element [antd/plus-icon])
                      :on-click #(on-change (conj teams (empty-subcontract-team)))}
         "新增分包队伍"])]
     (if (empty? teams)
       [:div {:style {:height 64 :border "1px dashed var(--ant-color-border, #d9d9d9)"
                      :borderRadius 4 :display "flex" :alignItems "center" :justifyContent "center"
                      :color "var(--ant-color-text-secondary, #8c8c8c)"}}
        "暂无分包队伍，请点击上方按钮添加"]
       [:div {:style {:display "grid" :gap 16}}
        (for [[idx team] (map-indexed vector teams)]
          ^{:key idx}
          [subcontract-team-card
           {:teams teams
            :idx idx
            :readonly? readonly?
            :on-change on-change}])])]))

(defn- subcontract-teams-form-item [form readonly?]
  (let [teams (or (js->clj (.getFieldValue form "subcontract_teams") :keywordize-keys true) [])]
    [antd/form-item {:name :subcontract_teams :noStyle true}
     [subcontract-teams-list
      {:value teams
       :readonly? readonly?
       :on-change #(.setFieldsValue form #js {:subcontract_teams (clj->js %)})}]]))

(defn project-form
  ([form] (project-form form {} (fn [_]) false))
  ([form readonly?] (project-form form {} (fn [_]) readonly?))
  ([form upload-files set-upload-files! readonly?]
   [:div
    [generic-form-section {:title "数据来源" :columns 2}
     [pending-upload-box {:files (get upload-files :source_file [])
                          :set-files! #(set-upload-files! (assoc upload-files :source_file %))
                          :readonly? readonly?
                          :text "支持 docx、pdf、json、xlsx，文件大小不超过 100M"}]
     [pending-upload-box {:files (get upload-files :engineering_source [])
                          :set-files! #(set-upload-files! (assoc upload-files :engineering_source %))
                          :readonly? readonly?
                          :text "从项目综合管理系统导入项目基础信息"}]]
    [generic-form-section {:title "项目概况" :columns 3}
     (for [field project-overview-fields]
       ^{:key (name (:key field))}
       [form-field (assoc field :form form :name (:key field) :disabled readonly?)])]
    [management-staff-form-item form readonly?]
    [subcontract-teams-form-item form readonly?]]))

(defn upload-box
  [{:keys [biz-type biz-id section-key file-purpose on-uploaded]}]
  [antd/upload {:showUploadList false
                :beforeUpload (fn [file]
                                (api/upload-business-attachment
                                 {:biz_type biz-type :biz_id biz-id :section_key section-key :file_purpose file-purpose}
                                 file
                                 (fn [result]
                                   (if (ok? result)
                                     (do (antd/success! "上传成功") (when on-uploaded (on-uploaded result)))
                                     (antd/error! (:msg result))))
                                 (fn [_] (antd/error! "上传失败")))
                                false)}
   [antd/button {:icon (r/as-element [antd/upload-icon])} "上传附件"]])

(defn project-modal
  [{:keys [open? editing readonly? on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)
        [upload-files set-upload-files!] (hooks/use-state {})
        [attachments set-attachments!] (hooks/use-state [])
        delete-attachment! (fn [attachment]
                             (api/delete-business-attachment (:id attachment)
                                                             (fn [result]
                                                               (handle-result! result "删除成功"
                                                                               (fn [_]
                                                                                 (set-attachments! (vec (remove #(= (:id %) (:id attachment)) attachments))))
                                                                               "删除失败"))
                                                             (fn [_] (antd/error! "删除失败"))))]
    (hooks/use-effect
     (fn []
       (when open?
         (set-upload-files! {})
         (.resetFields form)
         (if (:id editing)
           (do
             (set-attachments! (vec (or (:attachments editing) [])))
             (.setFieldsValue form (clj->js (hydrate-project editing)))
             (api/get-project (:id editing)
                              (fn [result]
                                (when (ok? result)
                                  (let [detail (:data result)]
                                    (set-attachments! (vec (or (:attachments detail) [])))
                                    (.setFieldsValue form (clj->js (hydrate-project detail))))))
                              (fn [_] nil)))
           (do
             (.setFieldsValue form (clj->js (default-project-form)))
             (set-attachments! []))))
       js/undefined)
     [open? editing])
    [antd/modal (merge (modal-size 1180)
                       (cond-> {:open open?
                                :title (cond
                                         readonly? "查看项目"
                                         (:id editing) "编辑项目"
                                         :else "新建项目")
                                :okText "保存"
                                :cancelText "取消"
                                :destroyOnHidden true
                                :on-ok #(when-not readonly? (.submit form))
                                :on-cancel on-cancel}
                         readonly? (assoc :footer nil)))
     [antd/form {:form form
                 :layout "vertical"
                 :disabled readonly?
                 :on-finish (fn [values]
                              (let [values (js->clj values :keywordize-keys true)]
                                (on-ok (project-payload values) upload-files)))}
      [project-form form upload-files set-upload-files! readonly?]
      [generic-form-section {:title "已上传附件" :columns 1}
       [full-row [attachment-list {:attachments attachments
                                   :readonly? readonly?
                                   :on-delete delete-attachment!}]]]]]))

(defn team-modal
  [{:keys [open? project on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when open?
         (.resetFields form)
         (.setFieldsValue form #js {}))
       js/undefined)
     [open?])
    [antd/modal (merge (modal-size 640)
                       {:open open? :title "新增分包队伍" :okText "保存" :cancelText "取消"
                        :on-ok #(.submit form)
                        :on-cancel on-cancel})
     [antd/form {:form form :layout "vertical"
                 :on-finish (fn [values]
                              (on-ok (js->clj values :keywordize-keys true)))}
      [form-field {:type :input :form form :name :team_name :label "分包队伍名称"
                   :rules [{:required true :message "请输入分包队伍名称"}]}]
      [form-field {:type :input :form form :name :leader_name :label "负责人"}]
      [form-field {:type :input :form form :name :contact_phone :label "联系电话"}]
      [form-field {:type :textarea :form form :name :work_scope :label "分包内容"}]
      [form-field {:type :textarea :form form :name :remark :label "备注"}]
      [:div {:style {:marginTop 8 :color "var(--ant-color-text-disabled, #999)"}}
       "所属项目：" (:project_name project)]]]))

(def section-fields
  {"project-info" project-fields
   "people-org" [[:org_name "组织名称" :input] [:position "岗位/职务" :input] [:person_name "姓名" :input] [:phone "联系方式" :input] [:responsibility "职责" :textarea] [:remark "备注" :textarea]]
   "cover" [[:title "方案名称" :input] [:compile_unit "编制单位" :input] [:compiler "编制人" :input] [:reviewer "审核人" :input] [:approver "审批人" :input] [:compile_date "编制日期" :input]]
   "design-overview" [[:engineering_overview "工程概况" :textarea] [:design_basis "设计依据" :textarea] [:design_scope "设计范围" :textarea] [:technical_params "主要技术参数" :textarea] [:design_description "设计说明" :textarea]]
   "layout-plan" [[:drawing_name "图纸名称" :input] [:drawing_desc "图纸说明" :textarea] [:sort_order "排序" :input]]})

(def section-labels
  [{:key "project-info" :label "项目信息"}
   {:key "people-org" :label "人员和组织"}
   {:key "cover" :label "封面"}
   {:key "design-overview" :label "设计概况"}
   {:key "layout-plan" :label "平面布置图"}])

(defn- parse-json
  [s]
  (try (if (seq s) (js->clj (.parse js/JSON s) :keywordize-keys true) {}) (catch :default _ {})))

(defn- stringify
  [m]
  (.stringify js/JSON (clj->js m)))

(defn- solution-payload
  [form]
  (assoc form :progress (or (js/parseInt (value form :progress) 10) 0)))

(defn solution-editor
  [{:keys [solution on-back]}]
  (let [[section set-section!] (hooks/use-state "project-info")
        [form set-form!] (hooks/use-state {})
        [loading? set-loading!] (hooks/use-state false)
        load-section! (fn [key]
                        (set-loading! true)
                        (api/get-solution-section (:id solution) key
                                                  (fn [result]
                                                    (set-loading! false)
                                                    (set-form! (parse-json (get-in result [:data :content_json]))))
                                                  (fn [_] (set-loading! false))))]
    (hooks/use-effect (fn [] (load-section! section) js/undefined) [section])
    [page-card (str "编辑方案 - " (:solution_name solution))
     [antd/space
      [antd/button {:on-click on-back} "返回"]
      [antd/button {:type "primary" :on-click #(api/save-solution-section (:id solution) section {:content_json (stringify form)}
                                                                          (fn [result]
                                                                            (handle-result! result "保存成功" nil "保存失败"))
                                                                          (fn [_] (antd/error! "保存失败")))} "保存章节"]]
     [:div {:style {:display "grid" :gridTemplateColumns "220px 1fr" :gap 16}}
      [antd/card {:size "small"}
       (for [{:keys [key label]} section-labels]
         ^{:key key}
         [antd/button {:block true :type (if (= key section) "primary" "default") :style {:marginBottom 8}
                       :on-click #(set-section! key)} label])]
      [antd/card {:size "small" :loading loading?}
       [form-grid
        (for [[k label type] (get section-fields section)]
          ^{:key (name k)} [form-item label (if (= type :textarea) [textarea form set-form! k label] [input form set-form! k label])])]
       [:div {:style {:marginTop 16}}
        [upload-box {:biz-type "solution" :biz-id (:id solution) :section-key section :file-purpose (if (= section "cover") "cover" "attachment")}]]]]]))

(def solution-categories
  ["超危大工程（A类）" "危大工程（B类）" "一般工程（C/D类）"])

(def solution-plan-cards
  [{:key "ceiling" :title "顶面工程" :desc "顶棚专项方案" :icon "⌂" :available? false}
   {:key "ground" :title "地面工程" :desc "墙地砖粘贴方案" :icon "▦" :available? false}
   {:key "wall" :title "墙面工程" :desc "内墙专项方案" :icon "▣" :available? true}])

(def solution-level-options ["一般" "重点" "示范"])
(def solution-type-options ["墙面工程" "地面工程" "顶面工程"])

(defn- short-date
  [text]
  (if (and text (>= (count text) 10)) (subs text 0 10) (or text "-")))

(defn- solution-shell
  [& children]
  (into [:div {:style {:background "var(--ant-color-bg-layout, #f5f5f5)" :minHeight "calc(100vh - 64px)" :padding "28px 32px"}}
         [:div {:style {:maxWidth 1420 :margin "0 auto"}}]]
        children))

(defn- solution-filter-select
  [value placeholder options on-change]
  [antd/select {:value value :placeholder placeholder :style {:width "100%" :height 40} :on-change on-change}
   [antd/select-option {:value ""} "全部"]
   (for [option options]
     ^{:key option} [antd/select-option {:value option} option])])

(defn- solution-project-filter-select
  [value projects on-change]
  [antd/select {:value (or value "") :placeholder "全部" :style {:width "100%" :height 40} :on-change on-change}
   [antd/select-option {:value ""} "全部"]
   (for [project projects]
     ^{:key (:id project)} [antd/select-option {:value (:id project)} (:project_name project)])])

(defn- solution-columns
  [columns-config on-edit on-delete]
  (clj->js
   (filterv some?
            [(when (get-in columns-config [:solution_name :visible?])
               {:title "方案名称" :dataIndex "solution_name" :key "solution_name"
                :render (fn [v _]
                          (r/as-element
                           [:a {:style {:cursor "pointer" :color "var(--ant-color-primary, #1677ff)"}}
                            (or v "-")]))})
             (when (get-in columns-config [:solution_type :visible?])
               {:title "方案类型" :dataIndex "remark" :key "solution_type"
                :render (fn [v _] (or v "墙面工程"))})
             (when (get-in columns-config [:project_name :visible?])
               {:title "所属项目" :dataIndex "project_name" :key "project_name"
                :render (fn [v _] (or v "-"))})
             (when (get-in columns-config [:solution_level :visible?])
               {:title "方案级别" :dataIndex "solution_level" :key "solution_level"
                :render (fn [v _]
                          (r/as-element
                           [antd/tag {:color (case v "重点" "red" "示范" "gold" "blue")}
                            (or v "一般")]))})
             (when (get-in columns-config [:create_time :visible?])
               {:title "生成时间" :dataIndex "create_time" :key "create_time"
                :render (fn [v _] (short-date v))})
             (when (get-in columns-config [:create_by :visible?])
               {:title "编制人" :dataIndex "create_by" :key "create_by"
                :render (fn [v _] (or v "开发者1"))})
             {:title "操作" :key "action" :width 150
              :render (fn [_ record]
                        (let [row (js->clj record :keywordize-keys true)]
                          (r/as-element
                           [action-menu
                            {:on-edit #(on-edit row)
                             :on-delete #(on-delete row)
                             :more-items []}])))}])))

(defn- solution-project-card
  [project selected? on-click]
  [:div {:on-click on-click
         :style {:display "flex" :gap 14 :alignItems "center" :padding "14px 16px"
                 :border (if selected? "1px solid var(--ant-color-primary, #3b82f6)" "1px solid var(--ant-color-border-secondary, #e5e7eb)")
                 :borderRadius 8
                 :background (if selected? "var(--ant-color-primary-bg, #eff6ff)" "var(--ant-color-bg-container, #fff)")
                 :cursor "pointer"}}
   [:div {:style {:width 46 :height 46 :borderRadius 8
                  :background "var(--ant-color-primary-bg, #e8f1ff)"
                  :display "flex" :alignItems "center" :justifyContent "center"
                  :color "var(--ant-color-primary, #3b82f6)" :fontSize 22}} "▦"]
   [:div {:style {:flex 1 :minWidth 0}}
    [:div {:style {:fontWeight 700 :fontSize 15 :whiteSpace "nowrap" :overflow "hidden" :textOverflow "ellipsis"
                   :color "var(--ant-color-text, #1f2937)"}}
     (or (:project_name project) "未命名项目")]
    [:div {:style {:marginTop 6 :display "flex" :gap 16 :color "var(--ant-color-text-secondary, #6b7280)" :fontSize 13}}
     [:span "▣ " (or (:solution_count project) 0) "个方案"]
     [:span "◆"]]]])

(defn- solution-supplement-card
  [title completed? body & [on-preview on-edit]]
  [:div {:style {:border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                 :borderRadius 8
                 :background "var(--ant-color-bg-container, #fff)"
                 :minHeight 230 :padding 18}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                  :borderBottom "1px solid var(--ant-color-border-secondary, #eef0f4)"
                  :paddingBottom 14}}
    [:div {:style {:fontWeight 700 :fontSize 16 :color "var(--ant-color-text, #1f2937)"}} title]
    [:div {:style {:display "flex" :gap 10}}
     [antd/button {:icon (r/as-element [antd/eye-icon]) :on-click #(when on-preview (on-preview))} "预览"]
     [antd/button {:icon (r/as-element [antd/edit-icon]) :on-click #(when on-edit (on-edit))} "编辑"]]]
   [:div {:style {:marginTop 16}}
    (if completed?
      [antd/tag {:color "success"} "已填写完成"]
      [antd/tag {:color "warning"} "尚未填写"])]
   [:div {:style {:marginTop 14 :color "var(--ant-color-text-secondary, #4b5563)" :lineHeight 1.7}} body]])

(defn- solution-modal-input
  [label placeholder]
  [form-item label [antd/input {:placeholder placeholder :style {:height 40}}]])

(defn- solution-modal-select
  [label value options]
  [form-item label
   [antd/select {:value value :style {:width "100%" :height 40}}
    (for [option options]
      ^{:key option} [antd/select-option {:value option} option])]])

(defn- solution-modal-section
  [title & children]
  [:div {:style {:border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                 :borderRadius 12 :padding 24 :marginTop 18}}
   [:div {:style {:fontSize 18 :fontWeight 800
                  :borderBottom "1px solid var(--ant-color-border-secondary, #edf0f5)"
                  :paddingBottom 14 :marginBottom 18
                  :color "var(--ant-color-text, #1f2937)"}} title]
   (into [:div] children)])

(defn- simple-staff-table
  [roles]
  [:div {:style {:border "1px solid var(--ant-color-border-secondary, #edf0f5)" :borderRadius 4 :overflow "hidden"}}
   [:div {:style {:display "grid" :gridTemplateColumns "80px 1.2fr 1.6fr 1.6fr"
                  :background "var(--ant-color-fill-quaternary, #fafafa)" :fontWeight 700}}
    (for [h ["序号" "岗位名称" "姓名" "职称（资质）"]]
      ^{:key h} [:div {:style {:padding 12 :borderRight "1px solid var(--ant-color-border-secondary, #edf0f5)"}} h])]
   (for [[idx role] (map-indexed vector roles)]
     ^{:key role}
     [:div {:style {:display "grid" :gridTemplateColumns "80px 1.2fr 1.6fr 1.6fr"
                    :borderTop "1px solid var(--ant-color-border-secondary, #edf0f5)"}}
      [:div {:style {:padding 10}} (inc idx)]
      [:div {:style {:padding 10 :fontWeight 600}} role]
      [:div {:style {:padding 8}} [antd/input {:placeholder "姓名" :style {:height 36}}]]
      [:div {:style {:padding 8}} [antd/select {:value "工程师" :style {:width "100%" :height 36}}
                                   [antd/select-option {:value "工程师"} "工程师"]
                                   [antd/select-option {:value "高级工程师"} "高级工程师"]]]])])

(defn- solution-edit-modal-static
  [section open? on-close project]
  (let [project (hydrate-project project)
        title (case section
                :project "编辑 · 项目概况"
                :people "编辑 · 人员组织"
                :cover "编辑 · 方案封面"
                :design "编辑 · 设计概况"
                :layout "编辑 · 平面布置图"
                "编辑")]
    [antd/modal (merge (modal-size 1180)
                       {:open open? :title title :okText "保存" :cancelText "取消" :destroyOnHidden true
                        :on-ok on-close :on-cancel on-close})
     (case section
       :project
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "以下为项目概况编辑模式。"]
        [solution-modal-section "项目概况"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
          [full-row [solution-modal-input "项目名称 *" (or (:project_name project) "项目名称")]]
          [solution-modal-select "工程业态 *" (or (:engineering_industry project) "基础设施") ["基础设施" "房建" "装饰装修"]]
          [solution-modal-select "工程性质 *" (or (:engineering_nature project) "商业商场") ["商业商场" "医院/医疗卫生" "工业建筑"]]
          [solution-modal-input "工程地址 *" (or (:project_address project) "工程地址")]
          [solution-modal-input "建设规模 *" (or (:construction_scale project) "建设规模")]
          [solution-modal-select "所属省份 *" (or (:province project) "河北省") ["河北省" "山东省" "广东省" "江苏省"]]
          [solution-modal-input "承包范围 *" (or (:contract_scope project) "承包范围")]
          [form-item "总占地面积" [unit-input project (fn [_]) :total_land_area "总占地面积" "万㎡"]]
          [form-item "总建筑面积" [unit-input project (fn [_]) :total_building_area "总建筑面积" "万㎡"]]
          [solution-modal-input "建设单位" (or (:construction_unit project) "建设单位")]
          [solution-modal-input "勘察单位" (or (:survey_unit project) "勘察单位")]
          [solution-modal-input "设计单位" (or (:design_unit project) "设计单位")]
          [solution-modal-input "监理单位" (or (:supervision_unit project) "监理单位")]
          [solution-modal-input "总承包单位" (or (:general_contractor_unit project) "总承包单位")]
          [solution-modal-input "主要分包工程" (or (:main_subproject project) "主要分包工程")]
          [form-item "工期" [unit-input project (fn [_]) :contract_period "工期" "天"]]
          [solution-modal-input "质量" (or (:quality_requirement project) "质量")]
          [solution-modal-input "安全" (or (:safety_requirement project) "安全")]
          [solution-modal-input "科技" (or (:technology_requirement project) "科技")]
          [solution-modal-input "开工时间" (or (:start_date project) "开工时间")]
          [solution-modal-input "竣工时间" (or (:end_date project) "竣工时间")]
          [full-row [form-item "工程主要功能或用途" [antd/text-area {:placeholder "工程主要功能或用途" :rows 4 :style {:width "100%"}}]]]]]]

       :people
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "以下为人员组织编辑模式。"]
        [solution-modal-section "总承包项目管理人员及职责分工"
         [simple-staff-table ["安全工程师" "技术工程师" "项目总工" "安全总监" "专业工程师" "责任工程师" "测量工程师" "商务经理" "质量总监" "试验工程师" "物资工程师" "商务工程师" "资料员" "项目经理"]]]
        [solution-modal-section "装饰管理部管理人员及职责分工"
         [simple-staff-table ["专业工程师" "装饰经理" "试验工程师" "深化设计师" "测量工程师" "资料员" "技术工程师" "装饰商务经理" "装饰安全总监" "装饰总工" "装饰生产经理" "设计总监" "物资工程师" "安全工程师" "机械管理员"]]]
        [solution-modal-section "分包单位及岗位人员的安全职责表"
         [:div {:style {:display "flex" :justifyContent "flex-end" :marginBottom 16}} [antd/button {:type "primary" :icon (r/as-element [antd/plus-icon])} "新增分包队伍"]]
         [:div {:style {:height 90 :display "flex" :alignItems "center" :justifyContent "center" :color "var(--ant-color-text-secondary, #8c8c8c)"}} "暂无分包队伍，请点击上方按钮添加"]]]

       :cover
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "填写封面排版用字段；取消或遮罩关闭不保存本次修改。"]
        [solution-modal-section "封面信息"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "18px 28px"}}
          [full-row [solution-modal-input "项目名称" (or (:project_name project) "项目名称")]]
          [solution-modal-select "方案类型" "墙面工程" solution-type-options]
          [solution-modal-input "日期" (short-date (or (:create_time project) "2026-06-12"))]
          [full-row [form-item "项目渲染图" [upload-placeholder "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"]]]
          [solution-modal-input "编制单位" "编制单位"]
          [solution-modal-input "编制人" "编制人"]
          [solution-modal-input "制图人" "制图人"]
          [solution-modal-input "项目负责人" "项目负责人"]
          [solution-modal-input "审核人" "审核人"]]]]

       :design
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "当前仅编辑「设计概况」；保存只更新本区块。"]
        [solution-modal-section "设计概况"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
          [solution-modal-input "总建筑面积（㎡）" "如 181247.08"]
          [solution-modal-input "地上建筑面积（㎡）" "如 101064.08"]
          [solution-modal-input "地下建筑面积（㎡）" "如 80183"]
          [solution-modal-input "地下层数（层）" "如 3"]
          [solution-modal-input "地上层数（层）" "如 17"]
          [solution-modal-input "裙房层数（层）" "如 5"]
          [solution-modal-input "地下层高（m）" "如 4"]
          [solution-modal-input "首层层高（m）" "如 5.4"]
          [solution-modal-input "标准层层高（m）" "如 4.2"]
          [solution-modal-input "防火等级" "如 A1"]]
         (for [label ["楼地面" "墙面" "顶棚" "楼梯" "机房（地面 / 墙面 / 顶棚）" "窗" "防水"]]
           ^{:key label}
           [:div {:style {:marginTop 18}}
            [solution-modal-select label "" ["选择材料添加..." "乳胶漆" "瓷砖" "石材"]]])
         [:div {:style {:marginTop 18}} [form-item "环境保护" [antd/text-area {:placeholder "遵守国家及地方政府关于环境保护、水土保持等要求" :rows 3 :style {:width "100%"}}]]]]]

       :layout
       [:div
        [solution-modal-section "平面布置图"
         [form-item "文字说明" [antd/text-area {:placeholder "描述施工区域、楼层、道路与应急布置等。" :rows 5 :style {:width "100%"}}]]
         [:div {:style {:marginTop 18}} [form-item "上传平面布置图" [upload-placeholder "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"]]]]]
       [:span])]))

(def solution-section-key
  {:project "project-info"
   :people "people-org"
   :cover "cover"
   :design "design-overview"
   :layout "layout-plan"})

(defn- solution-section-default
  [section project]
  (let [project (hydrate-project project)]
    (case section
      :project project
      :people {:management_staff (or (:management_staff project) (default-management-staff))
               :subcontract_teams (or (:subcontract_teams project) [])}
      :cover {:project_name (:project_name project)
              :solution_type "墙面工程"
              :compile_date (short-date (or (:create_time project) ""))
              :compile_unit ""
              :compiler ""
              :draftsman ""
              :project_leader (:project_leader project)
              :reviewer ""}
      :design {:total_building_area (:total_building_area project)
               :ground_building_area ""
               :underground_building_area ""
               :underground_floors ""
               :ground_floors ""
               :podium_floors ""
               :underground_height ""
               :first_floor_height ""
               :standard_floor_height ""
               :fire_rating ""
               :floor_material ""
               :wall_material ""
               :ceiling_material ""
               :stair_material ""
               :machine_room_material ""
               :window_material ""
               :waterproof_material ""
               :environment_protection ""}
      :layout {:drawing_desc ""}
      {})))

(defn- solution-edit-modal
  [{:keys [section mode open? on-save on-close project initial-data files set-files!]}]
  (let [[form] (antd/form-use-form)
        readonly? (= mode :view)
        title (case section
                :project (str (if readonly? "预览" "编辑") " · 项目概况")
                :people (str (if readonly? "预览" "编辑") " · 人员组织")
                :cover (str (if readonly? "预览" "编辑") " · 方案封面")
                :design (str (if readonly? "预览" "编辑") " · 设计概况")
                :layout (str (if readonly? "预览" "编辑") " · 平面布置图")
                (if readonly? "预览" "编辑"))]
    (hooks/use-effect
     (fn []
       (when open?
         (.resetFields form)
         (.setFieldsValue form (clj->js (merge (solution-section-default section project) initial-data))))
       js/undefined)
     [open? section])
    [antd/modal (merge (modal-size 1180)
                       (cond-> {:open open? :title title
                                :okText "保存" :cancelText "取消"
                                :destroyOnHidden true
                                :on-ok #(when-not readonly? (.submit form))
                                :on-cancel on-close}
                         readonly? (assoc :footer nil)))
     [antd/form {:form form :layout "vertical"
                 :disabled readonly?
                 :on-finish (fn [values]
                              (let [values (js->clj values :keywordize-keys true)]
                                (when on-save (on-save section values files))
                                (antd/success! "保存成功")
                                (on-close)))}
      (case section
        :project
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "保存后会用于生成方案的项目信息章节。")]
         [project-form form readonly?]]

        :people
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "保存后会用于生成方案的人员组织章节。")]
         [management-staff-form-item form readonly?]
         [subcontract-teams-form-item form readonly?]]

        :cover
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "封面附件会在生成方案后自动上传。")]
         [solution-modal-section "封面信息"
          [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "18px 28px"}}
           [full-row [form-field {:type :input :form form :name :project_name :label "项目名称"}]]
           [form-field {:type :select :form form :name :solution_type :label "方案类型" :options solution-type-options}]
           [form-field {:type :input :form form :name :compile_date :label "日期"}]
           [form-field {:type :input :form form :name :compile_unit :label "编制单位"}]
           [form-field {:type :input :form form :name :compiler :label "编制人"}]
           [form-field {:type :input :form form :name :draftsman :label "制图人"}]
           [form-field {:type :input :form form :name :project_leader :label "项目负责人"}]
           [form-field {:type :input :form form :name :reviewer :label "审核人"}]
           [full-row [form-item "项目渲染图"
                      [pending-upload-box {:files files
                                           :set-files! set-files!
                                           :readonly? readonly?
                                           :text "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"}]]]]]]

        :design
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "当前仅编辑「设计概况」；保存只更新本区块。")]
         [solution-modal-section "设计概况"
          [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
           [form-field {:type :input :form form :name :total_building_area :label "总建筑面积（㎡）"}]
           [form-field {:type :input :form form :name :ground_building_area :label "地上建筑面积（㎡）"}]
           [form-field {:type :input :form form :name :underground_building_area :label "地下建筑面积（㎡）"}]
           [form-field {:type :input :form form :name :underground_floors :label "地下层数（层）"}]
           [form-field {:type :input :form form :name :ground_floors :label "地上层数（层）"}]
           [form-field {:type :input :form form :name :podium_floors :label "裙房层数（层）"}]
           [form-field {:type :input :form form :name :underground_height :label "地下层高（m）"}]
           [form-field {:type :input :form form :name :first_floor_height :label "首层层高（m）"}]
           [form-field {:type :input :form form :name :standard_floor_height :label "标准层层高（m）"}]
           [form-field {:type :input :form form :name :fire_rating :label "防火等级"}]]
          (for [[k label] [[:floor_material "楼地面"] [:wall_material "墙面"] [:ceiling_material "顶棚"]
                           [:stair_material "楼梯"] [:machine_room_material "机房（地面 / 墙面 / 顶棚）"]
                           [:window_material "窗"] [:waterproof_material "防水"]]]
            ^{:key (name k)}
            [:div {:style {:marginTop 18}}
             [form-field {:type :select :form form :name k :label label :options ["选择材料添加..." "乳胶漆" "瓷砖" "石材"] :disabled readonly?}]])
          [:div {:style {:marginTop 18}}
           [form-field {:type :textarea :form form :name :environment_protection :label "环境保护" :full? true}]]]]

        :layout
        [:div
         [solution-modal-section "平面布置图"
          [form-field {:type :textarea :form form :name :drawing_desc :label "文字说明" :full? true}]
          [:div {:style {:marginTop 18}}
           [form-item "上传平面布置图"
            [pending-upload-box {:files files
                                 :set-files! set-files!
                                 :readonly? readonly?
                                 :text "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"}]]]]]

        [:span])]]))

(defn solution-home-page
  []
  (let [[solutions set-solutions!] (hooks/use-state [])
        [projects set-projects!] (hooks/use-state [])
        [engineerings set-engineerings!] (hooks/use-state [])
        [editing set-editing!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state {})
        [show-search? set-show-search!] (hooks/use-state false)
        [columns-config set-columns-config!] (hooks/use-state
                                              {:solution_name {:label "方案名称" :visible? true}
                                               :solution_type {:label "方案类型" :visible? true}
                                               :project_name {:label "所属项目" :visible? true}
                                               :solution_level {:label "方案级别" :visible? true}
                                               :create_time {:label "生成时间" :visible? true}
                                               :create_by {:label "编制人" :visible? true}})
        [page set-page!] (hooks/use-state 1)
        [page-size set-page-size!] (hooks/use-state 10)
        [workflow set-workflow!] (hooks/use-state :list)
        [selected-category set-selected-category!] (hooks/use-state "一般工程（C/D类）")
        [selected-plan set-selected-plan!] (hooks/use-state (last solution-plan-cards))
        [selected-project set-selected-project!] (hooks/use-state nil)
        [project-picker? set-project-picker!] (hooks/use-state false)
        [project-modal? set-project-modal!] (hooks/use-state false)
        [section-modal set-section-modal!] (hooks/use-state nil)
        [supplement-data set-supplement-data!] (hooks/use-state {})
        [supplement-files set-supplement-files!] (hooks/use-state {})
        fetch! (fn []
                 (api/list-solutions query #(when (ok? %) (set-solutions! (rows %))) (fn [_]))
                 (api/list-projects {} #(when (ok? %) (set-projects! (rows %))) (fn [_]))
                 (api/list-engineerings {} #(when (ok? %) (set-engineerings! (rows %))) (fn [_])))]
    (hooks/use-effect (fn [] (fetch!) js/undefined) [])
    (if editing
      [solution-editor {:solution editing :on-back #(do (set-editing! nil) (fetch!))}]
      (let [filtered-projects (if-let [engineering-id (:id (:engineering selected-project))]
                                (filter #(= (:engineering_id %) engineering-id) projects)
                                projects)
            chosen-plan-title (:title selected-plan)
            create-solution! (fn []
                               (when selected-project
                                 (let [payload {:solution_name (str (:project_name selected-project) "-" chosen-plan-title "施工方案")
                                                :engineering_id (:engineering_id selected-project)
                                                :engineering_name (:engineering_name selected-project)
                                                :project_id (:id selected-project)
                                                :project_name (:project_name selected-project)
                                                :status "draft"
                                                :progress 20
                                                :remark chosen-plan-title}
                                       persist-supplement! (fn [solution-id]
                                                             (doseq [[section data] supplement-data
                                                                     :let [section-key (solution-section-key section)]
                                                                     :when section-key]
                                                               (api/save-solution-section solution-id section-key
                                                                                          {:content_json (stringify data)}
                                                                                          (fn [_])
                                                                                          (fn [_] (antd/error! "补充信息保存失败"))))
                                                             (doseq [[section files] supplement-files
                                                                     :let [section-key (solution-section-key section)]
                                                                     :when (and section-key (seq files))]
                                                               (upload-files! {:biz-type "solution"
                                                                               :biz-id solution-id
                                                                               :section-key section-key
                                                                               :file-purpose (if (= section :cover) "cover" "attachment")
                                                                               :files files})))]
                                   (api/create-solution payload
                                                        #(handle-result!
                                                          %
                                                          nil
                                                          (fn [result]
                                                            (persist-supplement! (result-id result))
                                                            (antd/success! "生成成功")
                                                            (set-supplement-data! {})
                                                            (set-supplement-files! {})
                                                            (set-workflow! :list)
                                                            (fetch!))
                                                          "生成失败")
                                                        (fn [_] (antd/error! "生成失败"))))))]
        (case workflow
          :engineering
          [solution-shell
           [:div {:style {:marginBottom 56}}
            [antd/button {:on-click #(set-workflow! :list)} "←"]]
           [:div {:style {:display "flex" :justifyContent "center" :gap 18 :marginBottom 76}}
            (for [category solution-categories]
              ^{:key category}
              [:button {:on-click #(set-selected-category! category)
                        :style {:height 58 :minWidth 240 :borderRadius 30
                                :border (if (= category selected-category)
                                          "1px solid var(--ant-color-primary, #8bbcff)"
                                          "1px solid var(--ant-color-border-secondary, #d9dee7)")
                                :background (if (= category selected-category)
                                              "var(--ant-color-primary-bg, #eaf4ff)"
                                              "var(--ant-color-bg-container, #fff)")
                                :color (if (= category selected-category)
                                         "var(--ant-color-primary, #2f7ff0)"
                                         "var(--ant-color-text, #374151)")
                                :fontSize 18 :fontWeight 700 :cursor "pointer"}}
               category])]
           [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(260px, 1fr))" :gap 28}}
            (for [{:keys [key title desc icon available?] :as card} solution-plan-cards]
              ^{:key key}
              [:div {:on-click #(when available?
                                  (set-selected-plan! card)
                                  (set-project-picker! true))
                     :style {:height 290
                             :border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                             :borderRadius 16
                             :background "var(--ant-color-bg-container, #fff)"
                             :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                             :cursor (if available? "pointer" "not-allowed")
                             :opacity (if available? 1 0.45)}}
               [:div {:style {:width 60 :height 60 :borderRadius 12
                              :background "var(--ant-color-primary-bg, #eef5ff)"
                              :display "flex" :alignItems "center" :justifyContent "center"
                              :fontSize 28 :color "var(--ant-color-primary, #3b82f6)"}} icon]
               [:div {:style {:marginTop 22 :fontSize 21 :fontWeight 700
                              :color "var(--ant-color-text, #1f2937)"}} title]
               [:div {:style {:marginTop 8 :color "var(--ant-color-text-secondary, #8c8c8c)"}} desc]
               [:div {:style {:marginTop 16 :padding "5px 16px" :borderRadius 20
                              :background (if available?
                                            "var(--ant-color-primary-bg, #eaf4ff)"
                                            "var(--ant-color-fill-quaternary, #f1f2f4)")
                              :color (if available?
                                       "var(--ant-color-primary, #2f7ff0)"
                                       "var(--ant-color-text-secondary, #8c8c8c)")
                              :fontWeight 700}}
                (if available? "• 可使用" "暂未开放")]])]
           [antd/modal (merge (modal-size 620)
                              {:open project-picker? :title "选择项目" :footer nil :on-cancel #(set-project-picker! false)})
            [:div {:style {:display "grid" :gap 12}}
             [:div {:style {:fontSize 16 :color "var(--ant-color-text, #374151)"}} "请选择当前方案所属项目："]
             (for [project filtered-projects]
               ^{:key (:id project)}
               [solution-project-card project (= (:id project) (:id selected-project))
                #(do (set-selected-project! project)
                     (set-project-picker! false)
                     (set-workflow! :supplement))])
             [:div {:style {:borderTop "1px solid var(--ant-color-border-secondary, #edf0f5)"
                            :marginTop 10 :paddingTop 18
                            :color "var(--ant-color-text-secondary, #6b7280)"}}
              [:div "没有找到合适的项目？"]
              [antd/button {:type "link" :icon (r/as-element [antd/plus-icon]) :on-click #(set-project-modal! true)} "新建项目"]]]]
           [project-modal {:open? project-modal? :editing nil
                           :on-ok (fn [form upload-files]
                                    (api/create-project form
                                                        #(handle-result!
                                                          %
                                                          nil
                                                          (fn [result]
                                                            (let [new-project-id (result-id result)
                                                                  all-files (vec (mapcat second upload-files))]
                                                              (upload-files! {:biz-type "project"
                                                                              :biz-id new-project-id
                                                                              :section-key ""
                                                                              :file-purpose "attachment"
                                                                              :files all-files
                                                                              :on-done (fn []
                                                                                         (set-project-modal! false)
                                                                                         (fetch!))})))
                                                          "保存失败")
                                                        (fn [_] (antd/error! "保存失败"))))
                           :on-cancel #(set-project-modal! false)}]]

          :supplement
          [solution-shell
           [:div {:style {:background "var(--ant-color-bg-container, #fff)"
                          :borderRadius 18 :padding 26
                          :boxShadow "0 8px 24px rgba(15,23,42,0.06)"}}
            [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                           :borderBottom "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                           :paddingBottom 22}}
             [:div {:style {:display "flex" :alignItems "center" :gap 14}}
              [antd/button {:on-click #(set-workflow! :engineering)} "←"]
              [:h1 {:style {:margin 0 :fontSize 24 :fontWeight 800 :color "var(--ant-color-text, #1f2937)"}} "补充信息"]]
             [antd/button {:type "primary" :size "large" :on-click create-solution!} "生成方案"]]
            [:div {:style {:marginTop 30}}
             [:div {:style {:fontSize 18 :fontWeight 800 :marginBottom 6 :color "var(--ant-color-text, #1f2937)"}} "关联项目信息"]
             [:div {:style {:color "var(--ant-color-text-secondary, #667085)" :marginBottom 22}} "确认本次生成方案所使用的项目概况、人员组织与方案封面信息。"]
             [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap 22}}
              [solution-supplement-card "项目概况" true
               [:div
                [:div "项目名称：" (or (:project_name selected-project) "-")]
                [:div "工程性质：" (or (:engineering_nature (hydrate-project selected-project)) "-")]
                [:div "工程地址：" (or (:project_address selected-project) "-")]]
               #(set-section-modal! {:section :project :mode :view})
               #(set-section-modal! {:section :project :mode :edit})]
              [solution-supplement-card "人员组织" true "已维护总承包项目管理人员及职责分工，可继续编辑人员组织信息。"
               #(set-section-modal! {:section :people :mode :view})
               #(set-section-modal! {:section :people :mode :edit})]
              [solution-supplement-card "方案封面" true "已填写完成"
               #(set-section-modal! {:section :cover :mode :view})
               #(set-section-modal! {:section :cover :mode :edit})]]
             [:div {:style {:marginTop 34 :fontSize 18 :fontWeight 800 :color "var(--ant-color-text, #1f2937)"}} "个性化参数"]
             [:div {:style {:marginTop 14 :border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                            :borderRadius 8 :padding 18}}
              [form-item "方案级别" [antd/select {:value "一般" :style {:width 300 :height 40}}
                                 [antd/select-option {:value "一般"} "一般"]
                                 [antd/select-option {:value "重点"} "重点"]]]
              [:div {:style {:height 1 :background "var(--ant-color-border-secondary, #edf0f5)" :margin "18px 0"}}]
              [:div {:style {:fontSize 16 :fontWeight 700 :marginBottom 16 :color "var(--ant-color-text, #1f2937)"}} "设计概况与平面布置图"]
              [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap 22}}
               [solution-supplement-card "设计概况" false "尚未填写设计概况。请在本卡片右上角「预览」或「编辑」打开弹窗。"
                #(set-section-modal! {:section :design :mode :view})
                #(set-section-modal! {:section :design :mode :edit})]
               [solution-supplement-card "平面布置图" false "尚未填写平面布置图说明或登记附件。请在本卡片右上角「预览」或「编辑」打开弹窗。"
                #(set-section-modal! {:section :layout :mode :view})
                #(set-section-modal! {:section :layout :mode :edit})]]]]]
           (let [active-section (:section section-modal)]
             [solution-edit-modal {:section active-section
                                   :mode (:mode section-modal)
                                   :open? (some? section-modal)
                                   :project selected-project
                                   :initial-data (get supplement-data active-section)
                                   :files (get supplement-files active-section [])
                                   :set-files! #(set-supplement-files! (assoc supplement-files active-section %))
                                   :on-save (fn [section form files]
                                              (set-supplement-data! (assoc supplement-data section form))
                                              (set-supplement-files! (assoc supplement-files section files)))
                                   :on-close #(set-section-modal! nil)}])]

          (let [total (count solutions)
                paginated-solutions (vec (take page-size (drop (* (dec page) page-size) solutions)))
                columns (solution-columns
                         columns-config
                         #(set-editing! %)
                         #(api/delete-solution (:id %)
                                               (fn [_] (antd/success! "删除成功") (fetch!))
                                               (fn [_] (antd/error! "删除失败"))))]
            [solution-shell
             [page-search {:visible? show-search?}
              [:div {:style {:display "grid" :gridTemplateColumns "1.1fr 1.1fr 1.1fr 1.1fr 1.45fr" :gap 18}}
               [form-item "方案名称" [antd/input {:placeholder "请输入方案名称" :value (value query :solution_name) :style {:height 40} :on-change #(set-query! (assoc query :solution_name (target-value %)))}]]
               [form-item "所属项目" [solution-project-filter-select (:project_id query) projects #(set-query! (assoc query :project_id %))]]
               [form-item "方案类型" [solution-filter-select (value query :solution_type) "全部" solution-type-options #(set-query! (assoc query :solution_type %))]]
               [form-item "方案级别" [solution-filter-select (value query :solution_level) "全部" solution-level-options #(set-query! (assoc query :solution_level %))]]
               [form-item "生成时间" [antd/input {:placeholder "请选择日期" :style {:height 40}}]]]
              [:div {:style {:display "flex" :justifyContent "flex-end" :gap 12 :marginTop 24}}
               [antd/button {:icon (r/as-element [antd/reload-icon])
                             :on-click (fn []
                                         (set-query! {})
                                         (api/list-solutions {}
                                                             (fn [result] (when (ok? result) (set-solutions! (rows result))))
                                                             (fn [_])))}
                "重置"]
               [antd/button {:type "primary" :icon (r/as-element [antd/search-icon]) :on-click fetch!} "查询"]]]
             [page-toolbar
              {:left [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
                      [:span {:style {:fontSize 18 :fontWeight 600 :color "var(--ant-color-text, #1f2937)"}} "方案管理"]
                      [antd/button {:type "primary" :size "small" :icon (r/as-element [antd/plus-icon])
                                    :on-click #(set-workflow! :engineering)} "新增编制"]]
               :right [right-toolbar {:show-search? show-search?
                                      :columns columns-config
                                      :on-toggle-search #(set-show-search! (not show-search?))
                                      :on-refresh #(do (set-page! 1) (fetch!))
                                      :on-toggle-column #(set-columns-config!
                                                          (update-in columns-config [% :visible?] not))}]}]
             [antd/table {:rowKey "id"
                          :columns columns
                          :dataSource (clj->js paginated-solutions)
                          :scroll #js {:x "max-content"}
                          :pagination false}]
             (when (> total page-size)
               [:div {:style {:display "flex" :justifyContent "flex-end" :marginTop 12}}
                [pagination {:page page
                             :page-size page-size
                             :total total
                             :on-change (fn [p s]
                                          (set-page! p)
                                          (set-page-size! s))}]])]))))))

(def resource-config
  {:standard {:type "standard" :title "标准规范库" :button "添加资源"
              :fields [[:category "资源类型" :select] [:code "规范编码" :input] [:name "规范名称" :input]
                       [:tags "适用专项类型" :multi-select] [:attachment "上传文档" :upload]]}
   :vector-kb {:type "vector-kb" :title "通用知识库-章节级" :button "批量新增资源"
               :fields [[:attachment "上传文档" :upload] [:name "章节名称" :input]
                        [:category "适用方案类型" :select] [:tags "适用省份" :select]
                        [:vector_status "章节级别" :select]]}
   :structured-kb {:type "structured-kb" :title "结构化知识库" :button "新增资源"
                   :fields [[:name "知识名称" :input] [:category "知识分类" :select] [:structured_fields "属性字段" :textarea]
                            [:content "内容" :textarea] [:status "状态" :status] [:remark "备注" :textarea]]}
   :case {:type "case" :title "优秀案例库" :button "新增案例"
          :fields [[:name "方案名称" :input] [:related_project_name "项目名称" :input]
                   [:category "方案类型" :select] [:engineering_nature "工程性质" :select]
                   [:engineering_industry "工程业态" :select] [:province "项目所在地区" :select]
                   [:approval_time "审核通过时间" :input] [:case_level "方案级别" :select]
                   [:attachment "上传文档" :upload]]}
   :atlas {:type "atlas" :title "通用图集库" :button "批量新增图集"
           :fields [[:attachment "上传图片/图纸" :upload] [:name "图片名称" :input]
                    [:category "所属方案类型" :select] [:tags "适配地区" :select]
                    [:gallery "图库明细" :gallery] [:sort_order "排序" :input]]}})

(def resource-nav
  [{:kind :standard :label "标准规范" :path "/resource/standard"}
   {:kind :vector-kb :label "通用知识库" :path "/resource/vector-kb"}
   {:kind :case :label "优秀案例库" :path "/resource/case"}
   {:kind :atlas :label "通用图集库" :path "/resource/atlas"}])

(def resource-categories
  {:standard ["国家行政文件" "地方行政文件" "国家标准" "地方标准" "行业标准" "企业文件"]
   :vector-kb ["墙面工程" "地面工程" "吊篮工程" "顶面工程"]
   :structured-kb ["项目属性" "施工工艺" "技术参数" "验收标准"]
   :case ["顶面工程" "墙面工程" "地面工程" "基础设施"]
   :atlas ["墙面工程" "地面工程" "吊篮工程" "顶面工程"]})

(def resource-select-options
  {:engineering_nature ["医院/医疗卫生" "酒店" "教育建筑" "工业建筑" "商业商场"]
   :engineering_industry ["基础设施" "房建" "装饰装修" "市政公用"]
   :province ["山东省" "广东省" "河北省" "江苏省" "北京市" "上海市"]
   :case_level ["一般" "重点" "示范" "优秀"]
   :vector_status ["一级标题" "二级标题" "三级标题"]})

(def resource-extra-keys
  [:engineering_nature :engineering_industry :province :approval_time :case_level])

(defn- hydrate-resource
  [row]
  (let [row (merge (parse-extra-json (:extra_json row)) row)]
    (cond-> row
      (string? (:tags row)) (assoc :tags (if (seq (:tags row))
                                           (filterv seq (str/split (:tags row) #","))
                                           [])))))

(defn- resource-payload
  [form]
  (let [form (cond-> form
               (vector? (:tags form)) (assoc :tags (str/join "," (:tags form))))
        extra (select-keys form resource-extra-keys)]
    (-> form
        (as-> payload (reduce dissoc payload resource-extra-keys))
        (assoc :extra_json (.stringify js/JSON (clj->js extra))))))

(defn- resource-options
  [kind k]
  (case k
    :category (get resource-categories kind [])
    :tags (if (#{:vector-kb :atlas} kind)
            (:province resource-select-options)
            ["墙面工程" "地面工程" "吊篮工程" "顶面工程"])
    :vector_status (:vector_status resource-select-options)
    :engineering_nature (:engineering_nature resource-select-options)
    :engineering_industry (:engineering_industry resource-select-options)
    :province (:province resource-select-options)
    :case_level (:case_level resource-select-options)
    []))

(defn- multi-select-input
  [form set-form! k placeholder options]
  (let [selected (if (string? (get form k))
                   (filterv seq (str/split (get form k) #","))
                   (vec (or (get form k) [])))]
    [antd/select {:mode "multiple"
                  :value selected
                  :placeholder placeholder
                  :style {:width "100%" :height 36}
                  :on-change #(set-form! (assoc form k (str/join "," (js->clj %))))}
     (for [option options]
       ^{:key option} [antd/select-option {:value option} option])]))

(defn- resource-upload-placeholder
  [kind]
  (let [text (case kind
               :atlas "点击或将文件拖拽至框内上传；文件类型：JPG、JPEG、PNG"
               :vector-kb "点击或将文件拖拽至框内替换；文件类型：DOC/DOCX，文件大小不超过100MB"
               "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX/PDF，文件大小不超过100MB")]
    [:div {:style {:height (if (#{:vector-kb :atlas} kind) 170 140)
                   :width "100%"
                   :boxSizing "border-box"
                   :border "1px dashed var(--ant-color-border, #d9d9d9)"
                   :background "var(--ant-color-fill-quaternary, #f8fafc)"
                   :display "flex"
                   :flexDirection "column"
                   :alignItems "center"
                   :justifyContent "center"
                   :color "var(--ant-color-text-secondary, #8c8c8c)"
                   :gap 10
                   :padding 12
                   :fontSize 13
                   :textAlign "center"}}
     [antd/upload-icon {:style {:fontSize 30 :color "var(--ant-color-primary, #1677ff)"}}]
     [:div text]]))

(defn- resource-form-grid
  [kind & children]
  (into [:div {:style {:display "grid"
                       :gridTemplateColumns (if (#{:vector-kb :atlas} kind)
                                              "repeat(5, minmax(0, 1fr))"
                                              "1fr")
                       :gap "12px 16px"
                       :alignItems "start"}}]
        children))

(defn- resource-columns-config
  [kind]
  (case kind
    :standard {:code {:label "规范编码" :visible? true}
               :name {:label "规范名称" :visible? true}
               :category {:label "适用方案类型" :visible? true}
               :create_time {:label "创建时间" :visible? true}}
    :vector-kb {:name {:label "章节名称" :visible? true}
                :category {:label "适用方案类型" :visible? true}
                :tags {:label "适用省份" :visible? true}
                :vector_status {:label "章节级别" :visible? true}
                :create_time {:label "创建时间" :visible? true}}
    :structured-kb {:name {:label "知识名称" :visible? true}
                    :category {:label "知识分类" :visible? true}
                    :structured_fields {:label "属性字段" :visible? true}
                    :status {:label "状态" :visible? true}
                    :create_time {:label "创建时间" :visible? true}}
    :case {:name {:label "案例名称" :visible? true}
           :category {:label "案例分类" :visible? true}
           :related_project_name {:label "项目名称" :visible? true}
           :summary {:label "案例简介" :visible? true}
           :create_time {:label "创建时间" :visible? true}}
    :atlas {:image {:label "图片" :visible? true}
            :name {:label "图片名称" :visible? true}
            :category {:label "所属方案类型" :visible? true}
            :tags {:label "适配地区" :visible? true}
            :sort_order {:label "排序" :visible? true}}
    {}))

(defn- resource-columns
  [kind columns-config open-view! open-editor! delete!]
  (let [action {:title "操作" :key "action" :fixed "right" :width 150
                :render (fn [_ record]
                          (let [row (js->clj record :keywordize-keys true)]
                            (r/as-element
                             [action-menu
                              {:on-edit #(open-editor! row)
                               :on-delete #(delete! row)
                               :more-items [{:key :view :label "查看"
                                             :on-click #(open-view! row)}]}])))}]
    (clj->js
     (conj
      (filterv some?
               (case kind
                 :standard [(when (get-in columns-config [:code :visible?])
                              {:title "规范编码" :dataIndex "code" :key "code"})
                            (when (get-in columns-config [:name :visible?])
                              {:title "规范名称" :dataIndex "name" :key "name"})
                            (when (get-in columns-config [:category :visible?])
                              {:title "适用方案类型" :dataIndex "category" :key "category"})
                            (when (get-in columns-config [:create_time :visible?])
                              {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :vector-kb [(when (get-in columns-config [:name :visible?])
                               {:title "章节名称" :dataIndex "name" :key "name"})
                             (when (get-in columns-config [:category :visible?])
                               {:title "适用方案类型" :dataIndex "category" :key "category"})
                             (when (get-in columns-config [:tags :visible?])
                               {:title "适用省份" :dataIndex "tags" :key "tags"})
                             (when (get-in columns-config [:vector_status :visible?])
                               {:title "章节级别" :dataIndex "vector_status" :key "vector_status"})
                             (when (get-in columns-config [:create_time :visible?])
                               {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :structured-kb [(when (get-in columns-config [:name :visible?])
                                   {:title "知识名称" :dataIndex "name" :key "name"})
                                 (when (get-in columns-config [:category :visible?])
                                   {:title "知识分类" :dataIndex "category" :key "category"})
                                 (when (get-in columns-config [:structured_fields :visible?])
                                   {:title "属性字段" :dataIndex "structured_fields" :key "structured_fields"})
                                 (when (get-in columns-config [:status :visible?])
                                   {:title "状态" :dataIndex "status" :key "status" :width 100
                                    :render (fn [v _]
                                              (r/as-element
                                               [status-tag {:value v
                                                            :options {"0" {:label "正常" :color "green"}
                                                                      "1" {:label "停用" :color "red"}}}]))})
                                 (when (get-in columns-config [:create_time :visible?])
                                   {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :case [(when (get-in columns-config [:name :visible?])
                          {:title "案例名称" :dataIndex "name" :key "name"})
                        (when (get-in columns-config [:category :visible?])
                          {:title "案例分类" :dataIndex "category" :key "category"})
                        (when (get-in columns-config [:related_project_name :visible?])
                          {:title "项目名称" :dataIndex "related_project_name" :key "related_project_name"})
                        (when (get-in columns-config [:summary :visible?])
                          {:title "案例简介" :dataIndex "summary" :key "summary"})
                        (when (get-in columns-config [:create_time :visible?])
                          {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :atlas [(when (get-in columns-config [:image :visible?])
                           {:title "图片" :key "image" :width 80 :render (fn [] (r/as-element [:> PictureOutlined {:style {:fontSize 20 :color "var(--ant-color-primary, #1677ff)"}}]))})
                         (when (get-in columns-config [:name :visible?])
                           {:title "图片名称" :dataIndex "name" :key "name"})
                         (when (get-in columns-config [:category :visible?])
                           {:title "所属方案类型" :dataIndex "category" :key "category"})
                         (when (get-in columns-config [:tags :visible?])
                           {:title "适配地区" :dataIndex "tags" :key "tags"})
                         (when (get-in columns-config [:sort_order :visible?])
                           {:title "排序" :dataIndex "sort_order" :key "sort_order"})]
                 []))
      action))))

(defn- gallery-grid
  [gallery]
  [:div {:style {:marginTop 12}}
   [:h4 "图库"]
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(140px, 1fr))" :gap 12}}
    (for [g gallery]
      ^{:key (:id g)}
      [antd/card {:size "small"
                  :cover (r/as-element
                          [:div {:style {:height 90
                                         :background "var(--ant-color-fill-secondary, #f5f5f5)"
                                         :display "flex" :alignItems "center" :justifyContent "center"}}
                           [:> PictureOutlined {:style {:fontSize 32 :color "var(--ant-color-text-disabled, #999)"}}]])}
       [:div {:style {:fontSize 12}} (:image_title g)]
       (when (= "Y" (:is_cover g)) [antd/tag {:color "blue"} "封面"])])]])

(defn- resource-modal-width
  [kind]
  (case kind
    :standard 520
    :case 560
    :vector-kb 1120
    :atlas 1120
    760))

(defn- resource-form-field*
  [kind form gallery pending-files set-pending-files! attachments delete-attachment! readonly? [k label field-type]]
  (let [upload-text (case kind
                      :atlas "点击或将文件拖拽至框内上传；文件类型：JPG、JPEG、PNG"
                      :vector-kb "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX，文件大小不超过100MB"
                      "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX/PDF，文件大小不超过100MB")]
    (case field-type
      :upload [full-row
               [form-item label
                [:div
                 [attachment-list {:attachments attachments
                                   :readonly? readonly?
                                   :on-delete delete-attachment!}]
                 (when-not readonly?
                   [:div {:style {:marginTop 8}}
                    [pending-upload-box {:files pending-files
                                         :set-files! set-pending-files!
                                         :readonly? false
                                         :text upload-text}]])]]]
      :gallery [full-row
                [form-item label
                 (if (= kind :atlas) [gallery-grid gallery] [:span])]]
      :textarea [full-row
                 [form-field {:type :textarea :form form :name k :label label :full? true :disabled readonly?}]]
      :select [form-field {:type :select :form form :name k :label label :options (resource-options kind k) :disabled readonly?}]
      :multi-select [form-field {:type :multi-select :form form :name k :label label :options (resource-options kind k) :disabled readonly?}]
      :status [form-field {:type :status :form form :name k :label label :disabled readonly?}]
      [form-field {:type :input :form form :name k :label label :disabled readonly?}])))

(defn resource-page
  [kind]
  (let [{:keys [type title fields button]} (get resource-config kind)
        [items set-items!] (hooks/use-state [])
        [modal? set-modal!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)
        [viewing? set-viewing!] (hooks/use-state false)
        [form] (antd/form-use-form)
        [gallery set-gallery!] (hooks/use-state [])
        [attachments set-attachments!] (hooks/use-state [])
        [pending-files set-pending-files!] (hooks/use-state [])
        [show-search? set-show-search!] (hooks/use-state false)
        [columns-config set-columns-config!] (hooks/use-state (resource-columns-config kind))
        [page set-page!] (hooks/use-state 1)
        [page-size set-page-size!] (hooks/use-state 10)
        fetch! (fn []
                 (api/list-resources type {}
                                     #(when (ok? %) (set-items! (rows %)))
                                     (fn [_])))]
    (hooks/use-effect (fn [] (fetch!) js/undefined) [type])
    (let [reload-gallery! (fn [resource-id]
                            (when (= kind :atlas)
                              (api/list-gallery-items resource-id
                                                      #(when (ok? %) (set-gallery! (:data %)))
                                                      (fn [_]))))
          delete-attachment! (fn [attachment]
                               (api/delete-business-attachment (:id attachment)
                                                               (fn [result]
                                                                 (handle-result! result "删除成功"
                                                                                 (fn [_]
                                                                                   (set-attachments! (vec (remove #(= (:id %) (:id attachment)) attachments)))
                                                                                   (when (:id editing)
                                                                                     (reload-gallery! (:id editing))))
                                                                                 "删除失败"))
                                                               (fn [_] (antd/error! "删除失败"))))
          open-resource! (fn [row readonly?]
                           (set-editing! row)
                           (set-viewing! readonly?)
                           (set-gallery! [])
                           (set-attachments! (vec (or (:attachments row) [])))
                           (set-pending-files! [])
                           (.resetFields form)
                           (.setFieldsValue form (clj->js (hydrate-resource row)))
                           (set-modal! true)
                           (api/get-resource type (:id row)
                                             (fn [result]
                                               (when (ok? result)
                                                 (let [detail (:data result)]
                                                   (set-editing! detail)
                                                   (set-attachments! (vec (or (:attachments detail) [])))
                                                   (.resetFields form)
                                                   (.setFieldsValue form (clj->js (hydrate-resource detail))))))
                                             (fn [_] nil))
                           (reload-gallery! (:id row)))
          open-view! (fn [row] (open-resource! row true))
          open-editor! (fn [row] (open-resource! row false))
          delete! (fn [row]
                    (api/delete-resource type (:id row)
                                         (fn [_] (antd/success! "删除成功") (fetch!))
                                         (fn [_] (antd/error! "删除失败"))))
          columns (resource-columns kind columns-config open-view! open-editor! delete!)
          total (count items)
          paginated-items (vec (take page-size (drop (* (dec page) page-size) items)))]
      [:div
       [page-search {:visible? show-search?}]
       [page-toolbar
        {:left [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
                [:span {:style {:fontSize 18 :fontWeight 600 :color "var(--ant-color-text, #1f2937)"}} (str "资源管理 - " title)]
                [antd/button {:type "primary" :size "small" :icon (r/as-element [antd/plus-icon])
                              :on-click #(do (set-editing! nil)
                                             (set-viewing! false)
                                             (set-gallery! [])
                                             (set-attachments! [])
                                             (set-pending-files! [])
                                             (.resetFields form)
                                             (.setFieldsValue form #js {:status "0"})
                                             (set-modal! true))}
                 (or button "新增")]]
         :right [right-toolbar {:show-search? show-search?
                                :columns columns-config
                                :on-toggle-search #(set-show-search! (not show-search?))
                                :on-refresh #(do (set-page! 1) (fetch!))
                                :on-toggle-column #(set-columns-config!
                                                    (update-in columns-config [% :visible?] not))}]}]
       [antd/table {:rowKey "id"
                    :columns columns
                    :dataSource (clj->js paginated-items)
                    :scroll #js {:x "max-content"}
                    :pagination false}]
       (when (> total page-size)
         [:div {:style {:display "flex" :justifyContent "flex-end" :marginTop 12}}
          [pagination {:page page
                       :page-size page-size
                       :total total
                       :on-change (fn [p s]
                                    (set-page! p)
                                    (set-page-size! s))}]])
       [antd/modal (merge (modal-size (resource-modal-width kind))
                          (cond-> {:open modal? :title (cond
                                                         viewing? (str "查看" title)
                                                         (:id editing) (str "编辑" title)
                                                         :else (str "新增" title))
                                   :okText "保存" :cancelText "取消"
                                   :destroyOnHidden true
                                   :on-cancel #(set-modal! false)
                                   :on-ok #(when-not viewing? (.submit form))}
                            viewing? (assoc :footer nil)))
        [antd/form {:form form :layout "vertical"
                    :disabled viewing?
                    :on-finish (fn [values]
                                 (let [values (js->clj values :keywordize-keys true)
                                       finish! (fn []
                                                 (antd/success! "保存成功")
                                                 (set-modal! false)
                                                 (set-pending-files! [])
                                                 (fetch!))
                                       upload-after-save! (fn [resource-id]
                                                            (upload-files! {:biz-type type
                                                                            :biz-id resource-id
                                                                            :section-key ""
                                                                            :file-purpose (if (= kind :atlas) "gallery" "attachment")
                                                                            :files pending-files
                                                                            :on-uploaded (when (= kind :atlas)
                                                                                           (fn [result]
                                                                                             (api/create-gallery-item
                                                                                              resource-id
                                                                                              {:attachment_id (get-in result [:data :id])
                                                                                               :image_title (get-in result [:data :original_name])
                                                                                               :is_cover (if (empty? gallery) "Y" "N")}
                                                                                              (fn [_])
                                                                                              (fn [_] (antd/error! "图库明细创建失败")))))
                                                                            :on-done finish!}))]
                                   (if (:id editing)
                                     (api/update-resource type (:id editing) (resource-payload values)
                                                          (fn [result]
                                                            (handle-result! result nil (fn [_] (upload-after-save! (:id editing))) "保存失败"))
                                                          (fn [_] (antd/error! "保存失败")))
                                     (api/create-resource type (resource-payload values)
                                                          (fn [result]
                                                            (handle-result! result nil (fn [saved] (upload-after-save! (result-id saved))) "保存失败"))
                                                          (fn [_] (antd/error! "保存失败"))))))}
         (into [resource-form-grid kind]
               (map (fn [field]
                      ^{:key (name (first field))}
                      [resource-form-field* kind form gallery pending-files set-pending-files! attachments delete-attachment! viewing? field])
                    fields))]]])))
