(ns com.ruoyi.frontend.pages.business.bpm-model-editor
  "流程模型全屏编辑器 —— 对齐 vben model/form/index.vue：
   顶部固定导航条（返回 + 流程名 / 1-4 步骤条 / 保存·发布）+ 分步内容区。
   基本信息 / 表单设计 / 更多设置 居中 760px，流程设计全宽。"
  (:require
    ["@ant-design/icons" :refer [ArrowLeftOutlined SaveOutlined RocketOutlined]]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.pages.business.bpm-model :as bpm-model]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def ^:private key-pattern #"^[a-zA-Z_][-\w.$]*$")


(def ^:private steps
  [{:key "basic" :label "基本信息" :num 1}
   {:key "form" :label "表单设计" :num 2}
   {:key "process" :label "流程设计" :num 3}
   {:key "extra" :label "更多设置" :num 4}])


(defn- query-id
  "从路由 query (?id=) 取模型 id。"
  []
  (.get (js/URLSearchParams. (.-search js/location)) "id"))


(defn bpm-model-editor-page
  []
  (let [model-id (query-id)
        [detail set-detail!] (hooks/use-state nil)
        [loading? set-loading!] (hooks/use-state true)
        [error? set-error!] (hooks/use-state false)
        [saving? set-saving!] (hooks/use-state false)
        [publishing? set-publishing!] (hooks/use-state false)
        [tab set-tab!] (hooks/use-state "basic")
        ;; 基本信息
        [mname set-mname!] (hooks/use-state "")
        [mkey set-mkey!] (hooks/use-state "")
        [mcat set-mcat!] (hooks/use-state "")
        [mform-type set-mform-type!] (hooks/use-state "0")
        [mform-id set-mform-id!] (hooks/use-state nil)
        [mform-json set-mform-json!] (hooks/use-state "")
        [mcustom-create set-mcustom-create!] (hooks/use-state "")
        [mcustom-view set-mcustom-view!] (hooks/use-state "")
        [form-list set-form-list!] (hooks/use-state [])
        [mremark set-mremark!] (hooks/use-state "")
        [mfields-perm set-mfields-perm!] (hooks/use-state {})
        ;; 更多设置（Phase 3/4 治理能力 + P0-4 权限开关）
        [mauto-type set-mauto-type!] (hooks/use-state "NONE")
        [mname-rule set-mname-rule!] (hooks/use-state "")
        [mprocess-rule set-mprocess-rule!] (hooks/use-state {:enable false :prefix "" :infix "DAY" :suffix "" :length 5})
        [msummary-fields set-msummary-fields!] (hooks/use-state [])
        [mprint-enable set-mprint-enable!] (hooks/use-state false)
        [mprint-html set-mprint-html!] (hooks/use-state "")
        [mwebhooks set-mwebhooks!] (hooks/use-state {})
        [mallow-cancel set-mallow-cancel!] (hooks/use-state "1")
        [mallow-withdraw set-mallow-withdraw!] (hooks/use-state "1")
        ;; 基本信息扩展：图标 / 谁可发起 / 流程管理员
        [micon set-micon!] (hooks/use-state "")
        [mstart-type set-mstart-type!] (hooks/use-state "ALL")
        [mstart-user-ids set-mstart-user-ids!] (hooks/use-state [])
        [mstart-dept-ids set-mstart-dept-ids!] (hooks/use-state [])
        [mmanager-ids set-mmanager-ids!] (hooks/use-state [])
        [categories set-categories!] (hooks/use-state [])
        [users set-users!] (hooks/use-state [])
        [depts set-depts!] (hooks/use-state [])
        form-fields (let [sel-form (first (filter #(= (:form_id %) mform-id) form-list))
                          schema (or (when-let [j (:form_json sel-form)]
                                       (if (string? j)
                                         (js->clj (js/JSON.parse j) :keywordize-keys true)
                                         (walk/keywordize-keys j)))
                                     {:fields []})]
                      (filter :field (:fields schema)))
        back! #(rf/dispatch [:navigate :bpm-model])
        ;; 汇总 13+ 个编辑字段（与弹窗版保存逻辑一致）
        collect-updates (fn []
                          {:model_name mname :model_key mkey
                           :category_id (some-> mcat js/Number) :form_type mform-type
                           :form_id (some-> mform-id js/Number)
                           :form_custom_create_path mcustom-create
                           :form_custom_view_path mcustom-view
                           :form_json mform-json :remark mremark
                           :fields_permission (js/JSON.stringify (clj->js mfields-perm))
                           :auto_approval_type mauto-type
                           :name_rule mname-rule
                           :process_id_rule (js/JSON.stringify (clj->js mprocess-rule))
                           :summary_fields (js/JSON.stringify (clj->js msummary-fields))
                           :print_template_enable (if mprint-enable "1" "0")
                           :print_template_html mprint-html
                           :webhooks (js/JSON.stringify (clj->js mwebhooks))
                           :allow_cancel mallow-cancel
                           :allow_withdraw mallow-withdraw
                           :icon (or micon "")
                           :start_user_ids (js/JSON.stringify
                                             (clj->js (if (= "USER" mstart-type) mstart-user-ids [])))
                           :start_dept_ids (js/JSON.stringify
                                             (clj->js (if (= "DEPT" mstart-type) mstart-dept-ids [])))
                           :manager_user_ids (js/JSON.stringify (clj->js mmanager-ids))})
        save-model! (fn [cb]
                      (when (and detail (not saving?))
                        (let [updates (collect-updates)]
                          (set-saving! true)
                          (api/bpm-update-model
                            (:model_id detail)
                            (merge detail updates)
                            (fn [res]
                              (set-saving! false)
                              (if (= 200 (:code res))
                                (do (antd/success! "流程保存成功")
                                    (set-detail! (merge detail updates))
                                    (rf/dispatch [:bpm/model-fetch {:page 1 :size 1000}])
                                    (when cb (cb)))
                                (antd/error! (str "保存失败: " (:msg res)))))
                            (fn [_] (set-saving! false) (antd/error! "保存失败"))))))
        publish! (fn []
                   (when (and detail (not publishing?))
                     (set-publishing! true)
                     (save-model!
                       (fn []
                         (api/bpm-deploy-model
                           (:model_id detail)
                           (fn [res]
                             (set-publishing! false)
                             (if (= 200 (:code res))
                               (do (antd/success! "发布成功")
                                   (rf/dispatch [:bpm/model-fetch {:page 1 :size 1000}])
                                   ;; 重新拉取详情，更新 deployment_id / version 状态
                                   (api/bpm-get-model (:model_id detail)
                                                      (fn [r2]
                                                        (when (= 200 (:code r2))
                                                          (set-detail! (walk/keywordize-keys (:data r2)))))
                                                      #()))
                               (antd/error! (str "发布失败: " (:msg res)))))
                           (fn [_] (set-publishing! false) (antd/error! "发布失败")))))))
        ;; 步骤切换：离开「基本信息」时校验名称非空 + Key 格式
        switch-tab! (fn [target]
                      (if (and (= tab "basic") (not= target "basic"))
                        (cond
                          (not (seq (str/trim mname)))
                          (antd/warning! "请输入流程名称后再进行后续设计")
                          (not (re-matches key-pattern (str mkey)))
                          (antd/warning! "流程Key须以字母或下划线开头，只能包含字母、数字、_ - . $")
                          :else (set-tab! target))
                        (set-tab! target)))]
    ;; 挂载 / id 变化：拉取模型详情 + 初始化全部字段，并行加载下拉数据源
    (hooks/use-effect
      (fn []
        (if (seq model-id)
          (api/bpm-get-model model-id
                             (fn [res]
                               (set-loading! false)
                               (if (= 200 (:code res))
                                 (let [current (walk/keywordize-keys (:data res))]
                                   (set-detail! current)
                                   (set-mname! (or (:model_name current) ""))
                                   (set-mkey! (or (:model_key current) ""))
                                   (set-mcat! (let [c (:category_id current)]
                                                (if (and (number? c) (zero? c)) "" (or c ""))))
                                   (set-mform-type! (or (:form_type current) "0"))
                                   (set-mform-id! (or (:form_id current) nil))
                                   (set-mform-json! (or (:form_json current) ""))
                                   (set-mcustom-create! (or (:form_custom_create_path current) ""))
                                   (set-mcustom-view! (or (:form_custom_view_path current) ""))
                                   (set-mremark! (or (:remark current) ""))
                                   (set-mfields-perm!
                                     (or (when-let [fp (:fields_permission current)]
                                           (if (string? fp)
                                             (js->clj (js/JSON.parse fp) :keywordize-keys true)
                                             (walk/keywordize-keys fp)))
                                         {}))
                                   (set-mauto-type! (or (:auto_approval_type current) "NONE"))
                                   (set-mname-rule! (or (:name_rule current) ""))
                                   (set-mprocess-rule!
                                     (or (when-let [pr (:process_id_rule current)]
                                           (if (string? pr)
                                             (js->clj (js/JSON.parse pr) :keywordize-keys true)
                                             (walk/keywordize-keys pr)))
                                         {:enable false :prefix "" :infix "DAY" :suffix "" :length 5}))
                                   (set-msummary-fields!
                                     (or (when-let [sf (:summary_fields current)]
                                           (if (string? sf)
                                             (js->clj (js/JSON.parse sf))
                                             (walk/keywordize-keys sf)))
                                         []))
                                   (set-mprint-enable! (= "1" (str (:print_template_enable current))))
                                   (set-mprint-html! (or (:print_template_html current) ""))
                                   (set-mwebhooks! (or (when-let [w (:webhooks current)]
                                                         (if (string? w)
                                                           (js->clj (js/JSON.parse w) :keywordize-keys true)
                                                           (walk/keywordize-keys w)))
                                                       {}))
                                   (set-mallow-cancel! (or (:allow_cancel current) "1"))
                                   (set-mallow-withdraw! (or (:allow_withdraw current) "1"))
                                   (set-micon! (or (:icon current) ""))
                                   (set-mstart-type! (if (seq (:start_user_ids current)) "USER"
                                                         (if (seq (:start_dept_ids current)) "DEPT" "ALL")))
                                   (set-mstart-user-ids! (vec (or (:start_user_ids current) [])))
                                   (set-mstart-dept-ids! (vec (or (:start_dept_ids current) [])))
                                   (set-mmanager-ids! (vec (or (:manager_user_ids current) []))))
                                 (set-error! true)))
                             (fn [_] (set-loading! false) (set-error! true)))
          (do (set-loading! false) (set-error! true)))
        (api/bpmmgmt-list "form" {:page 1 :size 1000}
                          #(set-form-list! (walk/keywordize-keys (get-in % [:data :rows])))
                          #())
        (api/bpm-list-categories {:page 1 :size 1000}
                                 #(set-categories! (walk/keywordize-keys (get-in % [:data :rows])))
                                 #())
        (api/list-users {:page 1 :size 1000}
                        #(set-users! (walk/keywordize-keys (get-in % [:data :rows])))
                        #())
        (api/list-depts {:page 1 :size 1000}
                        #(set-depts! (walk/keywordize-keys (get-in % [:data :rows])))
                        #())
        js/undefined)
      [model-id])
    (cond
      loading?
      [:div {:style {:display "flex" :justifyContent "center" :paddingTop 120}}
       [antd/spin {:size "large"}]]
      (or error? (nil? detail))
      [:div {:style {:paddingTop 120 :textAlign "center"}}
       [:div {:style {:fontSize 16 :color "#f56c6c" :marginBottom 16}}
        "流程模型加载失败或不存在，请返回列表重试"]
       [antd/button {:type "primary" :on-click back!} "返回列表"]]
      :else
      [:div {:style {:background "#fff" :minHeight "100%"}}
       ;; ── 顶部固定导航条（h-12，对齐 vben model/form/index.vue）──
       [:div {:style {:position "sticky" :top 0 :zIndex 20 :height 48
                      :borderBottom "1px solid #e8e8e8" :background "#fff"
                      :display "flex" :alignItems "center" :padding "0 16px" :gap 16}}
        ;; 左：返回 + 流程名
        [:div {:style {:display "flex" :alignItems "center" :gap 4 :width 280 :minWidth 0}}
         [antd/button {:type "text" :size "small"
                       :icon (r/as-element [:> ArrowLeftOutlined])
                       :on-click back!}]
         [:span {:style {:fontWeight 600 :fontSize 14 :whiteSpace "nowrap"
                         :overflow "hidden" :textOverflow "ellipsis"}}
          (:model_name detail)]]
        ;; 中：步骤条（编号圆点 + 标题，当前步高亮，可点击切换）
        [:div {:style {:flex 1 :display "flex" :justifyContent "center" :gap 8}}
         (doall
           (for [{:keys [key label num]} steps]
             (let [active? (= key tab)]
               ^{:key key}
               [:div {:style {:display "flex" :alignItems "center" :gap 6 :cursor "pointer"
                              :padding "0 16px" :height 48
                              :borderBottom (if active? "2px solid #1677ff" "2px solid transparent")
                              :color (if active? "#1677ff" "#666")}
                      :on-click #(switch-tab! key)}
                [:span {:style {:width 20 :height 20 :borderRadius "50%" :fontSize 12
                                :display "inline-flex" :alignItems "center" :justifyContent "center"
                                :background (if active? "#1677ff" "#f0f0f0")
                                :color (if active? "#fff" "#999")}}
                 num]
                [:span {:style {:fontSize 14}} label]])))]
        ;; 右：保存 + 发布
        [:div {:style {:width 280 :display "flex" :justifyContent "flex-end" :gap 8}}
         [antd/button {:icon (r/as-element [:> SaveOutlined])
                       :loading saving?
                       :on-click #(save-model! nil)}
          "保存"]
         [antd/button {:type "primary"
                       :icon (r/as-element [:> RocketOutlined])
                       :loading publishing?
                       :on-click publish!}
          "发布"]]]
       ;; ── 内容区 ──
       [:div {:style {:background "#f5f5f5" :paddingBottom 24}}
        (case tab
          "basic" [:div {:style {:maxWidth 760 :margin "0 auto" :padding 16}}
                   [bpm-model/basic-info-tab {:mname mname :set-mname! set-mname!
                                              :mkey mkey :set-mkey! set-mkey!
                                              :mcat mcat :set-mcat! set-mcat!
                                              :mform-type mform-type :set-mform-type! set-mform-type!
                                              :categories categories
                                              :micon micon :set-micon! set-micon!
                                              :mremark mremark :set-mremark! set-mremark!
                                              :mstart-type mstart-type :set-mstart-type! set-mstart-type!
                                              :mstart-user-ids mstart-user-ids
                                              :set-mstart-user-ids! set-mstart-user-ids!
                                              :mstart-dept-ids mstart-dept-ids
                                              :set-mstart-dept-ids! set-mstart-dept-ids!
                                              :mmanager-ids mmanager-ids
                                              :set-mmanager-ids! set-mmanager-ids!
                                              :users users :depts depts
                                              :mdeployed (seq (str (:deployment_id detail)))}]]
          "form" [:div {:style {:maxWidth 760 :margin "0 auto" :padding 16}}
                  [bpm-model/form-design-tab mform-type set-mform-type! mform-id set-mform-id! form-list
                   mcustom-create set-mcustom-create! mcustom-view set-mcustom-view!
                   mfields-perm set-mfields-perm! mform-json]]
          "process" [:div {:style {:padding 16}}
                     [bpm-model/process-design-tab {:model-id (:model_id detail)
                                                    :on-close back!}]]
          "extra" [:div {:style {:maxWidth 760 :margin "0 auto" :padding 16}}
                   [bpm-model/extra-tab {:mwebhooks mwebhooks :set-mwebhooks! set-mwebhooks!
                                         :mauto-type mauto-type :set-mauto-type! set-mauto-type!
                                         :mname-rule mname-rule :set-mname-rule! set-mname-rule!
                                         :mprocess-rule mprocess-rule :set-mprocess-rule! set-mprocess-rule!
                                         :msummary-fields msummary-fields
                                         :set-msummary-fields! set-msummary-fields!
                                         :mprint-enable mprint-enable :set-mprint-enable! set-mprint-enable!
                                         :mprint-html mprint-html :set-mprint-html! set-mprint-html!
                                         :mallow-cancel mallow-cancel :set-mallow-cancel! set-mallow-cancel!
                                         :mallow-withdraw mallow-withdraw
                                         :set-mallow-withdraw! set-mallow-withdraw!
                                         :form-fields form-fields}]]
          nil)]])))
