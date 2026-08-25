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

(defn- render-card [node on-edit]
  (let [color (get node-color (:type node) "#909399")
        icon (get node-icon (:type node) "bpmn-icon-task")]
    [:div.bpm-node-card {:on-click on-edit}
     [:div.bpm-node-title-row
      [:div.bpm-node-icon {:style {:color color}} [:i {:class (str "iconfont " icon)}]]
      [:div.bpm-node-name (:name node)]]
     [:div.bpm-node-text (get node-type-label (:type node))]]))

(defn- render-capsule [node end? on-edit]
  [:div.bpm-capsule {:class (when end? "end") :on-click on-edit} (:name node)])

(defn- render-branch [node path on-edit on-add]
  [:div.bpm-branch
   (doall
    (for [[i cn] (map-indexed vector (or (:condition-nodes node) []))]
      ^{:key (:id cn)}
      [:div.bpm-branch-item {:class (if (= i 0) "primary" "")}
       [:div.bpm-branch-label (:name cn)]
       (when-let [child (:child-node cn)]
         [:div.bpm-node-column
          (render-node child (conj path :condition-nodes i :child-node) on-edit on-add)
          (render-connector #(on-add (conj path :condition-nodes i :child-node)))])]))])

(defn render-node
  "递归渲染节点树。path 为从根到当前节点的 assoc-in 路径。"
  [node path on-edit on-add]
  (if (nil? node)
    [:div]
    (let [type (:type node)]
      [:div.bpm-node-column
       (cond
         (= type "START_USER_NODE") (render-capsule node false #(on-edit path))
         (= type "END_EVENT_NODE") (render-capsule node true #(on-edit path))
         (and (str/includes? (or type "") "BRANCH") (seq (:condition-nodes node)))
         (render-branch node path on-edit on-add)
         :else (render-card node #(on-edit path)))
       (when-let [child (:child-node node)]
         [:div.bpm-node-column
          (render-connector #(on-add (conj path :child-node)))
          (render-node child (conj path :child-node) on-edit on-add)])])))

;; ── 设计器组件（r/atom + with-let component-did-mount）────────────────

(defn bpm-flow-designer
  "HTML/flex 流程编辑器。参数 {:model-id :on-saved}。"
  [{:keys [model-id on-saved]}]
  (r/with-let [tree (r/atom nil)
               loading (r/atom true)
               edit-path (r/atom nil)
               edit-name (r/atom "")
               add-path (r/atom nil)
               _ (when model-id
                   (api/bpm-model-tree model-id
                                       (fn [res]
                                         (reset! tree (walk/keywordize-keys (:data res)))
                                         (reset! loading false))
                                       (fn [e] (reset! loading false) (antd/error! (str "加载流程失败: " e)))))
               open-edit (fn [path] (reset! edit-path path) (reset! edit-name (get-in @tree (conj path :name))))
               apply-edit (fn [] (when-let [p @edit-path] (swap! tree assoc-in (conj p :name) @edit-name)) (reset! edit-path nil))
               save-tree (fn [] (when (and model-id @tree)
                                  (api/bpm-save-model-tree model-id @tree
                                                           (fn [_] (antd/success! "流程已保存") (when on-saved (on-saved)))
                                                           (fn [e] (antd/error! (str "保存失败: " e))))))]
    [:div
     [:div {:style {:display "flex" :justifyContent "flex-end" :gap 8 :marginBottom 8}}
      [antd/button {:size "small" :type "primary" :on-click save-tree} "保存流程"]]
     (if @loading
       [:div {:style {:padding 48 :textAlign "center"}} "加载中..."]
       [:div.bpm-flow-root
        (when-let [t @tree]
          (render-node t [] open-edit (fn [path] (reset! add-path path))))])
     [antd/modal {:title "编辑节点" :open (boolean @edit-path) :footer nil
                  :width 420 :onCancel #(reset! edit-path nil)}
      [antd/input {:value @edit-name :onChange (fn [e] (reset! edit-name (-> e .-target .-value)))}]
      [antd/button {:type "primary" :block true :style {:marginTop 12} :on-click apply-edit} "确定"]]
     [antd/modal {:title "在此添加节点" :open (boolean @add-path) :footer nil
                  :width 400 :onCancel #(reset! add-path nil)}
      [:div {:style {:color "#666"}} "添加节点功能开发中。"]]]))
