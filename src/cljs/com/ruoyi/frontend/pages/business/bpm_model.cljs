(ns com.ruoyi.frontend.pages.business.bpm-model
  "流程模型管理 -- 分类卡片分组视图(P1 对齐 vben model/index.vue);设计/新建跳转全屏编辑器页."
  (:require
    ["@ant-design/icons" :refer [ReloadOutlined PlayCircleOutlined EditOutlined
                                 PlusOutlined DownOutlined SearchOutlined]]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [com.ruoyi.frontend.components.bpm-flow-designer :as bpmfd]
    [com.ruoyi.frontend.components.form-render :as fr]
    [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


;; ── P1 分组卡片视图辅助 ─────────────────────────────────────────────

(def ^:private palette
  ["#409eff" "#67c23a" "#e6a23c" "#f56c6c" "#626aef"
   "#13c2c2" "#996633" "#345da2" "#e47470" "#13a8a8"])


(defn- color-of
  "按名称稳定取色(图标色块用)."
  [s]
  (let [h (reduce (fn [acc c] (unchecked-add (unchecked-multiply (int acc) 31) (int c))) 7 (str s))]
    (nth palette (mod (Math/abs h) (count palette)))))


(defn- model-icon-view
  "模型图标:有 icon 显示图片,否则名称前 2 字色块."
  ([model] (model-icon-view model 32))
  ([model size]
   (let [icon (:icon model)
         name (or (:model_name model) "?")]
     (if (seq (str icon))
       [:img {:src icon :style {:width size :height size :objectFit "cover" :borderRadius 6}}]
       [:div {:style {:width size :height size :borderRadius 6
                      :background (color-of name) :color "#fff" :flex-shrink "0"
                      :display "flex" :alignItems "center" :justifyContent "center"
                      :fontSize (max 11 (quot size 2)) :fontWeight 600}}
        (subs name 0 (min 2 (count name)))]))))


(defn- delete-model!
  "删除模型(更多菜单入口)."
  [m]
  (antd/modal-confirm!
    #(api/bpm-delete-model
       (:model_id m)
       (fn [res]
         (if (= 200 (:code res))
           (do (antd/success! "已删除")
               (rf/dispatch [:bpm/model-fetch {:page 1 :size 1000}]))
           (antd/error! (str "删除失败: " (:msg res)))))
       (fn [_] (antd/error! "删除失败")))
    {:title (str "确认删除模型「" (:model_name m) "」？")
     :okButtonProps #js {:danger true}}))


(defn- model-actions
  "模型行操作:设计 / 部署 / 更多(历史·挂起↔激活·复制·报表·清理·删除)."
  [m]
  (let [mid (:model_id m)
        running? (= "1" (:status m))
        confirm! (fn
                   ([title on-ok] (antd/modal-confirm! on-ok {:title title}))
                   ([title on-ok danger?]
                    (antd/modal-confirm! on-ok (cond-> {:title title}
                                                 danger? (assoc :okButtonProps #js {:danger true})))))
        item (fn [key label on-click]
               {:key key :label (r/as-element [:div {:on-click on-click} label])})
        danger-item (fn [key label on-click]
                      {:key key :danger true :label (r/as-element [:div {:on-click on-click} label])})
        more-items (clj->js
                     [(item "history" "历史" #(rf/dispatch [:bpm/definition-open m]))
                      (item "state" (if running? "挂起" "激活")
                            #(confirm! (if running? "确认挂起该流程？挂起后不可发起" "确认激活该流程？")
                                       (fn [] (rf/dispatch [:bpm/model-state mid (if running? "2" "1")]))))
                      (item "copy" "复制" #(confirm! "确认复制该模型？"
                                                   (fn [] (rf/dispatch [:bpm/model-copy mid]))))
                      (item "report" "报表" #(rf/dispatch [:navigate :report]))
                      {:type "divider"}
                      (danger-item "clean" "清理"
                                   #(confirm! "确认清理该流程全部历史实例与部署？不可恢复"
                                              (fn [] (rf/dispatch [:bpm/model-clean mid]))
                                              true))
                      (danger-item "delete" "删除" #(delete-model! m))])]
    [antd/space {:size 2}
     [antd/button {:type "link" :size "small"
                   :icon (r/as-element [:> EditOutlined])
                   :on-click #(rf/dispatch [:navigate :bpm-model-edit {:id (:model_id m)}])}
      "设计"]
     [antd/popconfirm {:title "确认部署该流程模型？"
                       :on-confirm #(rf/dispatch [:bpm/model-deploy mid])}
      [antd/button {:type "link" :size "small"
                    :icon (r/as-element [:> PlayCircleOutlined])}
       "部署"]]
     [antd/dropdown {:menu {:items more-items}}
      [antd/button {:type "link" :size "small"}
       "更多 " (r/as-element [:> DownOutlined])]]]))


(defn- group-columns
  "分类卡内模型表格列(P1 对齐 vben model/index.vue 信息密度)."
  [forms-by-id]
  #js [#js {:title "流程" :key "name" :width 240
            :render (fn [_ ^js record]
                      (let [m (js->clj record :keywordize-keys true)]
                        (r/as-element
                          [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
                           [model-icon-view m 32]
                           [:div
                            [:div {:style {:fontWeight 500}} (:model_name m)]
                            [:div {:style {:fontSize 12 :color "#909399"}} (:model_key m)]]])))}
       #js {:title "表单" :key "form" :width 170
            :render (fn [_ ^js record]
                      (let [m (js->clj record :keywordize-keys true)
                            f (get forms-by-id (:form_id m))]
                        (r/as-element
                          (case (:form_type m)
                            "1" (if f
                                  [antd/tag {:color "green"} (:form_name f)]
                                  [antd/tag {:color "orange"} "动态表单(未绑定)"])
                            "2" [antd/tag {:color "blue"} (or (:form_custom_create_path m) "自定义表单")]
                            [:span {:style {:color "#c0c4cc"}} "-"]))))}
       #js {:title "部署" :key "deploy" :width 210
            :render (fn [_ ^js record]
                      (let [m (js->clj record :keywordize-keys true)]
                        (r/as-element
                          [:div {:style {:display "flex" :gap 6 :alignItems "center" :flexWrap "wrap"}}
                           (if (:deployment_id m)
                             [:span {:style {:fontSize 12 :color "#606266"}}
                              (let [dt (str (:deploy_time m))]
                                (if (seq dt) (subs dt 0 (min 19 (count dt))) "-"))]
                             [antd/tag {:color "default"} "未部署"])
                           [antd/tag {:color "blue"} (str "v" (:version m))]
                           (when (= "2" (:status m))
                             [antd/tag {:color "red"} "已停用"])])))}
       #js {:title "谁可发起" :key "start" :width 140
            :render (fn [_ ^js record]
                      (let [m (js->clj record :keywordize-keys true)]
                        (r/as-element
                          (if (or (seq (:start_users m)) (seq (:start_depts m)))
                            [antd/tag {:color "purple"}
                             (str (str/join "," (take 2 (:start_users m)))
                                  (when (seq (:start_depts m))
                                    (str (when (seq (:start_users m)) " + ")
                                         (str/join "," (take 2 (:start_depts m)))))
                                  (let [n (+ (count (:start_users m)) (count (:start_depts m)))]
                                    (when (> n 2) (str " 等" n "项"))))]
                            [:span {:style {:color "#909399" :fontSize 12}} "全部"]))))}
       #js {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 160
            :render (fn [v]
                      (r/as-element
                        [:span {:style {:fontSize 12}}
                         (let [s (str v)] (if (seq s) (subs s 0 (min 19 (count s))) "-"))]))}
       #js {:title "操作" :key "action" :width 230
            :render (fn [_ ^js record]
                      (let [m (js->clj record :keywordize-keys true)]
                        (r/as-element [model-actions m])))}])


(defn- group-models
  "把模型按分类分组(排序:分类 order_num → 分类 id;组内 order_num → model_id 倒序),
   无分类/分类已删除的归\"未分类\"组(恒在最后)."
  [models categories]
  (let [cat-by-id (into {} (map (juxt :category_id identity)) categories)
        valid-ids (set (keys cat-by-id))
        ordered-cats (sort-by (juxt (comp #(or % 0) :order_num) :category_id) categories)
        buckets (group-by #(if (contains? valid-ids (:category_id %)) (:category_id %) :uncategorized) models)
        sort-ms (fn [rows] (sort-by (juxt (comp #(or % 0) :order_num) (comp - :model_id)) rows))]
    (concat
      (for [c ordered-cats]
        {:key (:category_id c) :category c :models (sort-ms (get buckets (:category_id c) []))})
      [{:key :uncategorized :category nil :models (sort-ms (get buckets :uncategorized []))}])))


;; Tab 页内容组件(避免深层嵌套,拆成独立函数)
(defn basic-info-tab
  "P1 基本信息(对齐 vben base-info.vue):名称/Key/分类/表单类型 +
   流程图标 / 流程描述 / 谁可发起(全员·指定人员·指定部门)/ 流程管理员."
  [{:keys [mname set-mname! mkey set-mkey! mcat set-mcat! mform-type set-mform-type!
           categories micon set-micon! mremark set-mremark!
           mstart-type set-mstart-type! mstart-user-ids set-mstart-user-ids!
           mstart-dept-ids set-mstart-dept-ids! mmanager-ids set-mmanager-ids!
           users depts mdeployed]}]
  [:div {:style {:padding 16 :maxWidth 560}}
   [antd/form {:layout "vertical"}
    [antd/form-item {:label "流程名称"}
     [antd/input {:value mname :disabled (boolean mdeployed)
                  :onChange (fn [e] (set-mname! (-> e .-target .-value)))}]]
    [antd/form-item {:label "流程Key"}
     [antd/input {:value mkey :disabled (boolean mdeployed)
                  :onChange (fn [e] (set-mkey! (-> e .-target .-value)))}]]
    [antd/form-item {:label "流程分类"}
     [antd/select {:value (when (seq (str mcat)) (js/Number mcat))
                   :style {:width "100%"} :allowClear true
                   :placeholder "请选择分类"
                   :onChange (fn [v] (set-mcat! (or v "")))}
      (doall (for [c categories] ^{:key (:category_id c)}
                  [antd/select-option {:value (:category_id c)} (:name c)]))]]
    [antd/form-item {:label "表单类型"}
     [antd/select {:value mform-type :style {:width "100%"} :onChange (fn [v] (set-mform-type! (str v)))}
      [antd/select-option {:value "0"} "无表单"]
      [antd/select-option {:value "1"} "动态表单"]]]
    [antd/form-item {:label "流程图标"}
     [:div {:style {:display "flex" :gap 12 :alignItems "center"}}
      [model-icon-view {:model_name mname :icon micon} 40]
      [:input {:type "file" :accept "image/*" :style {:display "none"}
               :id "bpm-model-icon-input"
               :onChange (fn [e]
                           (let [f (-> e .-target .-files (aget 0))]
                             (when f
                               (let [fd (js/FormData.)]
                                 (.append fd "file" f)
                                 (api/upload-file fd
                                                  (fn [res]
                                                    (if (= 200 (:code res))
                                                      (set-micon! (get-in res [:data :url]))
                                                      (antd/error! "图标上传失败")))
                                                  (fn [_] (antd/error! "图标上传失败")))))))}]
      [antd/button {:size "small"
                    :on-click #(.click (.getElementById js/document "bpm-model-icon-input"))}
       "上传图标"]
      (when (seq (str micon))
        [antd/button {:type "link" :size "small" :on-click #(set-micon! "")} "移除"])]]
    [antd/form-item {:label "流程描述"}
     [antd/text-area {:value mremark :rows 3 :placeholder "流程用途说明（存入备注）"
                      :onChange (fn [e] (set-mremark! (-> e .-target .-value)))}]]
    [antd/form-item {:label "谁可发起"}
     [antd/radio-group {:value mstart-type
                        :onChange (fn [e] (set-mstart-type! (-> e .-target .-value)))}
      [antd/radio {:value "ALL"} "全员可发起"]
      [antd/radio {:value "USER"} "指定人员"]
      [antd/radio {:value "DEPT"} "指定部门"]]
     (case mstart-type
       "USER" [antd/select {:mode "multiple" :style {:width "100%" :marginTop 8} :allowClear true
                            :placeholder "选择可发起的用户"
                            :value (or mstart-user-ids [])
                            :onChange #(set-mstart-user-ids! (vec %))}
               (doall (for [u users] ^{:key (:user_id u)}
                           [antd/select-option {:value (:user_id u)} (:nick_name u)]))]
       "DEPT" [antd/select {:mode "multiple" :style {:width "100%" :marginTop 8} :allowClear true
                            :placeholder "选择可发起的部门（含下级部门成员）"
                            :value (or mstart-dept-ids [])
                            :onChange #(set-mstart-dept-ids! (vec %))}
               (doall (for [d depts] ^{:key (:dept_id d)}
                           [antd/select-option {:value (:dept_id d)} (:dept_name d)]))]
       nil)]
    [antd/form-item {:label "流程管理员（当前仅用于展示）"}
     [antd/select {:mode "multiple" :style {:width "100%"} :allowClear true
                   :placeholder "选择流程管理员"
                   :value (or mmanager-ids [])
                   :onChange #(set-mmanager-ids! (vec %))}
      (doall (for [u users] ^{:key (:user_id u)}
                  [antd/select-option {:value (:user_id u)} (:nick_name u)]))]]]])


(defn form-design-tab
  "表单设计 Tab -- 对齐 vben form-design.vue:表单类型(无/动态/自定义) + 表单选择 + 只读预览.
   未绑定独立表单时回退到模型内嵌 form_json(内置模型)."
  [mform-type set-mform-type! mform-id set-mform-id! form-list
   mcustom-create set-mcustom-create! mcustom-view set-mcustom-view!
   mfields-perm set-mfields-perm! mform-json]
  (let [sel-form (first (filter #(= (:form_id %) mform-id) form-list))
        parse-schema (fn [j]
                       (when (seq (str j))
                         (if (string? j)
                           (js->clj (js/JSON.parse j) :keywordize-keys true)
                           (walk/keywordize-keys j))))
        schema (or (parse-schema (:form_json sel-form))
                   (parse-schema mform-json)
                   {:fields []})]
    [:div {:style {:padding 16}}
     [antd/form {:layout "vertical"}
      [antd/form-item {:label "表单类型"}
       [antd/radio-group {:value mform-type
                          :onChange (fn [e] (set-mform-type! (str (-> e .-target .-value))))}
        [antd/radio {:value "0"} "无表单"]
        [antd/radio {:value "1"} "动态表单"]
        [antd/radio {:value "2"} "自定义表单"]]]
      (when (= mform-type "1")
        [antd/form-item {:label "流程表单"}
         [antd/select {:value (when (pos? (or mform-id 0)) mform-id)
                       :style {:width "100%"} :allowClear true
                       :placeholder (if (seq (str mform-json))
                                      "未绑定独立表单（使用模型内嵌表单）" "请选择表单")
                       :onChange set-mform-id!}
          (doall (for [f form-list] ^{:key (:form_id f)}
                      [antd/select-option {:value (:form_id f)} (:form_name f)]))]])
      (when (= mform-type "2")
        [:<>
         [antd/form-item {:label "表单提交路由"}
          [antd/input {:value mcustom-create :placeholder "如 /bpm/oa/leave/create"
                       :onChange (fn [e] (set-mcustom-create! (-> e .-target .-value)))}]]
         [antd/form-item {:label "表单查看地址"}
          [antd/input {:value mcustom-view :placeholder "如 /bpm/oa/leave/detail"
                       :onChange (fn [e] (set-mcustom-view! (-> e .-target .-value)))}]]])
      (when (and (= mform-type "1") (seq (:fields schema)))
        [:div {:style {:border "1px solid #eee" :borderRadius 6 :padding 16 :marginTop 8}}
         [:div {:style {:display "flex" :alignItems "center" :marginBottom 12}}
          [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
          [:span {:style {:fontWeight 600}} "表单预览"]]
         [fr/form-render {:schema schema :disabled? true}]])
      (when (and (= mform-type "1") (seq (:fields schema)))
        [:div {:style {:marginTop 16}}
         [:div {:style {:display "flex" :alignItems "center" :marginBottom 8}}
          [:div {:style {:width 4 :height 16 :background "#626aef" :marginRight 8}}]
          [:span {:style {:fontWeight 600}} "字段权限"]]
         [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6}}
          (doall
            (for [f (:fields schema)]
              (let [field (or (:field f) (:key f))]
                ^{:key (or field (str "f-" (:type f)))}
                [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                               :padding "6px 10px" :borderBottom "1px solid #f5f5f5"}}
                 [:span (or (:title f) (:label f) field)]
                 [antd/select {:style {:width 110} :size "small"
                               :value (or (get mfields-perm field) "edit")
                               :onChange #(set-mfields-perm! (assoc mfields-perm field %))}
                  [antd/select-option {:value "edit"} "可编辑"]
                  [antd/select-option {:value "readonly"} "只读"]
                  [antd/select-option {:value "hidden"} "隐藏"]]])))]])]]))


(defn process-design-tab
  [{:keys [model-id on-close]}]
  [:div
   [:div {:style {:display "flex" :justifyContent "flex-end" :marginBottom 8}}
    [antd/button {:size "small" :on-click on-close} "关闭"]]
   [bpmfd/bpm-flow-designer {:model-id model-id}]])


(def ^:private webhook-events
  [{:key "process_start" :label "流程发起 (process_start)"}
   {:key "process_end" :label "流程结束 (process_end)"}
   {:key "task_start" :label "任务创建 (task_start)"}
   {:key "task_end" :label "任务完成 (task_end)"}])


(defn- kv-editor
  "key-value 行编辑器(Webhook headers / bodyParams)."
  [label rows on-change]
  [:div {:style {:marginTop 6}}
   [:div {:style {:fontSize 12 :color "#909399" :marginBottom 4}} label]
   (doall
     (for [[i row] (map-indexed vector (or rows []))]
       ^{:key i}
       [:div {:style {:display "flex" :gap 6 :marginBottom 4}}
        [:input {:style {:flex 1 :padding "4px 8px" :border "1px solid #d9d9d9" :borderRadius 4}
                 :placeholder "参数名" :value (:key row)
                 :onChange #(on-change (assoc (vec (or rows [])) i
                                              (assoc row :key (-> % .-target .-value))))}]
        [:input {:style {:flex 1 :padding "4px 8px" :border "1px solid #d9d9d9" :borderRadius 4}
                 :placeholder "值（支持 ${字段}）" :value (:value row)
                 :onChange #(on-change (assoc (vec (or rows [])) i
                                              (assoc row :value (-> % .-target .-value))))}]
        [:a {:style {:color "#f56c6c" :fontSize 12}
             :on-click #(on-change (vec (keep-indexed (fn [j r] (when (not= j i) r)) (or rows []))))}
         "删除"]]))
   [:a {:style {:fontSize 12 :color "#409eff"}
        :on-click #(on-change (conj (vec (or rows [])) {:key "" :value ""}))}
    "＋ 添加一行"]])


(defn- template-insert-select
  "模板变量插入下拉(P1):选中后把 token 插入绑定输入框的光标处并恢复光标."
  [input-ref value on-change placeholder options]
  [antd/select {:style {:width 160} :size "small" :placeholder placeholder
                :value nil
                :onChange (fn [token]
                            (when (seq (str token))
                              (let [el (or (some-> @input-ref .-input) @input-ref)
                                    pos (or (some-> el .-selectionStart) (count value))
                                    nv (str (subs value 0 pos) (str token) (subs value pos))]
                                (on-change nv)
                                (js/setTimeout
                                  (fn []
                                    (when el
                                      (.focus el)
                                      (set! (.-selectionStart el) (+ pos (count (str token))))
                                      (set! (.-selectionEnd el) (+ pos (count (str token))))))
                                  50))))}
   (doall (for [{:keys [value label]} options]
            ^{:key value} [antd/select-option {:value value} label]))])


(defn- template-input
  "标题模板输入框 + 变量插入下拉(P1).options: [{:value token :label 显示}]"
  [{:keys [value on-change placeholder options]}]
  (let [ref (hooks/use-ref nil)]
    [:div {:style {:display "flex" :gap 8}}
     [antd/input {:ref ref :value value :placeholder placeholder
                  :onChange (fn [e] (on-change (-> e .-target .-value)))}]
     [template-insert-select ref value on-change "插入变量" options]]))


(defn- print-template-editor
  "打印模板编辑器(P1):textarea + 变量插入下拉({{xxx}} 格式)."
  [value on-change options]
  (let [ref (hooks/use-ref nil)]
    [:div
     [antd/text-area {:ref ref :value value :rows 8
                      :placeholder "<h2>{{reason}} 审批单</h2>..."
                      :onChange (fn [e] (on-change (-> e .-target .-value)))}]
     [:div {:style {:marginTop 6}}
      [template-insert-select ref value on-change "插入打印变量" options]]]))


(defn extra-tab
  "更多设置:Phase 3/4 治理能力(编号规则/自动去重/标题规则/摘要字段/打印模板/Webhook)
   + P0-4 提交人/审批人权限开关 + P1 Webhook 响应回写.
   (流程描述已移至基本信息 Tab)"
  [{:keys [mwebhooks set-mwebhooks! mauto-type set-mauto-type! mname-rule set-mname-rule!
           mprocess-rule set-mprocess-rule! msummary-fields set-msummary-fields!
           mprint-enable set-mprint-enable! mprint-html set-mprint-html!
           mallow-cancel set-mallow-cancel! mallow-withdraw set-mallow-withdraw!
           form-fields]}]
  (let [rule-enabled? (boolean (:enable mprocess-rule))
        upd-rule! (fn [k v] (set-mprocess-rule! (assoc mprocess-rule k v)))
        section (fn [title]
                  [:div {:style {:display "flex" :alignItems "center" :margin "16px 0 8px"}}
                   [:div {:style {:width 4 :height 16 :background "#409eff" :marginRight 8}}]
                   [:span {:style {:fontWeight 600}} title]])]
    [:div {:style {:padding 16 :maxWidth 640}}
     [antd/form {:layout "vertical"}
      (section "提交人 / 审批人权限")
      [:div {:style {:marginBottom 8}}
       [antd/space {:align "center"}
        [antd/switch {:checked (= "1" (str mallow-cancel))
                      :onChange #(set-mallow-cancel! (if % "1" "0"))}]
        [:span {:style {:color "#606266"}} "提交人权限：允许撤销审批中的申请"]]]
      [:div {:style {:marginBottom 12}}
       [antd/space {:align "center"}
        [antd/switch {:checked (= "1" (str mallow-withdraw))
                      :onChange #(set-mallow-withdraw! (if % "1" "0"))}]
        [:span {:style {:color "#606266"}} "审批人权限：允许审批人撤回"]]]
      (section "流程编号规则")
      [:div {:style {:marginBottom 12}}
       [antd/space {:align "center"}
        [antd/switch {:checked rule-enabled? :onChange #(upd-rule! :enable (boolean %))}]
        [:span {:style {:color "#606266"}} "启用流程单号（前缀+日期中缀+当日递增流水号）"]]]
      (when rule-enabled?
        [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 12}}
         [antd/form-item {:label "前缀"}
          [antd/input {:value (or (:prefix mprocess-rule) "") :placeholder "如 CG-"
                       :onChange #(upd-rule! :prefix (-> % .-target .-value))}]]
         [antd/form-item {:label "后缀"}
          [antd/input {:value (or (:suffix mprocess-rule) "") :placeholder "如 -"
                       :onChange #(upd-rule! :suffix (-> % .-target .-value))}]]
         [antd/form-item {:label "日期中缀"}
          [antd/select {:value (or (:infix mprocess-rule) "DAY") :style {:width "100%"}
                        :onChange #(upd-rule! :infix %)}
           [antd/select-option {:value "NONE"} "无"]
           [antd/select-option {:value "DAY"} "日(yyyyMMdd)"]
           [antd/select-option {:value "HOUR"} "时(yyyyMMddHH)"]
           [antd/select-option {:value "MINUTE"} "分(yyyyMMddHHmm)"]
           [antd/select-option {:value "SECOND"} "秒(yyyyMMddHHmmss)"]]]
         [antd/form-item {:label "流水号长度(≥5)"}
          [antd/input-number {:value (or (:length mprocess-rule) 5) :min 5 :max 10
                              :style {:width "100%"}
                              :onChange #(upd-rule! :length (js/Number %))}]]])
      (section "自动去重")
      [antd/form-item {:label "同一审批人重复出现时"}
       [antd/select {:value (or mauto-type "NONE") :style {:width "100%"}
                     :onChange set-mauto-type!}
        [antd/select-option {:value "NONE"} "不处理"]
        [antd/select-option {:value "APPROVE_ONCE"} "只审一次（后续节点自动通过）"]
        [antd/select-option {:value "CONSECUTIVE"} "连续重复节点自动通过"]]]
      (section "自定义标题")
      [antd/form-item {:label "标题模板（{字段id}、{发起人}、{发起时间}、{流程名称}）"}
       [template-input {:value (or mname-rule "")
                        :on-change set-mname-rule!
                        :placeholder "如 {发起人}的{days}天请假申请"
                        :options (concat
                                   (mapv (fn [f]
                                           {:value (str "{" (:field f) "}")
                                            :label (str (:title f) " {" (:field f) "}")})
                                         form-fields)
                                   [{:value "{发起人}" :label "发起人 {发起人}"}
                                    {:value "{发起时间}" :label "发起时间 {发起时间}"}
                                    {:value "{流程名称}" :label "流程名称 {流程名称}"}])}]]
      (section "摘要字段")
      [antd/form-item {:label "实例/待办/抄送列表展示的摘要（需绑定动态表单）"}
       [antd/select {:value (clj->js (or msummary-fields [])) :mode "multiple"
                     :style {:width "100%"} :placeholder "选择表单字段"
                     :onChange #(set-msummary-fields! (vec (js->clj %)))}
        (doall (for [f form-fields]
                 ^{:key (:field f)}
                 [antd/select-option {:value (:field f)} (:title f)]))]]
      (section "流程 Webhook（HTTP 回调）")
      (doall
        (for [{:keys [key label]} webhook-events]
          (let [hook (get mwebhooks (keyword key) {})
                enabled? (boolean (:enable hook))
                upd! (fn [k v] (set-mwebhooks! (assoc mwebhooks (keyword key) (assoc hook k v))))]
            ^{:key key}
            [:div {:style {:border "1px solid #f0f0f0" :borderRadius 6 :padding 10 :marginBottom 8}}
             [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
              [antd/switch {:size "small" :checked enabled?
                            :onChange #(upd! :enable (boolean %))}]
              [:span {:style {:fontSize 13 :fontWeight 500}} label]]
             (when enabled?
               [:div {:style {:marginTop 8}}
                [:input {:style {:width "100%" :padding "5px 8px" :border "1px solid #d9d9d9" :borderRadius 4}
                         :placeholder "回调 URL（POST）" :value (or (:url hook) "")
                         :onChange #(upd! :url (-> % .-target .-value))}]
                [kv-editor "Headers" (:headers hook) #(upd! :headers %)]
                [kv-editor "Body 参数" (:bodyParams hook) #(upd! :bodyParams %)]
                [kv-editor "响应回写（JSON 路径 → 流程变量名）" (:response-mappings hook)
                 #(upd! :response-mappings %)]])])))
      (section "打印模板")
      [:div {:style {:marginBottom 12}}
       [antd/space {:align "center"}
        [antd/switch {:checked mprint-enable :onChange #(set-mprint-enable! (boolean %))}]
        [:span {:style {:color "#606266"}} "启用打印模板（占位符：{{字段}}、{{流程记录}}）"]]]
      (when mprint-enable
        [antd/form-item {:label "打印 HTML 模板（占位符：{{字段}}、{{流程记录}}）"}
         [print-template-editor (or mprint-html "") set-mprint-html!
          (concat
            (mapv (fn [f]
                    {:value (str "{{" (:field f) "}}")
                     :label (str (:title f) " {{" (:field f) "}}")})
                  form-fields)
            [{:value "{{流程记录}}" :label "流程记录 {{流程记录}}"}
             {:value "{{发起人}}" :label "发起人 {{发起人}}"}
             {:value "{{发起时间}}" :label "发起时间 {{发起时间}}"}
             {:value "{{流程名称}}" :label "流程名称 {{流程名称}}"}])]])]]))


(defn- create-model-modal
  [{:keys [visible? on-close on-created]}]
  (let [[form] (antd/form-use-form)
        [categories set-categories!] (hooks/use-state [])
        [submitting? set-submitting!] (hooks/use-state false)
        key-pattern #"^[a-zA-Z_][-\w.$]*$"]
    (hooks/use-effect
      (fn []
        (when visible?
          (api/bpm-list-categories {:page 1 :size 1000}
                                   #(set-categories! (walk/keywordize-keys (get-in % [:data :rows])))
                                   #())))
      [visible?])
    [antd/modal {:title "新建模型" :open visible? :width 480
                 :confirmLoading submitting?
                 :onCancel on-close
                 :onOk #(.submit form)}
     [antd/form {:form form :layout "vertical" :preserve false
                 :onFinish (fn [values]
                             (let [v (js->clj values :keywordize-keys true)]
                               (set-submitting! true)
                               (api/bpm-create-model
                                 {:model_name (:model_name v)
                                  :model_key (:model_key v)
                                  :category_id (:category_id v)
                                  :remark (:remark v)}
                                 (fn [res]
                                   (set-submitting! false)
                                   (if (= 200 (:code res))
                                     (do (antd/success! "模型创建成功，请继续设计流程")
                                         (on-close)
                                         (on-created (:model_key v)))
                                     (antd/error! (str "创建失败: " (:msg res)))))
                                 (fn [_]
                                   (set-submitting! false)
                                   (antd/error! "创建失败")))))}
      [antd/form-item {:label "流程名称" :name "model_name"
                       :rules [{:required true :message "请输入流程名称"}]}
       [antd/input {:placeholder "如：请假审批"}]]
      [antd/form-item {:label "流程Key" :name "model_key"
                       :rules [{:required true :message "请输入流程Key"}
                               {:pattern key-pattern
                                :message "须以字母或下划线开头，只能包含字母、数字、_ - . $"}]}
       [antd/input {:placeholder "如：oa_leave"}]]
      [antd/form-item {:label "流程分类" :name "category_id"}
       [antd/select {:style {:width "100%"} :allowClear true :placeholder "请选择分类"}
        (doall (for [c categories] ^{:key (:category_id c)}
                    [antd/select-option {:value (:category_id c)} (:name c)]))]]
      [antd/form-item {:label "备注" :name "remark"}
       [antd/text-area {:rows 2 :placeholder "备注(可选)"}]]]]))


(defn- refresh-categories!
  "重新加载分类列表."
  [set-categories!]
  (api/bpm-list-categories {:page 1 :size 1000}
                           #(set-categories! (walk/keywordize-keys (get-in % [:data :rows])))
                           (fn [_] (antd/error! "加载分类失败"))))


(defn- category-manage-modal
  "分类管理:新增 / 改名 / 删除 / 拖拽排序(对齐 vben 分类管理)."
  [{:keys [open? on-close on-changed]}]
  (let [[cats set-cats!] (hooks/use-state [])
        [new-name set-new-name!] (hooks/use-state "")
        drag-id (hooks/use-ref nil)]
    (hooks/use-effect
      (fn []
        (when open?
          (api/bpm-list-categories {:page 1 :size 1000}
                                   #(set-cats! (walk/keywordize-keys (get-in % [:data :rows])))
                                   (fn [_] (antd/error! "加载分类失败")))))
      [open?])
    (let [save-sort! (fn [ordered]
                       (set-cats! ordered)
                       (api/bpm-sort-categories (mapv :category_id ordered)
                                                (fn [_] (on-changed))
                                                (fn [_] (antd/error! "分类排序保存失败，请重试"))))
          rename! (fn [c name]
                    (when (and (seq name) (not= name (:name c)))
                      (api/bpm-update-category (:category_id c) {:name name}
                                               (fn [_] (on-changed))
                                               (fn [_] (antd/error! "重命名失败")))))
          remove! (fn [c]
                    (api/bpm-delete-category (:category_id c)
                                             (fn [res]
                                               (if (= 200 (:code res))
                                                 (do (antd/success! "已删除")
                                                     (on-changed))
                                                 (antd/error! (str "删除失败: " (:msg res)))))
                                             (fn [_] (antd/error! "删除失败"))))]
      [antd/modal {:title "分类管理" :open open? :width 480 :footer nil
                   :onCancel on-close}
       [:div
        [:div {:style {:display "flex" :gap 8 :marginBottom 12}}
         [antd/input {:placeholder "新分类名称" :value new-name
                      :onChange #(set-new-name! (-> % .-target .-value))
                      :onPressEnter #(.click (.getElementById js/document "bpm-cat-add-btn"))}]
         [antd/button {:id "bpm-cat-add-btn" :type "primary"
                       :on-click #(when (seq new-name)
                                    (api/bpm-create-category {:name new-name}
                                                             (fn [_]
                                                               (antd/success! "已新增分类")
                                                               (set-new-name! "")
                                                               (on-changed))
                                                             (fn [_] (antd/error! "新增失败"))))}
          "新增"]]
        (if (seq cats)
          (doall
            (for [[i c] (map-indexed vector cats)]
              ^{:key (:category_id c)}
              [:div {:style {:display "flex" :gap 8 :alignItems "center" :padding "6px 4px"
                             :borderBottom "1px solid #f5f5f5" :cursor "grab"}
                     :draggable true
                     :on-drag-start (fn [e] (.setData (.-dataTransfer e) "text/plain" (str "cat:" (:category_id c))))
                     :on-drag-over (fn [e] (.preventDefault e))
                     :on-drop (fn [e]
                                (.preventDefault e)
                                (when-let [data (.getData (.-dataTransfer e) "text/plain")]
                                  (when (and (str/starts-with? data "cat:")
                                             (not= (subs data 4) (str (:category_id c))))
                                    (let [did (subs data 4)
                                          without (vec (remove #(= (str (:category_id %)) did) cats))
                                          idx (.indexOf (clj->js (mapv :category_id without)) (:category_id c))
                                          idx (if (neg? idx) (count without) idx)
                                          reordered (vec (concat (subvec without 0 idx)
                                                                 [(first (filter #(= (str (:category_id %)) did) cats))]
                                                                 (subvec without idx)))]
                                      (save-sort! reordered)))))}
               [:span {:style {:color "#c0c4cc"}} "≡"]
               [antd/input {:default-value (:name c) :size "small" :style {:flex 1}
                            :on-blur (fn [e] (rename! c (-> e .-target .-value)))
                            :on-press-enter (fn [e] (rename! c (-> e .-target .-value)))}]
               [antd/tag {:color "blue"} (str (inc i))]
               [antd/popconfirm {:title (str "确认删除分类「" (:name c) "」？分类下的模型将归入未分类")
                                 :on-confirm #(remove! c)}
                [antd/button {:type "text" :size "small" :danger true} "删除"]]]))
          [:div {:style {:color "#c0c4cc" :textAlign "center" :padding 20}} "暂无分类，请先新增"])]
       [:div {:style {:marginTop 12 :color "#909399" :fontSize 12}}
        "提示：拖动左侧 ≡ 手柄可调整分类顺序，自动保存"]])))


(defn- category-card
  "单个分类卡:可折叠,可拖拽排序;卡内模型表格行可拖拽排序."
  [{:keys [category models collapsed? on-toggle forms-by-id on-model-sort on-cat-sort]}]
  (let [title (or (:name category) "未分类")
        cat-key (or (:category_id category) :uncategorized)]
    [:div {:style {:marginBottom 12}
           :draggable (some? category)
           :on-drag-start (fn [e]
                            (when category
                              (.setData (.-dataTransfer e) "text/plain" (str "cat:" (:category_id category)))))
           :on-drag-over (fn [e]
                           (when category (.preventDefault e)))
           :on-drop (fn [e]
                      (when category
                        (.preventDefault e)
                        (when-let [data (.getData (.-dataTransfer e) "text/plain")]
                          (when (and (str/starts-with? data "cat:")
                                     (not= (subs data 4) (str (:category_id category))))
                            (on-cat-sort (subs data 4) (:category_id category))))))}
     [antd/card
      {:size "small"
       :title (r/as-element
                [:div {:style {:display "flex" :alignItems "center" :gap 8}}
                 [:span {:style {:color "#c0c4cc" :cursor (if category "grab" "default")
                                 :marginRight 2}
                         :title (when category "拖拽调整分类顺序")}
                  "≡"]
                 [:span {:style {:fontWeight 600 :cursor "pointer"} :on-click on-toggle}
                  title]
                 [antd/tag {:color "blue"} (str (count models) " 个")]
                 (when (and category (:code category))
                   [:span {:style {:fontSize 12 :color "#c0c4cc"}} (:code category)])])
       :extra (r/as-element
                [:span {:style {:color "#909399" :cursor "pointer"} :on-click on-toggle}
                 (if collapsed? "▸ 展开" "▾ 收起")])}
      (when-not collapsed?
        (if (seq models)
          [antd/table
           {:rowKey "model_id"
            :columns (group-columns forms-by-id)
            :dataSource (clj->js models)
            :pagination false
            :size "small"
            :onRow (fn [^js record _]
                     #js {:draggable true
                          :style #js {:cursor "grab"}
                          :onDragStart (fn [e]
                                         (.setData (.-dataTransfer e) "text/plain"
                                                   (str "model:" (.-model_id record))))
                          :onDragOver (fn [e] (.preventDefault e))
                          :onDrop (fn [e]
                                    (.preventDefault e)
                                    (let [data (.getData (.-dataTransfer e) "text/plain")]
                                      (when (str/starts-with? data "model:")
                                        (on-model-sort (subs data 6) (.-model_id record)))))})}]
          [:div {:style {:color "#c0c4cc" :padding "14px 0" :textAlign "center" :fontSize 13}}
           "该分类下暂无流程模型"]))]]))


(defn bpm-model-page
  []
  (let [items @(rf/subscribe [:bpm-model/items])
        loading? @(rf/subscribe [:bpm-model/loading?])
        [kw set-kw!] (hooks/use-state "")
        [categories set-categories!] (hooks/use-state [])
        [collapsed set-collapsed!] (hooks/use-state {})
        [forms set-forms!] (hooks/use-state [])
        [cat-manage? set-cat-manage!] (hooks/use-state false)
        [create-open? set-create-open!] (hooks/use-state false)]
    (hooks/use-effect
      (fn []
        (rf/dispatch [:bpm/model-fetch {:page 1 :size 1000}])
        (refresh-categories! set-categories!)
        (api/bpmmgmt-list "form" {:page 1 :size 1000}
                          #(set-forms! (walk/keywordize-keys (get-in % [:data :rows])))
                          #())
        js/undefined)
      [])
    (let [forms-by-id (into {} (map (juxt :form_id identity)) forms)
          kw-trim (str/trim kw)
          filtered (if (seq kw-trim)
                     (filter #(str/includes? (:model_name %) kw-trim) items)
                     items)
          groups (group-models filtered categories)
          refresh-all! (fn []
                         (rf/dispatch [:bpm/model-fetch {:page 1 :size 1000}])
                         (refresh-categories! set-categories!))
          on-model-sort (fn [drag-id target-id]
                          (let [;; 找到目标所在组
                                gid (some (fn [g] (when (some #(= (str (:model_id %)) (str target-id)) (:models g)) (:key g))) groups)
                                gmodels (vec (get (into {} (map (juxt :key :models)) groups) gid))]
                            (when (and gid (some #(= (str (:model_id %)) (str drag-id)) gmodels))
                              (let [without (vec (remove #(= (str (:model_id %)) (str drag-id)) gmodels))
                                    idx (.indexOf (clj->js (mapv :model_id without)) target-id)
                                    idx (if (neg? idx) (count without) idx)
                                    dragged (first (filter #(= (str (:model_id %)) (str drag-id)) gmodels))
                                    reordered (vec (concat (subvec without 0 idx) [dragged] (subvec without idx)))]
                                (api/bpm-sort-models (mapv :model_id reordered)
                                                     (fn [_] (rf/dispatch [:bpm/model-fetch {:page 1 :size 1000}]))
                                                     (fn [_] (antd/error! "排序保存失败，请重试")))))))
          on-cat-sort (fn [drag-id target-id]
                        (let [without (vec (remove #(= (str (:category_id %)) (str drag-id)) categories))
                              idx (.indexOf (clj->js (mapv :category_id without)) target-id)
                              idx (if (neg? idx) (count without) idx)
                              dragged (first (filter #(= (str (:category_id %)) (str drag-id)) categories))
                              reordered (vec (concat (subvec without 0 idx) [dragged] (subvec without idx)))]
                          (set-categories! reordered)
                          (api/bpm-sort-categories (mapv :category_id reordered)
                                                   (fn [_] (refresh-categories! set-categories!))
                                                   (fn [_] (antd/error! "分类排序保存失败，请重试")))))]
      [:div
       [page-toolbar/page-toolbar
        {:left [page-toolbar/toolbar-left
                [:div {:style {:fontSize 15 :fontWeight 600}} "流程模型"]]
         :right [page-toolbar/toolbar-right
                 [antd/input {:placeholder "按名称搜索" :value kw :allowClear true
                              :style {:width 180}
                              :prefix (r/as-element [:> SearchOutlined])
                              :onChange #(set-kw! (-> % .-target .-value))}]
                 [antd/button {:on-click #(set-cat-manage! true)} "分类管理"]
                 [antd/button {:type "primary"
                               :icon (r/as-element [:> PlusOutlined])
                               :on-click #(set-create-open! true)}
                  "新建模型"]
                 [page-toolbar/round-tool-button {:title "刷新"
                                                  :icon (r/as-element [:> ReloadOutlined])
                                                  :on-click refresh-all!}]]}]
       (if (and (empty? groups) (not loading?))
         [antd/empty-component {:description "暂无流程模型"}]
         (doall
           (for [g groups]
             ^{:key (str "cat-" (:key g))}
             [category-card
              {:category (:category g)
               :models (:models g)
               :collapsed? (get collapsed (:key g) false)
               :on-toggle #(set-collapsed! (update collapsed (:key g) not))
               :forms-by-id forms-by-id
               :on-model-sort on-model-sort
               :on-cat-sort on-cat-sort}])))
       [create-model-modal {:visible? create-open?
                            :on-close #(set-create-open! false)
                            :on-created (fn [mkey]
                                          (refresh-all!)
                                          (api/bpm-list-models {:model_key mkey :page 1 :size 1}
                                                               (fn [res]
                                                                 (when-let [m (first (get-in res [:data :rows]))]
                                                                   (rf/dispatch [:navigate :bpm-model-edit
                                                                                 {:id (:model_id m)}])))
                                                               #()))}]
       [category-manage-modal {:open? cat-manage?
                               :on-close #(set-cat-manage! false)
                               :on-changed refresh-all!}]])))
