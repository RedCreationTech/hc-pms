(ns com.ruoyi.frontend.pages.pms.platform
  "平台能力与兼容基线 (G18): 展示服务端真实运行环境, 支持的数据库与浏览器基线, 本次请求客户端的判定, 以及报表指标口径分布.
   全部取值来自后端只读核对, 未上报或未识别的项如实标注, 不当作通过."
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.pages.pms.shared :as shared]
    [com.ruoyi.frontend.pages.pms.widgets :as w]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(defn- verdict
  "把布尔判定转为可读结论; nil 表示未上报, 不能算通过."
  [value]
  (cond (true? value) [antd/tag {:color "green"} "符合"]
        (false? value) [antd/tag {:color "red"} "不符合"]
        :else [antd/tag "未上报"]))


(defn- runtime-section
  [{:keys [data]}]
  (let [java (:java data)
        database (:database data)
        client (:client data)
        env (:environment data)]
    [antd/card {:title "运行环境" :size "small" :styles {:body {:padding 16}}}
     ;; 用 :items (项目里 config.cljs / detail.cljs 的既有写法) 而不是 children 子元素:
     ;; 子元素写法下 Reagent 会把多个 descriptions-item 当无 key 的列表展开, React 报
     ;; "Each child in a list should have a unique key prop" (G18 跨引擎实测发现).
     [antd/descriptions {:column 2 :size "small" :bordered true
                         :items (mapv (fn [[k label children]]
                                        {:key (name k) :label label :children (r/as-element children)})
                                      [[:jdk "JDK 版本"
                                        [:span (str (:version java) " (主版本 " (:major java) ", 基线 " (:required java) ") ")
                                         [verdict (:supported java)]]]
                                       [:jdk_vendor "JDK 厂商" [:span (:vendor java)]]
                                       [:database "数据库"
                                        [:span (str (or (:label database) "未识别") " / 迁移目录 " (or (:migration_dir database) "-"))]]
                                       [:database_supported "数据库支持" [verdict (:supported database)]]
                                       [:frontend "前端构建"
                                        [:span (str (get-in data [:frontend :framework]) " / " (get-in data [:frontend :build]))]]
                                       [:client "本次客户端"
                                        [:span (if (:reported client)
                                                 (str (or (:label client) (:family client)) " " (:version client)
                                                      (when (:minimum client) (str " (基线 " (:minimum client) ")")))
                                                 "未上报 User-Agent")]]
                                       [:client_supported "客户端兼容" [verdict (:supported client)]]
                                       [:generated_at "核对时间" [:span (:generated_at data)]]])}]
     [:div {:style {:marginTop 12 :fontSize 12 :color "#8793a3" :lineHeight 1.8}}
      [:div (str "环境核对 (只读): 授权项目 " (:project_rows env) " 个, 任务 "
                 (:task_rows env) " 条, 费用明细 " (:cost_entry_rows env) " 条.")]]]))


(defn- database-columns
  []
  [{:title "数据库" :dataIndex "label" :width 160}
   {:title "类型" :dataIndex "kind" :width 120}
   {:title "迁移目录" :dataIndex "migration_dir" :width 200
    :render (fn [v] (or v "-"))}
   {:title "当前" :dataIndex "current" :width 90
    :render (fn [v] (r/as-element [antd/tag {:color (if v "blue" "default")} (if v "当前" "支持")]))}])


(defn- browser-columns
  []
  [{:title "浏览器" :dataIndex "label" :width 220}
   {:title "族" :dataIndex "family" :width 120}
   {:title "最低主版本" :dataIndex "minimum" :width 120 :align "right"}
   {:title "本次请求" :dataIndex "current" :width 110
    :render (fn [v] (r/as-element (if v [antd/tag {:color "blue"} "是"] [antd/tag "否"])))}])


(defn- metric-columns
  []
  [{:title "指标对象" :dataIndex "label" :width 200}
   {:title "对象类型" :dataIndex "object_type" :width 160}
   {:title "已发布指标数" :dataIndex "metric_count" :width 140 :align "right"}])


(defn- matrix-section
  "兼容矩阵规则配置: 数据库支持、浏览器基线与报表指标口径分布, 全部来自后端只读核对."
  [{:keys [data]}]
  (let [[tab set-tab!] (hooks/use-state "databases")
        ;; row-key 必须给: AntD Table 拿不到唯一行键时会回退用索引, React 于是对 tbody 的
        ;; 子行报 "Each child in a list should have a unique key prop" (G18 跨引擎实测发现).
        tab-items (mapv (fn [[key label columns rows note row-key]]
                          {:key key :label label
                           :children (r/as-element
                                       [:div
                                        (when note [:div {:style {:marginBottom 8 :color "#8793a3" :fontSize 12}} note])
                                        [antd/table {:size "small" :pagination false
                                                     :rowKey row-key
                                                     :columns (columns)
                                                     :dataSource (clj->js (vec (or rows [])))}]])})
                        [["databases" "数据库"
                          database-columns (:databases data)
                          "仅支持本项目提供迁移脚本的数据库; 其余一律如实标为未识别, 不做猜测."
                          "kind"]
                         ["browsers" "浏览器"
                          browser-columns (:browsers data)
                          "可识别的主流浏览器最低主版本; 未上报 User-Agent 的请求不判定通过."
                          "family"]
                         ["metrics" "指标口径"
                          metric-columns (get-in data [:metrics :definitions])
                          (str "已发布指标定义 " (get-in data [:metrics :published]) " 个.")
                          "key"]])]
    [antd/card {:title "兼容矩阵规则配置" :size "small" :styles {:body {:padding 16}}}
     [antd/tabs {:activeKey tab :onChange set-tab! :items tab-items}]]))


(defn- readiness-section
  "H19 运行就绪核对: 真实连接往返探测, 迁移版本对账与关键配置在位情况; 只读, 配置项只报在位与否."
  [{:keys [data]}]
  (let [db (:database data)
        m (:migrations data)
        pending (:pending-count data)
        unreadable (:unreadable m)
        ready (:ready data)]
    [antd/card {:title "运行就绪核对" :size "small" :styles {:body {:padding 16}}}
     [antd/space {:wrap true :size 4}
      [antd/tag {:color (if ready "green" "red")} (str "就绪判定: " (if ready "就绪" "未就绪"))]
      [antd/tag {:color (if (:connected db) "geekblue" "red")} (str "数据库" (if (:connected db) "连通" "不可达"))]
      [antd/tag {:color (if (:slow db) "orange" "blue")}
       (str "探测耗时 " (:latency-ms db) " ms (阈值 " (:threshold-ms db) " ms)")]
      (when (:error db) [antd/tag {:color "red"} (:error db)])
      [antd/tag {:color (if unreadable "orange" (if (pos? pending) "red" "green"))}
       (if unreadable "迁移清单不可读" (str "迁移 " (:applied m) "/" (:known m) " 已应用"))]
      (when (and (not unreadable) (pos? pending))
        [antd/tag {:color "red"} (str "待应用迁移 " pending)])
      [antd/tag {:color (if (:config-complete data) "green" "orange")}
       (if (:config-complete data) "关键配置齐备" (str "关键配置缺失 " (count (:missing-config data)) " 项"))]
      (when (seq (:missing-config data))
        [antd/tag {:color "volcano"} (str "缺失: " (clojure.string/join " / " (:missing-config data)))])
      [antd/tag {:color "blue"} (str "支持库 " (clojure.string/join " / " (:supports data)))]]]))

(defn platform-page
  "平台能力与兼容基线页."
  []
  (let [resource (shared/use-resource "/platform/capabilities" {} [])
        readiness (shared/use-resource "/platform/readiness" {} [])]
    [:div {:style {:padding 24}}
     [shared/page-heading "PROJECT MANAGEMENT" "平台能力"
      "服务端真实运行环境, 数据库与浏览器兼容基线, 本次请求客户端判定与报表指标口径分布; 未上报或不支持的项如实标注" nil]
     [w/resource-view resource
      (fn [data]
        [:div
         [runtime-section {:data data}]
         [:div {:style {:height 16}}]
         [w/resource-view readiness (fn [ready] [readiness-section {:data ready}])]
         [:div {:style {:height 16}}]
         [matrix-section {:data data}]])]]))