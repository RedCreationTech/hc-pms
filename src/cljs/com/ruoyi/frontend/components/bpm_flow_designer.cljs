(ns com.ruoyi.frontend.components.bpm-flow-designer
  "纯 HTML/CSS flex 流程编辑器（对齐 vben simple-process-design）。
   节点树: {:id :type :name :child-node :condition-nodes}
   垂直 flex 布局 + 卡片节点 + 灰线箭头 + 蓝色'＋'按钮 + 分支横向展开。"
  (:require
   [clojure.walk :as walk]
   [clojure.string :as str]
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]))

(def ^:private node-color
  {"USER_TASK_NODE" "#ff943e" "COPY_TASK_NODE" "#3296fa" "CONDITION_BRANCH_NODE" "#67c23a"
   "PARALLEL_BRANCH_NODE" "#626aef" "INCLUSIVE_BRANCH_NODE" "#345da2" "DELAY_TIMER_NODE" "#e47470"
   "TRIGGER_NODE" "#3373d2" "CHILD_PROCESS_NODE" "#996633" "START_USER_NODE" "#676565"
   "END_EVENT_NODE" "#676565"})

(def ^:private node-icon
  {"USER_TASK_NODE" "bpmn-icon-user-task" "COPY_TASK_NODE" "bpmn-icon-user-task"
   "CONDITION_BRANCH_NODE" "bpmn-icon-gateway-none" "PARALLEL_BRANCH_NODE" "bpmn-icon-gateway-parallel"
   "INCLUSIVE_BRANCH_NODE" "bpmn-icon-gateway-or" "DELAY_TIMER_NODE" "bpmn-icon-intermediate-event-catch-timer"
   "TRIGGER_NODE" "bpmn-icon-call-activity" "CHILD_PROCESS_NODE" "bpmn-icon-subprocess-expanded"
   "START_USER_NODE" "bpmn-icon-user" "END_EVENT_NODE" "bpmn-icon-end-event-none"})

(def ^:private node-type-label
  {"USER_TASK_NODE" "审批人" "COPY_TASK_NODE" "抄送" "CONDITION_BRANCH_NODE" "条件分支"
   "PARALLEL_BRANCH_NODE" "并行分支" "INCLUSIVE_BRANCH_NODE" "包容分支" "DELAY_TIMER_NODE" "延迟器"
   "TRIGGER_NODE" "触发器" "CHILD_PROCESS_NODE" "子流程" "START_USER_NODE" "发起人" "END_EVENT_NODE" "结束"})

;; ── 节点渲染（递归，path 用于定位编辑）──────────────────────────────

(declare render-node)

(defn- render-connector [on-add]
  [:div.bpm-connector
   [:button.bpm-plus-btn {:title "在此添加节点" :on-click (fn [e] (.stopPropagation e) (on-add))} "+"]
   [:div.bpm-connector-arrow "▼"]])

(defn- render-card [node path on-edit on-delete]
  (let [color (get node-color (:type node) "#909399")
        icon (get node-icon (:type node) "bpmn-icon-task")
        hint (get node-type-label (:type node))
        text (:show-text node)]
    [:div.bpm-node-card {:on-click #(on-edit path)}
     [:div.bpm-node-title-row
      [:div.bpm-node-icon {:style {:color color}} [:i {:class (str "iconfont " icon)}]]
      [:div.bpm-node-name (:name node)]]
     [:div.bpm-node-content {:on-click #(on-edit path)}
      [:div.bpm-node-text (if (seq text) text (str "请配置" hint))]]
     [:div.bpm-node-toolbar
      [:span.bpm-node-del {:title "删除"
                           :on-click (fn [e] (.stopPropagation e) (on-delete path))} "✕"]]]))

(defn- render-capsule [node end? on-edit]
  [:div.bpm-capsule {:class (when end? "end") :on-click on-edit} (:name node)])

(defn- render-branch [node path on-edit on-add on-delete]
  [:div.bpm-branch
   [:div.bpm-branch-node
    [:div.bpm-branch-card {:on-click #(on-edit path)}
     [:div.bpm-node-title-row
      [:div.bpm-node-icon {:style {:color (get node-color (:type node) "#67c23a")}}
       [:i {:class (str "iconfont " (get node-icon (:type node) "bpmn-icon-gateway-none"))}]]
      [:div.bpm-node-name (:name node)]]]]
   (doall
    (for [[i cn] (map-indexed vector (or (:condition-nodes node) []))]
      ^{:key (:id cn)}
      [:div.bpm-branch-item {:class (if (= i 0) "primary" "")}
       [:div.bpm-branch-label (:name cn)]
       (when-let [child (:child-node cn)]
         [:div.bpm-node-column
          (render-node child (conj path :condition-nodes i :child-node) on-edit on-add on-delete)
          (render-connector #(on-add (conj path :condition-nodes i :child-node)))])]))])

(defn render-node
  "递归渲染节点树。path 为从根到当前节点的 assoc-in 路径。"
  [node path on-edit on-add on-delete]
  (if (nil? node)
    [:div]
    (let [type (:type node)]
      [:div.bpm-node-column
       (cond
         (= type "START_USER_NODE") (render-capsule node false #(on-edit path))
         (= type "END_EVENT_NODE") (render-capsule node true #(on-edit path))
         (and (str/includes? (or type "") "BRANCH") (seq (:condition-nodes node)))
         (render-branch node path on-edit on-add on-delete)
         :else (render-card node path on-edit on-delete))
       (when-let [child (:child-node node)]
         [:div.bpm-node-column
          (render-connector #(on-add (conj path :child-node)))
          (render-node child (conj path :child-node) on-edit on-add on-delete)])])))

;; ── 设计器组件（r/atom + with-let component-did-mount）────────────────

(def ^:private add-node-types
  "可添加的节点类型（对齐 vben node-handler）。"
  [{:type "USER_TASK_NODE" :label "审批人"} {:type "USER_TASK_NODE" :label "办理人"}
   {:type "COPY_TASK_NODE" :label "抄送"} {:type "CONDITION_BRANCH_NODE" :label "条件分支"}
   {:type "PARALLEL_BRANCH_NODE" :label "并行分支"} {:type "INCLUSIVE_BRANCH_NODE" :label "包容分支"}
   {:type "DELAY_TIMER_NODE" :label "延迟器"} {:type "TRIGGER_NODE" :label "触发器"}
   {:type "CHILD_PROCESS_NODE" :label "子流程"}])

(defn bpm-flow-designer
  "HTML/flex 流程编辑器。参数 {:model-id :on-saved}。"
  [{:keys [model-id on-saved]}]
  (r/with-let [tree (r/atom nil)
               loading (r/atom true)
               edit-path (r/atom nil)
               edit-name (r/atom "")
               add-path (r/atom nil)
               scale (r/atom 1)
               _ (when model-id
                   (api/bpm-model-tree model-id
                                       (fn [res]
                                         (reset! tree (walk/keywordize-keys (:data res)))
                                         (reset! loading false))
                                       (fn [e] (reset! loading false) (antd/error! (str "加载流程失败: " e)))))
               open-edit (fn [path] (reset! edit-path path) (reset! edit-name (get-in @tree (conj path :name))))
               apply-edit (fn [] (when-let [p @edit-path] (swap! tree assoc-in (conj p :name) @edit-name)) (reset! edit-path nil))
               delete-node (fn [path]
                             (when (seq path)
                               (let [child (:child-node (get-in @tree path))]
                                 (swap! tree assoc-in (vec path) child))))
               add-node (fn [path type label]
                          (when path
                            (let [new-node {:id (str "n" (subs (str (random-uuid)) 0 8)) :type type :name label}]
                              (swap! tree update-in (vec path)
                                     (fn [existing] (assoc new-node :child-node existing))))
                            (reset! add-path nil)))
               find-end (fn find-end [node path]
                          (if (:child-node node)
                            (find-end (:child-node node) (conj path :child-node))
                            (conj path :child-node)))
               save-tree (fn [] (when (and model-id @tree)
                                  (api/bpm-save-model-tree model-id @tree
                                                           (fn [_] (antd/success! "流程已保存") (when on-saved (on-saved)))
                                                           (fn [e] (antd/error! (str "保存失败: " e))))))]
    [:div
     [:div.bpm-toolbar
      [:span.bpm-toolbar-title "流程设计"]
      [:div.bpm-toolbar-right
       [antd/button {:size "small" :on-click #(reset! add-path (find-end @tree []))} "＋ 添加节点"]
       [antd/button {:size "small" :on-click #(swap! scale (fn [s] (max 0.5 (- s 0.1))))} "−"]
       [:span.bpm-zoom (str (int (* @scale 100)) "%")]
       [antd/button {:size "small" :on-click #(swap! scale (fn [s] (min 2 (+ s 0.1))))} "＋"]
       [antd/button {:size "small" :on-click #(reset! scale 1)} "重置"]
       [antd/button {:size "small" :type "primary" :on-click save-tree} "保存流程"]]]
     (if @loading
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div.bpm-flow-root
        (when-let [t @tree]
          [:div {:style {:transform (str "scale(" @scale ")") :transformOrigin "50% 0"}}
           (render-node t [] open-edit (fn [path] (reset! add-path path)) delete-node)])])
     [antd/modal {:title "编辑节点" :open (boolean @edit-path) :footer nil
                  :width 420 :onCancel #(reset! edit-path nil)}
      [antd/input {:value @edit-name :onChange (fn [e] (reset! edit-name (-> e .-target .-value)))}]
      [antd/button {:type "primary" :block true :style {:marginTop 12} :on-click apply-edit} "确定"]]
     [antd/modal {:title "在此添加节点" :open (boolean @add-path) :footer nil
                  :width 480 :onCancel #(reset! add-path nil)}
      [:div.bpm-addmenu
       (doall
        (for [{:keys [type label]} add-node-types]
          ^{:key label}
          [:div.bpm-addmenu-item {:on-click #(add-node @add-path type label)}
           [:div.bpm-addmenu-icon {:style {:color (get node-color type "#909399")}}
            [:i {:class (str "iconfont " (get node-icon type "bpmn-icon-task"))}]]
           [:span label]]))]]]))
