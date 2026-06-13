(ns com.ruoyi.frontend.pages.business
  "工程方案与资源管理页面。"
  (:require
    ["@ant-design/icons" :refer [PlusOutlined SearchOutlined ReloadOutlined UploadOutlined EditOutlined DeleteOutlined EyeOutlined FileTextOutlined PictureOutlined]]
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
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
                    :border "1px solid #d9d9d9"
                    :borderLeft 0
                    :borderRadius "0 4px 4px 0"
                    :background "#fafafa"
                    :color "#595959"}}
     unit]]))


(defn- form-grid
  [& items]
  (into [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "10px 16px" :alignItems "start"}}] items))


(defn- form-item
  [label child]
  [:div {:style {:minWidth 0}}
   [:div {:style {:marginBottom 4 :fontWeight 500 :fontSize 13 :lineHeight "20px"}} label]
   child])


(defn- form-section
  [title & children]
  [:div {:style {:marginTop 14}}
   [:div {:style {:borderLeft "3px solid #1677ff" :paddingLeft 10 :marginBottom 10 :fontWeight 600 :color "#1f2937"}} title]
   (into [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "10px 16px" :alignItems "start"}}] children)])


(defn- full-row
  [child]
  [:div {:style {:gridColumn "1 / -1"}} child])


(defn- upload-placeholder
  [text]
  [:div {:style {:height 86 :width "100%" :boxSizing "border-box" :border "1px dashed #91caff" :background "#f5fbff"
                 :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                 :color "#8c8c8c" :gap 6 :padding 12 :fontSize 13 :textAlign "center"}}
   [:> UploadOutlined {:style {:fontSize 24 :color "#1677ff"}}]
   [:div text]])


(defn pending-upload-box
  [{:keys [files set-files! text readonly?]}]
  (let [content [:div {:style {:minHeight 96 :width "100%" :boxSizing "border-box" :border "1px dashed #91caff" :background "#f5fbff"
                               :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                               :color "#8c8c8c" :gap 8 :padding 12 :fontSize 13 :textAlign "center"
                               :cursor (if readonly? "default" "pointer")}}
                 [:> UploadOutlined {:style {:fontSize 24 :color "#1677ff"}}]
                 [:div (if readonly? "附件" text)]
                 (if (seq files)
                   [:div {:style {:width "100%" :display "grid" :gap 4 :marginTop 4}}
                    (for [[idx file] (map-indexed vector files)]
                      ^{:key idx}
                      [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                                     :gap 8 :padding "4px 8px" :borderRadius 4 :background "#fff"
                                     :border "1px solid #d6e4ff" :color "#1f2937"}}
                       [:span {:style {:overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}} (file-name file)]
                       (when-not readonly?
                         [:button {:type "button"
                                   :style {:border 0 :background "transparent" :color "#ff4d4f" :cursor "pointer"}
                                   :on-click (fn [e]
                                               (.stopPropagation e)
                                               (set-files! (vec (concat (subvec (vec files) 0 idx)
                                                                        (subvec (vec files) (inc idx))))))}
                          "移除"])])]
                   [:div {:style {:color "#8c8c8c"}} "暂无附件"])]]
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
                        :border "1px solid #d6e4ff"
                        :borderRadius 4
                        :background "#fff"}}
          [:span {:style {:minWidth 0 :overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}}
           (file-name attachment)]
          (when (and (not readonly?) on-delete)
            [antd/popconfirm {:title "确认删除该附件？" :okText "删除" :cancelText "取消"
                              :on-confirm #(on-delete attachment)}
             [antd/button {:type "link" :size "small" :danger true} "删除"]])])
       [:div {:style {:color "#8c8c8c" :fontSize 13}} "暂无已上传附件"])]))


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


(def project-fields
  project-overview-fields)


(defn- update-vector-item
  [form set-form! k idx updater]
  (let [items (vec (or (get form k) []))]
    (set-form! (assoc form k (mapv (fn [i item]
                                     (if (= i idx) (updater item) item))
                                   (range) items)))))


(defn- assoc-vector-field
  [form set-form! k idx field v]
  (update-vector-item form set-form! k idx #(assoc % field v)))


(defn- remove-vector-item
  [form set-form! k idx]
  (set-form! (assoc form k (vec (concat (subvec (vec (or (get form k) [])) 0 idx)
                                        (subvec (vec (or (get form k) [])) (inc idx)))))))


(defn- empty-subcontract-team
  []
  {:team_name "" :manager_name "" :technical_leader_name "" :safety_leader_name ""})


(defn- project-form-section
  [title columns & children]
  [:div {:style {:marginTop 14}}
   [:div {:style {:borderLeft "3px solid #1677ff" :paddingLeft 10 :marginBottom 10 :fontWeight 600 :color "#1f2937"}} title]
   (into [:div {:style {:display "grid"
                        :gridTemplateColumns (str "repeat(" columns ", minmax(0, 1fr))")
                        :gap "10px 16px"
                        :alignItems "start"}}]
         children)])


(defn- project-form-field
  ([form set-form! field] (project-form-field form set-form! nil false field))
  ([form set-form! upload-state field] (project-form-field form set-form! upload-state false field))
  ([form set-form! upload-state readonly? [k label type]]
   (case type
     :full-input [full-row [form-item label [input form set-form! k label readonly?]]]
     :textarea [full-row [form-item label [textarea form set-form! k label readonly?]]]
     :upload [form-item label (if upload-state
                                [pending-upload-box {:files (get-in upload-state [:files k] [])
                                                     :set-files! #(let [set-files! (:set-files! upload-state)]
                                                                    (set-files! (assoc (:files upload-state) k %)))
                                                     :readonly? readonly?
                                                     :text (if (= k :source_file)
                                                             "支持 docx、pdf、json、xlsx，文件大小不超过 100M"
                                                             "从项目综合管理系统导入项目基础信息")}]
                                [upload-placeholder (if (= k :source_file)
                                                      "支持 docx、pdf、json、xlsx，文件大小不超过 100M"
                                                      "从项目综合管理系统导入项目基础信息")])]
     :select [form-item label [select-input form set-form! k label (get project-select-options k []) readonly?]]
     :area [form-item label [unit-input form set-form! k label "万㎡" readonly?]]
     :days [form-item label [unit-input form set-form! k label "天" readonly?]]
     [form-item label [input form set-form! k label readonly?]])))


(defn- management-staff-section
  ([form set-form!] (management-staff-section form set-form! false))
  ([form set-form! readonly?]
   (let [staff (or (:management_staff form) (default-management-staff))]
     [:div {:style {:marginTop 14}}
      [:div {:style {:borderLeft "3px solid #1677ff" :paddingLeft 10 :marginBottom 10 :fontWeight 600 :color "#1f2937"}}
       "人员组织"]
      [:div {:style {:marginBottom 8 :fontWeight 600}} "总承包项目管理人员及职责分工"]
      [:div {:style {:border "1px solid #e5e7eb" :borderRadius 4 :overflow "hidden"}}
       [:div {:style {:display "grid" :gridTemplateColumns "70px 1.2fr 1.4fr 1.4fr" :background "#f8fafc" :fontWeight 600 :color "#374151"}}
        (for [title ["序号" "岗位名称" "姓名" "职称（资质）"]]
          ^{:key title} [:div {:style {:padding "9px 12px" :borderRight "1px solid #e5e7eb"}} title])]
       (for [[idx row] (map-indexed vector staff)]
         ^{:key (:role_key row)}
         [:div {:style {:display "grid" :gridTemplateColumns "70px 1.2fr 1.4fr 1.4fr" :borderTop "1px solid #e5e7eb" :alignItems "center"}}
          [:div {:style {:padding "8px 12px" :borderRight "1px solid #e5e7eb" :textAlign "center"}} (inc idx)]
          [:div {:style {:padding "8px 12px" :borderRight "1px solid #e5e7eb"}} (:role_name row)]
          [:div {:style {:padding 8 :borderRight "1px solid #e5e7eb"}}
           [antd/input {:value (value row :person_name)
                        :placeholder "姓名"
                        :disabled readonly?
                        :style {:height 34}
                        :on-change #(when-not readonly?
                                      (assoc-vector-field form set-form! :management_staff idx :person_name (target-value %)))}]]
          [:div {:style {:padding 8}}
           [antd/select {:value (value row :title)
                         :placeholder "职称（资质）"
                         :disabled readonly?
                         :style {:width "100%" :height 34}
                         :on-change #(when-not readonly?
                                       (assoc-vector-field form set-form! :management_staff idx :title %))}
            (for [title (:title project-select-options)]
              ^{:key title} [antd/select-option {:value title} title])]]])]])))


(defn- subcontract-team-row
  [form set-form! readonly? idx team]
  (let [roles [{:label "项目经理" :field :manager_name}
               {:label "项目技术负责人" :field :technical_leader_name}
               {:label "项目安全负责人" :field :safety_leader_name}]
        cell-style {:padding "14px 16px" :borderBottom "1px solid #f1f3f7"}]
    [:div {:style {:border "1px solid #eef0f4" :borderRadius 4 :overflow "hidden" :background "#fff"}}
     [:div {:style {:display "grid" :gridTemplateColumns "30% 13% 57%" :background "#fafafa" :fontWeight 700 :color "#3f4652"}}
      [:div {:style {:padding "12px 16px"}} "分包队伍"]
      [:div {:style {:padding "12px 16px"}} "管理职务"]
      [:div {:style {:padding "12px 16px"}} "姓名"]]
     [:div {:style {:display "grid" :gridTemplateColumns "30% 70%" :minHeight 220}}
      [:div {:style {:padding "18px 16px" :borderRight "1px solid #eef0f4"}}
       [antd/input {:value (value team :team_name)
                    :placeholder "请输入分包队伍名称"
                    :disabled readonly?
                    :style {:height 40 :width "100%"}
                    :on-change #(when-not readonly?
                                  (assoc-vector-field form set-form! :subcontract_teams idx :team_name (target-value %)))}]
       (when-not readonly?
         [antd/button {:type "link" :danger true :style {:padding 0 :marginTop 30}
                       :on-click #(remove-vector-item form set-form! :subcontract_teams idx)}
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
                                      (assoc-vector-field form set-form! :subcontract_teams idx field (target-value %)))}]]])]]]))


(defn- subcontract-teams-section
  ([form set-form!] (subcontract-teams-section form set-form! false))
  ([form set-form! readonly?]
   (let [teams (vec (or (:subcontract_teams form) []))]
     [:div {:style {:marginTop 14}}
      [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 10}}
       [:div {:style {:borderLeft "3px solid #1677ff" :paddingLeft 10 :fontWeight 600 :color "#1f2937"}}
        "分包单位及岗位人员的安全职责表"]
       (when-not readonly?
         [antd/button {:type "primary"
                       :icon (r/as-element [:> PlusOutlined])
                       :on-click #(set-form! (update form :subcontract_teams (fnil conj []) (empty-subcontract-team)))}
          "新增分包队伍"])]
      (if (empty? teams)
        [:div {:style {:height 64 :border "1px dashed #d9d9d9" :borderRadius 4 :display "flex" :alignItems "center" :justifyContent "center" :color "#8c8c8c"}}
         "暂无分包队伍，请点击上方按钮添加"]
        [:div {:style {:display "grid" :gap 16}}
         (for [[idx team] (map-indexed vector teams)]
           ^{:key idx}
           [subcontract-team-row form set-form! readonly? idx team])])])))


(defn project-form
  ([form set-form!] (project-form form set-form! nil))
  ([form set-form! upload-state] (project-form form set-form! upload-state false))
  ([form set-form! upload-state readonly?]
   [:div
    [project-form-section "数据来源" 2
     [project-form-field form set-form! upload-state readonly? [:source_file "上传施工组织设计方案并解析" :upload]]
     [project-form-field form set-form! upload-state readonly? [:engineering_source "关联综合管理系统导入项目" :upload]]]
    (into [project-form-section "项目概况" 3]
          (map (fn [field]
                 (with-meta [project-form-field form set-form! nil readonly? field]
                   {:key (name (first field))}))
               project-overview-fields))
    [management-staff-section form set-form! readonly?]
    [subcontract-teams-section form set-form! readonly?]]))


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
   [antd/button {:icon (r/as-element [:> UploadOutlined])} "上传附件"]])


(defn project-modal
  [{:keys [open? editing readonly? on-ok on-cancel]}]
  (let [[form set-form!] (hooks/use-state {})
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
          (if (:id editing)
            (do
              (set-form! (hydrate-project editing))
              (set-attachments! (vec (or (:attachments editing) [])))
              (api/get-project (:id editing)
                               (fn [result]
                                 (when (ok? result)
                                   (let [detail (:data result)]
                                     (set-form! (hydrate-project detail))
                                     (set-attachments! (vec (or (:attachments detail) []))))))
                               (fn [_] nil)))
            (do
              (set-form! (default-project-form))
              (set-attachments! []))))
        js/undefined)
      [open? editing])
    [antd/modal (merge (modal-size 1180)
                       (cond-> {:open open?
                                :title (cond
                                         readonly? "查看项目"
                                         (:id editing) "编辑项目"
                                         :else "新建项目")
                                :okText "保存" :cancelText "取消"
                                :destroyOnHidden true
                                :on-ok #(when-not readonly? (on-ok (project-payload form) upload-files))
                                :on-cancel on-cancel}
                         readonly? (assoc :footer nil)))
     [project-form form set-form! {:files upload-files :set-files! set-upload-files!} readonly?]
     [form-section "已上传附件"
      [full-row [attachment-list {:attachments attachments
                                  :readonly? readonly?
                                  :on-delete delete-attachment!}]]]]))


(defn team-modal
  [{:keys [open? project on-ok on-cancel]}]
  (let [[form set-form!] (hooks/use-state {})]
    (hooks/use-effect (fn [] (set-form! {}) js/undefined) [open?])
    [antd/modal (merge (modal-size 640)
                       {:open open? :title "新增分包队伍" :okText "保存" :cancelText "取消"
                        :on-ok #(on-ok form) :on-cancel on-cancel})
     [form-grid
      [form-item "分包队伍名称" [input form set-form! :team_name "分包队伍名称"]]
      [form-item "负责人" [input form set-form! :leader_name "负责人"]]
      [form-item "联系电话" [input form set-form! :contact_phone "联系电话"]]
      [form-item "分包内容" [textarea form set-form! :work_scope "分包内容"]]
      [form-item "备注" [textarea form set-form! :remark "备注"]]]
     [:div {:style {:marginTop 8 :color "#999"}} "所属项目：" (:project_name project)]]))


(defn- project-card
  [project on-view on-edit on-team on-delete]
  (let [project (hydrate-project project)]
    [antd/card {:style {:height "100%" :borderRadius 8 :boxShadow "0 8px 24px rgba(15, 23, 42, 0.06)"}
                :styles {:body {:padding 0}}}
     [:div {:style {:padding 20 :minHeight 190}}
      [:div {:style {:display "flex" :gap 14 :alignItems "flex-start"}}
       [:div {:style {:width 48 :height 48 :borderRadius 8 :background "#e8f1ff" :display "flex" :alignItems "center" :justifyContent "center"}}
        [:> FileTextOutlined {:style {:fontSize 24 :color "#1677ff"}}]]
       [:div {:style {:flex 1}}
        [:div {:style {:fontSize 17 :fontWeight 700 :lineHeight 1.45 :color "#1f2937"}} (:project_name project)]]]
      [:div {:style {:marginTop 18 :display "grid" :gap 10 :color "#5f6b7a" :fontSize 14}}
       [:div "方案数量：" [:span {:style {:color "#1677ff" :fontWeight 600}} (or (:solution_count project) 0)] " 个"]
       [:div "业态：" (or (:engineering_industry project) (:structure_type project) "-")]
       [:div "创建时间：" (or (:create_time project) "-")]
       [:div "工程性质：" (or (:engineering_nature project) "-")]]]
     [:div {:style {:borderTop "1px solid #f0f0f0" :padding "14px 20px" :display "flex" :justifyContent "flex-end" :gap 10}}
      [antd/button {:icon (r/as-element [:> EyeOutlined]) :on-click #(on-view project)} "查看"]
      [antd/button {:icon (r/as-element [:> EditOutlined]) :on-click #(on-edit project)} "编辑"]
      [antd/button {:on-click #(on-team project)} "新增分包队伍"]
      [antd/popconfirm {:title "确认删除？" :okText "确认" :cancelText "取消" :on-confirm #(on-delete project)}
       [antd/button {:danger true :icon (r/as-element [:> DeleteOutlined])} "删除"]]]]))


(defn project-page
  []
  (let [[items set-items!] (hooks/use-state [])
        [total-count set-total!] (hooks/use-state 0)
        [loading? set-loading!] (hooks/use-state false)
        [query set-query!] (hooks/use-state {})
        [modal? set-modal!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)
        [viewing? set-viewing!] (hooks/use-state false)
        [team? set-team!] (hooks/use-state false)
        [team-project set-team-project!] (hooks/use-state nil)
        fetch! (fn []
                 (set-loading! true)
                 (api/list-projects query
                                    (fn [result]
                                      (set-loading! false)
                                      (when (ok? result)
                                        (set-items! (rows result))
                                        (set-total! (total result))))
                                    (fn [_] (set-loading! false))))
        reset! (fn [] (set-query! {}) (js/setTimeout fetch! 0))
        delete! (fn [row]
                  (api/delete-project (:id row)
                                      (fn [_] (antd/success! "删除成功") (fetch!))
                                      (fn [_] (antd/error! "删除失败"))))]
    (hooks/use-effect (fn [] (fetch!) js/undefined) [])
    [:div {:style {:background "#f3f6fb" :minHeight "calc(100vh - 64px)" :padding "28px 32px"}}
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 24}}
      [:h1 {:style {:margin 0 :fontSize 28 :fontWeight 700 :color "#1f2937"}} "项目信息管理"]
      [antd/button {:type "primary" :size "large" :icon (r/as-element [:> PlusOutlined])
                    :on-click #(do (set-editing! nil) (set-viewing! false) (set-modal! true))} "新增项目"]]
     [antd/card {:style {:borderRadius 8 :marginBottom 24} :styles {:body {:padding 24}}}
      [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, minmax(180px, 1fr))" :gap 18}}
       [form-item "项目名称" [antd/input {:placeholder "请输入项目名称" :value (value query :project_name)
                                      :on-change #(set-query! (assoc query :project_name (target-value %)))}]]
       [form-item "创建时间" [antd/input {:placeholder "请选择日期" :value (value query :create_time)
                                      :on-change #(set-query! (assoc query :create_time (target-value %)))}]]
       [form-item "工程业态" [antd/input {:placeholder "全部" :value (value query :engineering_industry)
                                      :on-change #(set-query! (assoc query :engineering_industry (target-value %)))}]]
       [form-item "工程性质" [antd/input {:placeholder "全部" :value (value query :engineering_nature)
                                      :on-change #(set-query! (assoc query :engineering_nature (target-value %)))}]]]
      [:div {:style {:borderTop "1px solid #f0f0f0" :marginTop 20 :paddingTop 16 :display "flex" :justifyContent "flex-end" :gap 10}}
       [antd/button {:icon (r/as-element [:> ReloadOutlined]) :on-click reset!} "重置"]
       [antd/button {:type "primary" :icon (r/as-element [:> SearchOutlined]) :on-click fetch!} "查询"]]]
     (if loading?
       [antd/card {:loading true :style {:borderRadius 8}}]
       [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(330px, 1fr))" :gap 22}}
        (for [project items]
          ^{:key (:id project)}
          [project-card project
           (fn [row] (set-editing! row) (set-viewing! true) (set-modal! true))
           (fn [row] (set-editing! row) (set-viewing! false) (set-modal! true))
           (fn [row] (set-team-project! row) (set-team! true))
           delete!])])
     [:div {:style {:marginTop 24 :background "white" :borderRadius 8 :padding 16 :display "flex" :justifyContent "flex-end" :color "#6b7280"}}
      "共 " total-count " 条  10条/页"]
     [project-modal {:open? modal? :editing editing :readonly? viewing?
                     :on-ok (fn [form upload-files]
                              (let [all-files (vec (mapcat second upload-files))
                                    done (fn [biz-id]
                                           (upload-files! {:biz-type "project"
                                                           :biz-id biz-id
                                                           :section-key ""
                                                           :file-purpose "attachment"
                                                           :files all-files
                                                           :on-done #(do (antd/success! "保存成功")
                                                                         (set-modal! false)
                                                                         (fetch!))}))]
                                (if (:id editing)
                                  (api/update-project (:id editing) form
                                                      #(handle-result! % nil (fn [_] (done (:id editing))) "保存失败")
                                                      (fn [_] (antd/error! "保存失败")))
                                  (api/create-project form
                                                      #(handle-result! % nil (fn [result] (done (result-id result))) "保存失败")
                                                      (fn [_] (antd/error! "保存失败"))))))
                     :on-cancel #(set-modal! false)}]
     [team-modal {:open? team? :project team-project
                  :on-ok (fn [form]
                           (api/create-subcontract-team (:id team-project) form
                                                        #(do (antd/success! "保存成功") (set-team! false))
                                                        (fn [_] (antd/error! "保存失败"))))
                  :on-cancel #(set-team! false)}]]))


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
  (into [:div {:style {:background "#f3f6fb" :minHeight "calc(100vh - 64px)" :padding "28px 32px"}}
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


(defn- solution-table
  [solutions]
  [:div {:style {:marginTop 24 :background "#fff" :borderRadius 16 :overflow "hidden" :boxShadow "0 8px 24px rgba(15,23,42,0.06)"}}
   [:div {:style {:display "grid" :gridTemplateColumns "80px 2.1fr 1.05fr 2.1fr 1.05fr 1.2fr 1fr" :padding "18px 24px" :borderBottom "1px solid #eef0f4" :fontWeight 700 :color "#1f2937"}}
    (for [title ["序号" "方案名称" "方案类型" "所属项目" "方案级别" "生成时间" "编制人"]]
      ^{:key title} [:div title])]
   (if (seq solutions)
     (for [[idx row] (map-indexed vector solutions)]
       ^{:key (or (:id row) idx)}
       [:div {:style {:display "grid" :gridTemplateColumns "80px 2.1fr 1.05fr 2.1fr 1.05fr 1.2fr 1fr" :padding "22px 24px" :borderBottom "1px solid #f1f3f7" :alignItems "center" :fontSize 15 :lineHeight 1.7 :color "#263241"}}
        [:div {:style {:textAlign "center"}} (inc idx)]
        [:div {:style {:whiteSpace "normal" :wordBreak "break-all" :paddingRight 18}} (or (:solution_name row) "-")]
        [:div (or (:remark row) "墙面工程")]
        [:div {:style {:whiteSpace "normal" :wordBreak "break-all" :paddingRight 18}} (or (:project_name row) "-")]
        [:div [antd/tag {:color "blue"} "一般"]]
        [:div (short-date (or (:create_time row) (:update_time row)))]
        [:div (or (:create_by row) "开发者1")]])
     [:div {:style {:padding 32 :textAlign "center" :color "#8c8c8c"}} "暂无数据"])])


(defn- solution-project-card
  [project selected? on-click]
  [:div {:on-click on-click
         :style {:display "flex" :gap 14 :alignItems "center" :padding "14px 16px" :border (if selected? "1px solid #3b82f6" "1px solid #e5e7eb") :borderRadius 8 :background (if selected? "#eff6ff" "#fff") :cursor "pointer"}}
   [:div {:style {:width 46 :height 46 :borderRadius 8 :background "#e8f1ff" :display "flex" :alignItems "center" :justifyContent "center" :color "#3b82f6" :fontSize 22}} "▦"]
   [:div {:style {:flex 1 :minWidth 0}}
    [:div {:style {:fontWeight 700 :fontSize 15 :whiteSpace "nowrap" :overflow "hidden" :textOverflow "ellipsis"}} (or (:project_name project) "未命名项目")]
    [:div {:style {:marginTop 6 :display "flex" :gap 16 :color "#6b7280" :fontSize 13}}
     [:span "▣ " (or (:solution_count project) 0) "个方案"]
     [:span "◆"]]]])


(defn- solution-supplement-card
  [title completed? body & [on-preview on-edit]]
  [:div {:style {:border "1px solid #e5e7eb" :borderRadius 8 :background "#fff" :minHeight 230 :padding 18}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :borderBottom "1px solid #eef0f4" :paddingBottom 14}}
    [:div {:style {:fontWeight 700 :fontSize 16}} title]
    [:div {:style {:display "flex" :gap 10}}
     [antd/button {:icon (r/as-element [:> EyeOutlined]) :on-click #(when on-preview (on-preview))} "预览"]
     [antd/button {:icon (r/as-element [:> EditOutlined]) :on-click #(when on-edit (on-edit))} "编辑"]]]
   [:div {:style {:marginTop 16}}
    (if completed?
      [antd/tag {:color "success"} "已填写完成"]
      [antd/tag {:color "warning"} "尚未填写"])]
   [:div {:style {:marginTop 14 :color "#4b5563" :lineHeight 1.7}} body]])


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
  [:div {:style {:border "1px solid #e5e7eb" :borderRadius 12 :padding 24 :marginTop 18}}
   [:div {:style {:fontSize 18 :fontWeight 800 :borderBottom "1px solid #edf0f5" :paddingBottom 14 :marginBottom 18}} title]
   (into [:div] children)])


(defn- simple-staff-table
  [roles]
  [:div {:style {:border "1px solid #edf0f5" :borderRadius 4 :overflow "hidden"}}
   [:div {:style {:display "grid" :gridTemplateColumns "80px 1.2fr 1.6fr 1.6fr" :background "#fafafa" :fontWeight 700}}
    (for [h ["序号" "岗位名称" "姓名" "职称（资质）"]]
      ^{:key h} [:div {:style {:padding 12 :borderRight "1px solid #edf0f5"}} h])]
   (for [[idx role] (map-indexed vector roles)]
     ^{:key role}
     [:div {:style {:display "grid" :gridTemplateColumns "80px 1.2fr 1.6fr 1.6fr" :borderTop "1px solid #edf0f5"}}
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
        [:div {:style {:color "#8c8c8c" :marginBottom 18}} "以下为项目概况编辑模式。"]
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
        [:div {:style {:color "#8c8c8c" :marginBottom 18}} "以下为人员组织编辑模式。"]
        [solution-modal-section "总承包项目管理人员及职责分工"
         [simple-staff-table ["安全工程师" "技术工程师" "项目总工" "安全总监" "专业工程师" "责任工程师" "测量工程师" "商务经理" "质量总监" "试验工程师" "物资工程师" "商务工程师" "资料员" "项目经理"]]]
        [solution-modal-section "装饰管理部管理人员及职责分工"
         [simple-staff-table ["专业工程师" "装饰经理" "试验工程师" "深化设计师" "测量工程师" "资料员" "技术工程师" "装饰商务经理" "装饰安全总监" "装饰总工" "装饰生产经理" "设计总监" "物资工程师" "安全工程师" "机械管理员"]]]
        [solution-modal-section "分包单位及岗位人员的安全职责表"
         [:div {:style {:display "flex" :justifyContent "flex-end" :marginBottom 16}} [antd/button {:type "primary" :icon (r/as-element [:> PlusOutlined])} "新增分包队伍"]]
         [:div {:style {:height 90 :display "flex" :alignItems "center" :justifyContent "center" :color "#8c8c8c"}} "暂无分包队伍，请点击上方按钮添加"]]]

       :cover
       [:div
        [:div {:style {:color "#8c8c8c" :marginBottom 18}} "填写封面排版用字段；取消或遮罩关闭不保存本次修改。"]
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
        [:div {:style {:color "#8c8c8c" :marginBottom 18}} "当前仅编辑「设计概况」；保存只更新本区块。"]
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
  (let [[form set-form!] (hooks/use-state {})
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
          (set-form! (merge (solution-section-default section project) initial-data)))
        js/undefined)
      [open? section])
    [antd/modal (merge (modal-size 1180)
                       (cond-> {:open open? :title title
                                :okText "保存" :cancelText "取消"
                                :destroyOnHidden true
                                :on-ok #(do (when on-save (on-save section form files))
                                            (antd/success! "保存成功")
                                            (on-close))
                                :on-cancel on-close}
                         readonly? (assoc :footer nil)))
     (case section
       :project
       [:div
        [:div {:style {:color "#8c8c8c" :marginBottom 18}}
         (if readonly? "当前为预览模式，表单不可编辑。" "保存后会用于生成方案的项目信息章节。")]
        [project-form form set-form! nil readonly?]]

       :people
       [:div
        [:div {:style {:color "#8c8c8c" :marginBottom 18}}
         (if readonly? "当前为预览模式，表单不可编辑。" "保存后会用于生成方案的人员组织章节。")]
        [management-staff-section form set-form! readonly?]
        [subcontract-teams-section form set-form! readonly?]]

       :cover
       [:div
        [:div {:style {:color "#8c8c8c" :marginBottom 18}}
         (if readonly? "当前为预览模式，表单不可编辑。" "封面附件会在生成方案后自动上传。")]
        [solution-modal-section "封面信息"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "18px 28px"}}
          [full-row [form-item "项目名称" [input form set-form! :project_name "项目名称" readonly?]]]
          [form-item "方案类型" [select-input form set-form! :solution_type "方案类型" solution-type-options readonly?]]
          [form-item "日期" [input form set-form! :compile_date "日期" readonly?]]
          [full-row [form-item "项目渲染图"
                     [pending-upload-box {:files files
                                          :set-files! set-files!
                                          :readonly? readonly?
                                          :text "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"}]]]
          [form-item "编制单位" [input form set-form! :compile_unit "编制单位" readonly?]]
          [form-item "编制人" [input form set-form! :compiler "编制人" readonly?]]
          [form-item "制图人" [input form set-form! :draftsman "制图人" readonly?]]
          [form-item "项目负责人" [input form set-form! :project_leader "项目负责人" readonly?]]
          [form-item "审核人" [input form set-form! :reviewer "审核人" readonly?]]]]]

       :design
       [:div
        [solution-modal-section "设计概况"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
          [form-item "总建筑面积（㎡）" [input form set-form! :total_building_area "如 181247.08" readonly?]]
          [form-item "地上建筑面积（㎡）" [input form set-form! :ground_building_area "如 101064.08" readonly?]]
          [form-item "地下建筑面积（㎡）" [input form set-form! :underground_building_area "如 80183" readonly?]]
          [form-item "地下层数（层）" [input form set-form! :underground_floors "如 3" readonly?]]
          [form-item "地上层数（层）" [input form set-form! :ground_floors "如 17" readonly?]]
          [form-item "裙房层数（层）" [input form set-form! :podium_floors "如 5" readonly?]]
          [form-item "地下层高（m）" [input form set-form! :underground_height "如 4" readonly?]]
          [form-item "首层层高（m）" [input form set-form! :first_floor_height "如 5.4" readonly?]]
          [form-item "标准层层高（m）" [input form set-form! :standard_floor_height "如 4.2" readonly?]]
          [form-item "防火等级" [input form set-form! :fire_rating "如 A1" readonly?]]]
         (for [[k label] [[:floor_material "楼地面"] [:wall_material "墙面"] [:ceiling_material "顶棚"]
                          [:stair_material "楼梯"] [:machine_room_material "机房（地面 / 墙面 / 顶棚）"]
                          [:window_material "窗"] [:waterproof_material "防水"]]]
           ^{:key (name k)}
           [:div {:style {:marginTop 18}}
            [form-item label [select-input form set-form! k "选择材料添加..." ["乳胶漆" "瓷砖" "石材"] readonly?]]])
         [:div {:style {:marginTop 18}}
          [form-item "环境保护" [textarea form set-form! :environment_protection "遵守国家及地方政府关于环境保护、水土保持等要求" readonly?]]]]]

       :layout
       [:div
        [solution-modal-section "平面布置图"
         [form-item "文字说明" [textarea form set-form! :drawing_desc "描述施工区域、楼层、道路与应急布置等。" readonly?]]
         [:div {:style {:marginTop 18}}
          [form-item "上传平面布置图"
           [pending-upload-box {:files files
                                :set-files! set-files!
                                :readonly? readonly?
                                :text "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"}]]]]]

       [:span])]))


(defn solution-home-page
  []
  (let [[solutions set-solutions!] (hooks/use-state [])
        [projects set-projects!] (hooks/use-state [])
        [engineerings set-engineerings!] (hooks/use-state [])
        [editing set-editing!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state {})
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
                        :style {:height 58 :minWidth 240 :borderRadius 30 :border (if (= category selected-category) "1px solid #8bbcff" "1px solid #d9dee7") :background (if (= category selected-category) "#eaf4ff" "#fff") :color (if (= category selected-category) "#2f7ff0" "#374151") :fontSize 18 :fontWeight 700 :cursor "pointer"}}
               category])]
           [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(260px, 1fr))" :gap 28}}
            (for [{:keys [key title desc icon available?] :as card} solution-plan-cards]
              ^{:key key}
              [:div {:on-click #(when available?
                                  (set-selected-plan! card)
                                  (set-project-picker! true))
                     :style {:height 290 :border "1px solid #e5e7eb" :borderRadius 16 :background "#fff" :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center" :cursor (if available? "pointer" "not-allowed") :opacity (if available? 1 0.45)}}
               [:div {:style {:width 60 :height 60 :borderRadius 12 :background "#eef5ff" :display "flex" :alignItems "center" :justifyContent "center" :fontSize 28 :color "#3b82f6"}} icon]
               [:div {:style {:marginTop 22 :fontSize 21 :fontWeight 700}} title]
               [:div {:style {:marginTop 8 :color "#8c8c8c"}} desc]
               [:div {:style {:marginTop 16 :padding "5px 16px" :borderRadius 20 :background (if available? "#eaf4ff" "#f1f2f4") :color (if available? "#2f7ff0" "#8c8c8c") :fontWeight 700}}
                (if available? "• 可使用" "暂未开放")]])]
           [antd/modal (merge (modal-size 620)
                              {:open project-picker? :title "选择项目" :footer nil :on-cancel #(set-project-picker! false)})
            [:div {:style {:display "grid" :gap 12}}
             [:div {:style {:fontSize 16 :color "#374151"}} "请选择当前方案所属项目："]
             (for [project filtered-projects]
               ^{:key (:id project)}
               [solution-project-card project (= (:id project) (:id selected-project))
                #(do (set-selected-project! project)
                     (set-project-picker! false)
                     (set-workflow! :supplement))])
             [:div {:style {:borderTop "1px solid #edf0f5" :marginTop 10 :paddingTop 18 :color "#6b7280"}}
              [:div "没有找到合适的项目？"]
              [antd/button {:type "link" :icon (r/as-element [:> PlusOutlined]) :on-click #(set-project-modal! true)} "新建项目"]]]]
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
           [:div {:style {:background "#fff" :borderRadius 18 :padding 26 :boxShadow "0 8px 24px rgba(15,23,42,0.06)"}}
            [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between" :borderBottom "1px solid #e5e7eb" :paddingBottom 22}}
             [:div {:style {:display "flex" :alignItems "center" :gap 14}}
              [antd/button {:on-click #(set-workflow! :engineering)} "←"]
              [:h1 {:style {:margin 0 :fontSize 24 :fontWeight 800}} "补充信息"]]
             [antd/button {:type "primary" :size "large" :on-click create-solution!} "生成方案"]]
            [:div {:style {:marginTop 30}}
             [:div {:style {:fontSize 18 :fontWeight 800 :marginBottom 6}} "关联项目信息"]
             [:div {:style {:color "#667085" :marginBottom 22}} "确认本次生成方案所使用的项目概况、人员组织与方案封面信息。"]
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
             [:div {:style {:marginTop 34 :fontSize 18 :fontWeight 800}} "个性化参数"]
             [:div {:style {:marginTop 14 :border "1px solid #e5e7eb" :borderRadius 8 :padding 18}}
              [form-item "方案级别" [antd/select {:value "一般" :style {:width 300 :height 40}}
                                 [antd/select-option {:value "一般"} "一般"]
                                 [antd/select-option {:value "重点"} "重点"]]]
              [:div {:style {:height 1 :background "#edf0f5" :margin "18px 0"}}]
              [:div {:style {:fontSize 16 :fontWeight 700 :marginBottom 16}} "设计概况与平面布置图"]
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

          [solution-shell
           [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 28}}
            [:h1 {:style {:margin 0 :fontSize 28 :fontWeight 800 :color "#1f2937"}} "方案管理"]
            [antd/button {:type "primary" :size "large" :icon (r/as-element [:> PlusOutlined]) :on-click #(set-workflow! :engineering)} "新增编制"]]
           [:div {:style {:background "#fff" :borderRadius 16 :padding 24 :boxShadow "0 8px 24px rgba(15,23,42,0.06)"}}
            [:div {:style {:display "grid" :gridTemplateColumns "1.1fr 1.1fr 1.1fr 1.1fr 1.45fr" :gap 18}}
             [form-item "方案名称" [antd/input {:placeholder "请输入方案名称" :value (value query :solution_name) :style {:height 40} :on-change #(set-query! (assoc query :solution_name (target-value %)))}]]
             [form-item "所属项目" [solution-project-filter-select (:project_id query) projects #(set-query! (assoc query :project_id %))]]
             [form-item "方案类型" [solution-filter-select (value query :solution_type) "全部" solution-type-options #(set-query! (assoc query :solution_type %))]]
             [form-item "方案级别" [solution-filter-select (value query :solution_level) "全部" solution-level-options #(set-query! (assoc query :solution_level %))]]
             [form-item "生成时间" [antd/input {:placeholder "请选择日期" :style {:height 40}}]]]
            [:div {:style {:display "flex" :justifyContent "flex-end" :gap 12 :marginTop 24}}
             [antd/button {:icon (r/as-element [:> ReloadOutlined])
                           :on-click (fn []
                                       (set-query! {})
                                       (api/list-solutions {}
                                                           (fn [result] (when (ok? result) (set-solutions! (rows result))))
                                                           (fn [_])))}
              "重置"]
             [antd/button {:type "primary" :icon (r/as-element [:> SearchOutlined]) :on-click fetch!} "查询"]]]
           [solution-table solutions]])))))


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
  (merge (parse-extra-json (:extra_json row)) row))


(defn- resource-payload
  [form]
  (let [extra (select-keys form resource-extra-keys)]
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
                   :border "1px dashed #d9d9d9"
                   :background "#f8fafc"
                   :display "flex"
                   :flexDirection "column"
                   :alignItems "center"
                   :justifyContent "center"
                   :color "#8c8c8c"
                   :gap 10
                   :padding 12
                   :fontSize 13
                   :textAlign "center"}}
     [:> UploadOutlined {:style {:fontSize 30 :color "#1677ff"}}]
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


(defn- resource-columns
  [kind open-view! open-editor! delete!]
  (let [action {:title "操作" :key "action" :fixed "right" :width 150
                :render (fn [_ record]
                          (let [row (js->clj record :keywordize-keys true)]
                            (r/as-element
                              [antd/space
                               [antd/button {:type "link" :size "small" :on-click #(open-view! row)} "查看"]
                               [antd/button {:type "link" :size "small" :on-click #(open-editor! row)} "编辑"]
                               [antd/button {:type "link" :size "small" :danger true :on-click #(delete! row)} "删除"]])))}]
    (clj->js
      (conj
        (case kind
          :standard [{:title "序号" :key "index" :width 70 :render (fn [_ _ idx] (inc idx))}
                     {:title "规范编码" :dataIndex "code" :key "code"}
                     {:title "规范名称" :dataIndex "name" :key "name"}
                     {:title "适用方案类型" :dataIndex "category" :key "category"}
                     {:title "创建时间" :dataIndex "create_time" :key "create_time"}]
          :vector-kb [{:title "章节名称" :dataIndex "name" :key "name"}
                      {:title "适用方案类型" :dataIndex "category" :key "category"}
                      {:title "适用省份" :dataIndex "tags" :key "tags"}
                      {:title "章节级别" :dataIndex "vector_status" :key "vector_status"}
                      {:title "创建时间" :dataIndex "create_time" :key "create_time"}]
          :structured-kb [{:title "知识名称" :dataIndex "name" :key "name"}
                          {:title "知识分类" :dataIndex "category" :key "category"}
                          {:title "属性字段" :dataIndex "structured_fields" :key "structured_fields"}
                          {:title "状态" :dataIndex "status" :key "status" :render #(r/as-element [antd/tag {:color "blue"} (status-label %)])}
                          {:title "创建时间" :dataIndex "create_time" :key "create_time"}]
          :case [{:title "案例名称" :dataIndex "name" :key "name"}
                 {:title "案例分类" :dataIndex "category" :key "category"}
                 {:title "项目名称" :dataIndex "related_project_name" :key "related_project_name"}
                 {:title "案例简介" :dataIndex "summary" :key "summary"}
                 {:title "创建时间" :dataIndex "create_time" :key "create_time"}]
          :atlas [{:title "图片" :key "image" :width 80 :render (fn [] (r/as-element [:> PictureOutlined {:style {:fontSize 20 :color "#1677ff"}}]))}
                  {:title "图片名称" :dataIndex "name" :key "name"}
                  {:title "所属方案类型" :dataIndex "category" :key "category"}
                  {:title "适配地区" :dataIndex "tags" :key "tags"}
                  {:title "排序" :dataIndex "sort_order" :key "sort_order"}]
          [])
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
                           [:div {:style {:height 90 :background "#f5f5f5" :display "flex" :alignItems "center" :justifyContent "center"}}
                            [:> PictureOutlined {:style {:fontSize 32 :color "#999"}}]])}
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


(defn- resource-form-field
  [kind resource-type editing form set-form! gallery set-gallery! pending-files set-pending-files! attachments delete-attachment! readonly? [k label field-type]]
  (let [upload-text (case kind
                      :atlas "点击或将文件拖拽至框内上传；文件类型：JPG、JPEG、PNG"
                      :vector-kb "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX，文件大小不超过100MB"
                      "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX/PDF，文件大小不超过100MB")
        control (case field-type
                  :textarea [textarea form set-form! k label readonly?]
                  :status [select-status form set-form! readonly?]
                  :select [select-input form set-form! k label (resource-options kind k) readonly?]
                  :multi-select (if readonly?
                                  [select-input form set-form! k label (resource-options kind k) true]
                                  [multi-select-input form set-form! k label (resource-options kind k)])
                  :upload [:div
                           [attachment-list {:attachments attachments
                                             :readonly? readonly?
                                             :on-delete delete-attachment!}]
                           (when-not readonly?
                             [:div {:style {:marginTop 8}}
                              [pending-upload-box {:files pending-files
                                                   :set-files! set-pending-files!
                                                   :readonly? false
                                                   :text upload-text}]])]
                  :gallery (if (= kind :atlas) [gallery-grid gallery] [:span])
                  [input form set-form! k label readonly?])
        item [form-item label control]]
    (if (or (= field-type :upload) (= field-type :gallery) (= field-type :textarea))
      [full-row item]
      item)))


(defn resource-page
  [kind]
  (let [{:keys [type title fields button]} (get resource-config kind)
        [items set-items!] (hooks/use-state [])
        [modal? set-modal!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)
        [viewing? set-viewing!] (hooks/use-state false)
        [form set-form!] (hooks/use-state {})
        [gallery set-gallery!] (hooks/use-state [])
        [attachments set-attachments!] (hooks/use-state [])
        [pending-files set-pending-files!] (hooks/use-state [])
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
                           (set-form! (hydrate-resource row))
                           (set-gallery! [])
                           (set-attachments! (vec (or (:attachments row) [])))
                           (set-pending-files! [])
                           (set-modal! true)
                           (api/get-resource type (:id row)
                                             (fn [result]
                                               (when (ok? result)
                                                 (let [detail (:data result)]
                                                   (set-editing! detail)
                                                   (set-form! (hydrate-resource detail))
                                                   (set-attachments! (vec (or (:attachments detail) []))))))
                                             (fn [_] nil))
                           (reload-gallery! (:id row)))
          open-view! (fn [row] (open-resource! row true))
          open-editor! (fn [row] (open-resource! row false))
          delete! (fn [row]
                    (api/delete-resource type (:id row)
                                         (fn [_] (antd/success! "删除成功") (fetch!))
                                         (fn [_] (antd/error! "删除失败"))))
          columns (resource-columns kind open-view! open-editor! delete!)]
      [:div
       [page-card (str "资源管理 - " title)
        [antd/button {:type "primary" :icon (r/as-element [:> PlusOutlined])
                      :on-click #(do (set-editing! nil)
                                     (set-viewing! false)
                                     (set-form! {:status "0"})
                                     (set-gallery! [])
                                     (set-attachments! [])
                                     (set-pending-files! [])
                                     (set-modal! true))}
         (or button "新增")]
        [antd/table {:rowKey "id" :columns columns :dataSource (clj->js items)
                     :scroll #js {:x 900} :pagination {:pageSize 10}}]]
       [antd/modal (merge (modal-size (resource-modal-width kind))
                          (cond-> {:open modal? :title (cond
                                                         viewing? (str "查看" title)
                                                         (:id editing) (str "编辑" title)
                                                         :else (str "新增" title))
                                   :okText "保存" :cancelText "取消"
                                   :destroyOnHidden true
                                   :on-cancel #(set-modal! false)
                                   :on-ok #(when-not viewing?
                                             (let [finish! (fn []
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
                                                 (api/update-resource type (:id editing) (resource-payload form)
                                                                      (fn [result]
                                                                        (handle-result! result nil (fn [_] (upload-after-save! (:id editing))) "保存失败"))
                                                                      (fn [_] (antd/error! "保存失败")))
                                                 (api/create-resource type (resource-payload form)
                                                                      (fn [result]
                                                                        (handle-result! result nil (fn [saved] (upload-after-save! (result-id saved))) "保存失败"))
                                                                      (fn [_] (antd/error! "保存失败"))))))}
                            viewing? (assoc :footer nil)))
        (into [resource-form-grid kind]
              (map (fn [field]
                     (with-meta [resource-form-field kind type editing form set-form! gallery set-gallery! pending-files set-pending-files! attachments delete-attachment! viewing? field]
                       {:key (name (first field))}))
                   fields))]])))
