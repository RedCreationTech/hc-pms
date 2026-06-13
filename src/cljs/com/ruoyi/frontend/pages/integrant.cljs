(ns com.ruoyi.frontend.pages.integrant
  "Integrant config -> system 依赖可视化页面。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [clojure.string :as str]
   ["@ant-design/icons" :refer [ReloadOutlined]]
   [com.ruoyi.frontend.antd :as antd]))

;; ─── 数据 → Tree ─────────────────────────────────────────────────────

(defn- ->tree-nodes
  ([label value] (->tree-nodes "" label value))
  ([path label value]
   (let [ref? (and (map? value) (:__ig_ref value))
         node-path (str path "/" label)]
     (cond
       ref?
       [{:title (r/as-element
                 [:span {:style {:color "#1677ff" :fontWeight 500}}
                  (str "→ " (:key value))])
         :key (str node-path "-ref-" (:key value))}]

       (and (map? value) (seq value))
       [{:title (str label)
         :key node-path
         :children (mapcat (fn [[k v]]
                             (->tree-nodes node-path (str k) v))
                           (sort-by key value))}]

       (and (sequential? value) (seq value))
       [{:title (str label " [" (count value) "]")
         :key node-path
         :children (mapcat (fn [[i v]]
                             (->tree-nodes node-path (str "[" i "]") v))
                           (map-indexed vector value))}]

       :else
       [{:title (r/as-element
                 [:span
                  [:span {:style {:color "#999"}} (str label " = ")]
                  (pr-str value)])
         :key (str node-path "-leaf")}]))))

(defn- collect-tree-keys [nodes]
  (mapcat (fn [n]
            (cons (:key n)
                  (collect-tree-keys (:children n))))
          nodes))

(defn- trace-key [k]
  (if (keyword? k)
    (subs (str k) 1)
    (str k)))

;; ─── 依赖图 ─────────────────────────────────────────────────────────

(defn- compute-depths [order deps]
  (let [depth-fn (fn rec [k]
                   (let [ds (get deps (keyword k))]
                     (if (seq ds)
                       (inc (apply max (map rec ds)))
                       0)))]
    (into {} (map (fn [k] [k (depth-fn k)]) order))))

(defn- dep-graph [data selected trace-by-key on-select on-toggle]
  (let [{:keys [order dependencies system]} data
        depths (compute-depths order dependencies)
        by-depth (group-by #(get depths %) order)
        layer-height (+ 50 (* 34 (apply max 1 (map count (vals by-depth)))))
        node-pos (into {}
                       (for [[d keys] by-depth
                             [i k] (map-indexed vector keys)]
                         [k {:x (+ 80 (* d 200))
                             :y (+ 40 (* i 34) (/ (- layer-height (* (count keys) 34)) 2))}]))
        width (+ 160 (* (apply max 0 (vals depths)) 200))
        height layer-height]
    [:svg {:width width :height height
           :className "integrant-dep-graph"
           :style {:border "1px solid #f0f0f0" :background "#fafafa" :borderRadius 4}}
     [:defs
      [:marker {:id "ig-arrow" :markerWidth 8 :markerHeight 8
                :refX 7 :refY 4 :orient "auto" :markerUnits "strokeWidth"}
       [:path {:d "M0,0 L0,8 L8,4 z" :fill "#999"}]]]
     (for [k order
          d (get dependencies (keyword k))]
       (when-let [p1 (get node-pos d)]
         (when-let [p2 (get node-pos k)]
           ^{:key (str d "->" k)}
           [:line {:x1 (:x p1) :y1 (:y p1)
                   :x2 (- (:x p2) 75) :y2 (:y p2)
                   :stroke "#999" :strokeWidth 1
                   :markerEnd "url(#ig-arrow)"}])))
     (for [[k pos] node-pos]
       (let [function? (= "function" (get-in system [(keyword k) :kind]))
             active? (boolean (get-in trace-by-key [k :active]))]
         ^{:key k}
         [:g {:transform (str "translate(" (:x pos) "," (:y pos) ")")
              :style {:cursor "pointer"}
              :onClick #(on-select k)}
          [:rect {:x -75 :y -16 :width 150 :height 32
                  :rx 4 :ry 4
                  :fill (if (= k selected) "#1677ff" "#fff")
                  :stroke (if (= k selected) "#1677ff" "#d9d9d9")}]
          [:text {:x (if function? -18 0) :y 4 :textAnchor "middle"
                  :fill (if (= k selected) "#fff" "#333")
                  :fontSize 11}
           k]
          (when function?
            [:g {:transform "translate(42,-8)"
                 :style {:cursor "pointer"}
                 :onClick (fn [e]
                            (.stopPropagation e)
                            (on-toggle k (not active?)))}
             [:rect {:x 0 :y 0 :width 28 :height 16 :rx 8 :ry 8
                     :fill (if active? "#52c41a" "#d9d9d9")}]
             [:circle {:cx (if active? 20 8) :cy 8 :r 6 :fill "#fff"}]])]))]))

(defn- trace-log-line [log]
  (let [error? (= "error" (:type log))]
    [:div {:style {:fontFamily "monospace"
                   :fontSize 12
                   :borderBottom "1px solid #f0f0f0"
                   :padding "6px 0"}}
     [:div
      [:span {:style {:color (if error? "#ff4d4f" "#52c41a")
                      :fontWeight 600}}
       (if error? "[error]" "[return]")]
      [:span {:style {:color "#999" :marginLeft 8}}
       (str (:duration log) "ms")]]
     [:div {:style {:color "#666"}}
      [:span {:style {:color "#1890ff"}} "in "]
      (pr-str (:args log))]
     [:div {:style {:color "#666"}}
      [:span {:style {:color (if error? "#ff4d4f" "#52c41a")}}
       (if error? "err " "out ")]
      (pr-str (if error? (:error log) (:result log)))]]))

;; ─── 主页面 ─────────────────────────────────────────────────────────

(defn integrant-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:integrant/fetch])
     js/undefined)
   [])
  (let [data @(rf/subscribe [:integrant/data])
        [selected set-selected!] (hooks/use-state nil)
        [expanded set-expanded!] (hooks/use-state #{})
        trace-data @(rf/subscribe [:integrant/trace selected])
        trace-by-key @(rf/subscribe [:integrant/traces])]
    (hooks/use-effect
     (fn []
       (when (and (nil? selected) (seq (:order data)))
         (set-selected! (first (:order data))))
       js/undefined)
     [data])
    (hooks/use-effect
     (fn []
       (when data
         (set-expanded! (set (collect-tree-keys (->tree-nodes "config" (:config data))))))
       js/undefined)
     [data])
    (hooks/use-effect
     (fn []
       (when data
         (doseq [[k sys] (:system data)]
           (when (= "function" (:kind sys))
             (rf/dispatch [:integrant/fetch-trace-logs (trace-key k)]))))
       js/undefined)
     [data])
    (hooks/use-effect
     (fn []
       (if (and selected (:active trace-data))
         (let [id (js/setInterval #(rf/dispatch [:integrant/fetch-trace-logs selected]) 1000)]
           #(js/clearInterval id))
         js/undefined))
     [selected (:active trace-data)])
    (if (nil? data)
      [:div {:style {:textAlign "center" :padding 48 :color "#999"}} "加载中..."]
      (let [tree-data (->tree-nodes "config" (:config data))
            sel-k (keyword selected)
            deps (get-in data [:dependencies sel-k] [])
            dents (get-in data [:dependents sel-k] [])
            sys (get-in data [:system sel-k])
            is-fn? (= "function" (:kind sys))]
        [:div
         [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
          [:h3 {:style {:margin 0}} "Integrant 依赖视图"]
          [antd/button {:icon (r/as-element [:> ReloadOutlined])
                        :onClick #(rf/dispatch [:integrant/fetch])}
           "刷新"]]
         [:div {:style {:display "grid"
                        :gridTemplateColumns "repeat(auto-fit, minmax(320px, 1fr))"
                        :gap 16}}
          ;; 静态配置
          [antd/card {:title "静态配置 (Config)"
                      :size "small"
                      :styles {:body {:padding 12 :maxHeight 480 :overflow "auto"}}}
           [antd/tree {:treeData (clj->js tree-data)
                       :expandedKeys (clj->js expanded)
                       :onExpand #(set-expanded! (set %2))
                       :onSelect #(set-expanded! (let [k (first %1)]
                                                   (if (contains? expanded k)
                                                     (disj expanded k)
                                                     (conj expanded k))))
                       :showLine true}]]
          ;; 启动顺序
          [antd/card {:title "启动顺序 / 依赖 (Order)"
                      :size "small"
                      :styles {:body {:padding 12 :maxHeight 480 :overflow "auto"}}}
           [:div {:style {:display "flex" :flexDirection "column" :gap 4}}
            (for [[idx k] (map-indexed vector (:order data))]
              ^{:key k}
              [:div {:style {:padding "8px 12px"
                             :borderRadius 4
                             :cursor "pointer"
                             :background (if (= k selected) "#e6f7ff" "#fafafa")
                             :border (if (= k selected) "1px solid #1677ff" "1px solid #f0f0f0")}
                     :onClick #(set-selected! k)}
               [:span {:style {:color "#999" :marginRight 8 :fontSize 12}} (inc idx)]
               [:span {:style {:fontWeight (if (= k selected) 600 400)}} k]])]]
          ;; 运行时详情
          [antd/card {:title (str "运行时详情: " (or selected "-"))
                      :size "small"
                      :styles {:body {:padding 12}}}
           [antd/descriptions {:bordered true :size "small" :column 1}
            [antd/descriptions-item {:label "类型"}
             (or (:kind sys) "-")]
            [antd/descriptions-item {:label "依赖"}
             (if (seq deps)
               [antd/space {:size 4 :wrap true}
                (for [d deps]
                  ^{:key d}
                  [antd/tag {:color "blue" :style {:cursor "pointer"} :onClick #(set-selected! d)} d])]
               "-")]
            [antd/descriptions-item {:label "被依赖"}
             (if (seq dents)
               [antd/space {:size 4 :wrap true}
                (for [d dents]
                  ^{:key d}
                  [antd/tag {:color "green" :style {:cursor "pointer"} :onClick #(set-selected! d)} d])]
               "-")]
            (when is-fn?
              [antd/descriptions-item {:label "输入/输出采集"}
               [antd/switch {:checked (boolean (:active trace-data))
                             :checkedChildren "开启"
                             :unCheckedChildren "关闭"
                             :onChange #(rf/dispatch [:integrant/toggle-trace selected %])}]])
            [antd/descriptions-item {:label "运行时摘要"}
             (if sys
               [:pre {:style {:margin 0 :fontSize 12 :whiteSpace "pre-wrap"}}
                (js/JSON.stringify (clj->js sys) nil 2)]
               "-")]]
           (when (and is-fn? (seq (:logs trace-data)))
             [:div {:style {:marginTop 12}}
              [:div {:style {:fontWeight 500 :marginBottom 8}} "输入/输出日志"]
              [:div {:style {:maxHeight 240 :overflow "auto" :background "#fafafa" :padding "0 12px"}}
               (for [log (reverse (:logs trace-data))]
                 ^{:key (:id log)}
                 [trace-log-line log])]])]]
         [:div {:style {:marginTop 16}}
          [antd/card {:title "依赖图 (Dependency Graph)"
                      :size "small"
                      :styles {:body {:padding 12 :overflow "auto"}}}
           [dep-graph data selected trace-by-key
            #(set-selected! %)
            #(rf/dispatch [:integrant/toggle-trace %1 %2])]]]]))))
