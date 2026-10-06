(ns com.ruoyi.frontend.pages.pms.structure
  "项目结构与项目团队维护."
  (:require
    ["@ant-design/icons" :refer [PlusOutlined ApartmentOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.form :as project-form]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))

(def node-labels {"main" "主项目" "sub" "子项目" "machine" "单机"})
(def role-labels {"manager" "项目经理" "editor" "协作成员" "viewer" "只读成员"})

(defn- tree-data
  "按父节点组装项目结构,后端数据作为唯一来源."
  [rows parent]
  (mapv (fn [node]
          {:key (:node_id node)
           :title (r/as-element
                    [:div {:style {:display "flex" :gap 10 :alignItems "center" :padding "8px 0"}}
                     [antd/tag {:color (case (:node_type node) "main" "blue" "sub" "cyan" "default")}
                      (get node-labels (:node_type node))]
                     [:span {:style {:fontWeight 550}} (:name node)]
                     [:span {:style {:fontSize 12 :color "#8b96a5"}} (:node_code node)]])
           :children (tree-data rows (:node_id node))})
        (filter #(= parent (:parent_id %)) rows)))

(defn- node-form
  "根据父节点自动确定子项目或单机类型."
  [id rows on-close on-saved]
  (let [[form] (antd/form-use-form)
        parent-options (filter #(not= "machine" (:node_type %)) rows)
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title "添加项目节点" :open true :onCancel on-close :onOk #(.submit form)
                 :okText "添加节点" :cancelText "取消" :confirmLoading busy? :destroyOnHidden true}
     (when error [shared/error-panel error nil])
     [:p {:style {:color "#7b8798"}} "主项目下添加子项目,子项目下添加单机."]
     [antd/form {:form form :layout "vertical" :disabled busy?
                 :initialValues {:parent_id (:node_id (first parent-options))}
                 :onFinish
                 (fn [values]
                   (let [data (js->clj values :keywordize-keys true)
                         parent (some #(when (= (:node_id %) (:parent_id data)) %) rows)]
                     (run! :post (str "/projects/" id "/nodes")
                           (assoc data :node_type (if (= "main" (:node_type parent)) "sub" "machine"))
                           "项目节点已添加")))}
      [antd/form-item {:name "parent_id" :label "父节点" :rules [{:required true :message "请选择父节点"}]}
       [antd/select {:options (mapv #(hash-map :value (:node_id %)
                                              :label (str (get node-labels (:node_type %)) " / " (:name %))) parent-options)}]]
      [antd/form-item {:name "node_code" :label "节点编号" :rules [{:required true :whitespace true :message "请输入节点编号"}]}
       [antd/input {:placeholder "例如 SUB-01 或 EQ-01" :maxLength 64}]]
      [antd/form-item {:name "name" :label "节点名称" :rules [{:required true :whitespace true :message "请输入节点名称"}]}
       [antd/input {:placeholder "填写子项目或设备名称" :maxLength 200}]]]]))

(defn project-structure
  "加载并维护主项目,子项目与单机树."
  [id revision editable? on-change]
  (let [{:keys [data loading? error refresh!]} (shared/use-resource (str "/projects/" id "/nodes") {} [revision])
        [adding? set-adding!] (hooks/use-state false)
        can-add? (and (shared/use-permission "pms:node:add") editable?)
        rows (:rows data)]
    [shared/panel "项目结构" "主项目 / 子项目 / 单机"
     (when can-add? [antd/button {:size "small" :icon (r/as-element [:> PlusOutlined])
                                  :on-click #(set-adding! true) :disabled (or loading? (empty? rows))} "添加节点"])
     (cond
       error [shared/error-panel error refresh!]
       loading? [antd/spin]
       (empty? rows) [shared/empty-state "暂无项目结构" nil]
       :else [antd/tree {:treeData (tree-data rows nil) :defaultExpandAll true :showLine true
                         :selectable false :blockNode true}])
     (when adding? [node-form id rows #(set-adding! false)
                    (fn [_] (set-adding! false) (refresh!) (on-change))])]))

(defn- member-form
  "新增或调整项目成员角色."
  [id options on-close on-saved]
  (let [[form] (antd/form-use-form)
        {:keys [busy? error run!]} (shared/use-action on-saved)]
    [antd/modal {:title "维护项目成员" :open true :onCancel on-close :onOk #(.submit form)
                 :okText "保存成员" :cancelText "取消" :confirmLoading busy? :destroyOnHidden true}
     (when error [shared/error-panel error nil])
     [:p {:style {:color "#7b8798"}} "选择已有成员可更新其角色.项目经理由基本信息统一维护."]
     [antd/form {:form form :layout "vertical" :disabled busy? :initialValues {:role "editor"}
                 :onFinish #(run! :post (str "/projects/" id "/members")
                                  (js->clj % :keywordize-keys true) "项目成员已更新")}
      [antd/form-item {:name "user_id" :label "成员" :rules [{:required true :message "请选择成员"}]}
       [antd/select {:showSearch true :optionFilterProp "label" :placeholder "搜索组织成员"
                     :options (project-form/user-options (:users options))}]]
      [antd/form-item {:name "role" :label "项目角色" :rules [{:required true}]}
       [antd/select {:options [{:value "editor" :label "协作成员"}
                               {:value "viewer" :label "只读成员"}]}]]
      [antd/form-item {:name "ends_on" :label "委派到期日" :extra "留空表示长期有效,填写后到期成员在台账中标记为已过期"}
       [antd/input {:type "date"}]]]]))

(defn- lifecycle-tag
  "按后端派生的生命周期状态为成员打上徽标."
  [member]
  (let [state (:lifecycle-state member)
        ends (:ends_on member)
        days (:days-left member)]
    (case state
      "expired" [antd/tag {:color "red"} (str "已过期 " (or ends ""))]
      "expiring-soon" [antd/tag {:color "gold"}
                       (str "即将到期" (when (some? days) (str " 剩" days "天")))]
      "active" [antd/tag {:color "green"} (str "有效至 " (or ends ""))]
      "open-ended" [antd/tag {:color "default"} "长期有效"]
      nil)))

(defn- lifecycle-list
  "把到期/过期成员渲染为可读徽标清单."
  [rows color]
  (let [colors (shared/use-colors)]
    (into [:div {:style {:display "flex" :flexWrap "wrap" :gap 6}}]
      (map (fn [m]
             ^{:key (:user_id m)}
             [antd/tag {:color color}
              (str (or (:nick_name m) (:user_name m) (:user_id m))
                   " · " (:ends_on m)
                   (when-let [d (:days-left m)]
                     (if (neg? d) (str " 已逾期" (- d) "天") (str " 剩" d "天"))))])
           rows))))

(defn- lifecycle-overview
  "只读呈现成员权限生命周期汇总,数据源与台账同源 (GET /members lifecycle)."
  [lc]
  (let [colors (shared/use-colors)
        counts (:counts lc)]
    [:div {:style {:marginTop 16 :paddingTop 16 :borderTop (str "1px solid " (:border colors))}}
     [:div {:style {:fontSize 13 :fontWeight 600 :marginBottom 10}} "成员权限生命周期"]
     [:div {:style {:display "flex" :flexWrap "wrap" :gap 8 :alignItems "center"}}
      [antd/tag {:color "default"} (str "成员 " (:total lc))]
      [antd/tag {:color "green"} (str "有效 " (:active counts))]
      [antd/tag {:color "gold"} (str "即将到期 " (:expiring-soon counts))]
      [antd/tag {:color "red"} (str "已过期 " (:expired counts))]
      [antd/tag {:color "default"} (str "长期 " (:open-ended counts))]]
     (when (seq (:expiring-soon lc))
       [:div {:style {:marginTop 12}}
        [:div {:style {:fontSize 12 :color (:muted colors) :marginBottom 6}}
         (str "即将到期 (未来 " (:window-days lc) " 天内)")]
        [lifecycle-list (:expiring-soon lc) "gold"]])
     (when (seq (:expired lc))
       [:div {:style {:marginTop 12}}
        [:div {:style {:fontSize 12 :color (:muted colors) :marginBottom 6}} "已过期, 建议复核访问权限"]
        [lifecycle-list (:expired lc) "red"]])]))

(defn- member-row
  "显示成员身份与项目角色."
  [member remove!]
  (let [colors (shared/use-colors)
        name (or (:nick_name member) (:user_name member) (str (:user_id member)))]
    [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between" :padding "11px 0"
                   :borderBottom (str "1px solid " (:border colors))}}
     [:div {:style {:display "flex" :alignItems "center" :gap 12}}
      [:span {:style {:width 32 :height 32 :borderRadius 8 :background (:accent colors)
                      :color (:primary colors) :display "grid" :placeItems "center" :fontWeight 600}}
       (subs name 0 (min 1 (count name)))]
      [:span name]]
     [antd/space
      (when-let [tag (lifecycle-tag member)] tag)
      [antd/tag {:color (if (= "manager" (:role member)) "blue" "default")}
       (get role-labels (:role member) (:role member))]
      (when (and remove! (not= "manager" (:role member)))
        [antd/button {:type "link" :danger true :size "small" :on-click #(remove! member)} "移除"])] ]))

(defn project-members
  "加载与维护有真实权限的项目成员."
  [id revision options editable? on-change project-version]
  (let [{:keys [data loading? error refresh!]} (shared/use-resource (str "/projects/" id "/members") {} [revision])
        [adding? set-adding!] (hooks/use-state false)
        [removing set-removing!] (hooks/use-state nil)
        can-edit? (and (shared/use-permission "pms:member:edit") editable?)]
    [shared/panel "项目团队" "项目角色决定协作范围"
     (when can-edit? [antd/button {:size "small" :on-click #(set-adding! true)} "维护成员"])
     (cond
       error [shared/error-panel error refresh!]
       loading? [antd/spin]
       (empty? (:rows data)) [shared/empty-state "暂无项目成员" nil]
       :else (into [:div] (map #(with-meta [member-row % (when can-edit? set-removing!)] {:key (:user_id %)}) (:rows data))))
     (when-let [lc (:lifecycle data)] [lifecycle-overview lc])
     (when adding? [member-form id options #(set-adding! false)
                    (fn [_] (set-adding! false) (refresh!) (on-change))])
     (when removing [w/mutation-dialog
                     {:title "移除项目成员" :description "移除后该成员立即失去此项目的访问资格."
                      :path (str "/projects/" id "/members/" (:user_id removing)) :method :delete
                      :fields [] :project {:version project-version} :on-close #(set-removing! nil)
                      :on-saved (fn [_] (set-removing! nil) (refresh!) (on-change))}])]))
