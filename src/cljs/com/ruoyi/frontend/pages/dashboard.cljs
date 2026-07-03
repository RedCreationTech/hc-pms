(ns com.ruoyi.frontend.pages.dashboard
  "仪表盘首页 — 统计卡片 + 快捷入口 + 系统信息。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [UserOutlined TeamOutlined MenuOutlined
                                FileTextOutlined ScheduleOutlined
                                DashboardOutlined SettingOutlined
                                SafetyOutlined DatabaseOutlined
                                CloudOutlined]]
   [com.ruoyi.frontend.antd :as antd]))

;; ─── 工具函数 ──────────────────────────────────────────────────────

(defn- format-number
  "格式化数字为千位分隔。"
  [n]
  (if (nil? n)
    "-"
    (let [s (str n)]
      (if (<= (count s) 3)
        s
        (clojure.string/join "," (map clojure.string/join
                                      (reverse (partition-all 3 (reverse s)))))))))

(defn- relative-time
  "将时间字符串转为相对时间描述。"
  [time-str]
  (if (nil? time-str)
    "-"
    (try
      (let [t (if (string? time-str)
                (js/Date. time-str)
                time-str)
            now (js/Date.)
            diff-ms (- (.getTime now) (.getTime t))
            diff-min (quot diff-ms 60000)
            diff-hour (quot diff-min 60)
            diff-day (quot diff-hour 24)]
        (cond
          (< diff-min 1) "刚刚"
          (< diff-min 60) (str diff-min "分钟前")
          (< diff-hour 24) (str diff-hour "小时前")
          (< diff-day 30) (str diff-day "天前")
          :else time-str))
      (catch js/Error _ time-str))))

;; ─── 统计卡片 ──────────────────────────────────────────────────────

(defn- stat-card [{:keys [title value icon color desc]}]
  [antd/card {:hoverable true
              :style {:borderRadius 8 :overflow "hidden"}
              :styles {:body {:padding "20px 24px"}}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"}}
    [:div
     [:div {:style {:fontSize 14 :color "var(--ant-color-text-secondary)" :marginBottom 8}}
      title]
     [:div {:style {:fontSize 32 :fontWeight 700 :color color :lineHeight 1}}
      value]
     (when desc
       [:div {:style {:fontSize 12 :color "var(--ant-color-text-tertiary)" :marginTop 8}}
        desc])]
    [:div {:style {:width 56 :height 56 :borderRadius 12 :background (str color "15")
                   :display "flex" :alignItems "center" :justifyContent "center"}}
     [:> icon {:style {:fontSize 28 :color color}}]]]])

;; ─── 快捷入口 ──────────────────────────────────────────────────────

(defn- quick-link [{:keys [title icon color route]}]
  [antd/button {:type "text"
                :style {:height "auto" :padding "12px 16px" :display "flex" :alignItems "center" :gap 12
                        :borderRadius 8 :width "100%" :justifyContent "flex-start"
                        :transition "all 0.2s"}
                :on-click #(rf/dispatch [:navigate route])}
   [:div {:style {:width 40 :height 40 :borderRadius 10 :background (str color "15")
                  :display "flex" :alignItems "center" :justifyContent "center"}}
    [:> icon {:style {:fontSize 20 :color color}}]]
   [:span {:style {:fontSize 14 :fontWeight 500}} title]])

;; ─── 系统信息 ──────────────────────────────────────────────────────

(defn- system-info-item [{:keys [label value]}]
  [:div {:style {:display "flex" :justifyContent "space-between" :padding "8px 0"
                 :borderBottom "1px solid var(--ant-color-split, #f0f0f0)"}}
   [:span {:style {:color "var(--ant-color-text-secondary)"}} label]
   [:span {:style {:fontWeight 500}} (or value "-")]])

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn dashboard-page []
  (let [user @(rf/subscribe [:auth/user])
        stats @(rf/subscribe [:dashboard/stats])
        loading? @(rf/subscribe [:dashboard/loading?])
        [now set-now!] (hooks/use-state (js/Date.))
        _ (hooks/use-effect
           (fn []
             (rf/dispatch [:dashboard/fetch])
             js/undefined)
           [])
        _ (hooks/use-effect
           (fn []
             (let [interval (js/setInterval #(set-now! (js/Date.)) 1000)]
               (fn [] (js/clearInterval interval))))
           [])
        hour (.getHours now)
        greeting (cond
                   (< hour 6) "夜深了"
                   (< hour 12) "上午好"
                   (< hour 14) "中午好"
                   (< hour 18) "下午好"
                   :else "晚上好")
        server (:server stats)
        os-info (:os server)
        jvm-info (:jvm server)
        recent-ops (or (:recentOps stats) [])]
    [antd/spin {:spinning loading?}
     [:div {:style {:padding "0 0 24px"}}
      ;; 欢迎区域
      [antd/card {:style {:marginBottom 16 :borderRadius 8 :overflow "hidden"
                          :background "linear-gradient(135deg, #667eea 0%, #764ba2 100%)"
                          :border "none"}
                  :styles {:body {:padding "32px 40px"}}}
       [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"}}
        [:div
         [:div {:style {:fontSize 24 :fontWeight 600 :color "#fff" :marginBottom 8}}
          (str greeting "，" (or (:nick_name user) (:user_name user) "管理员"))]
         [:div {:style {:fontSize 14 :color "rgba(255,255,255,0.85)"}}
          "欢迎回到若依管理系统，今天也是元气满满的一天！"]]
        [:div {:style {:textAlign "right"}}
         [:div {:style {:fontSize 14 :color "rgba(255,255,255,0.65)"}} "当前时间"]
         [:div {:style {:fontSize 28 :fontWeight 600 :color "#fff" :fontFamily "monospace"}}
          (.toLocaleTimeString now)]]]]

      ;; 统计卡片
      [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, 1fr)" :gap 16 :marginBottom 16}}
       [stat-card {:title "用户总量"
                   :value (format-number (:userCount stats))
                   :icon UserOutlined :color "#1677ff"}]
       [stat-card {:title "在线用户"
                   :value (format-number (:onlineCount stats))
                   :icon TeamOutlined :color "#52c41a"}]
       [stat-card {:title "操作日志"
                   :value (format-number (:operLogCount stats))
                   :icon FileTextOutlined :color "#faad14"}]
       [stat-card {:title "定时任务"
                   :value (format-number (:jobTotal stats))
                   :icon ScheduleOutlined :color "#f5222d"
                   :desc (when stats (str "运行中 " (or (:jobRunning stats) 0)))}]]

      [:div {:style {:display "grid" :gridTemplateColumns "2fr 1fr" :gap 16}}
       ;; 左侧：快捷入口 + 最近操作
       [:div
        ;; 快捷入口
        [antd/card {:title "快捷操作" :style {:marginBottom 16 :borderRadius 8}
                    :styles {:body {:padding "12px 16px"}}}
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, 1fr)" :gap 8}}
          [quick-link {:title "用户管理" :icon UserOutlined :color "#1677ff" :route :user}]
          [quick-link {:title "角色管理" :icon SafetyOutlined :color "#52c41a" :route :role}]
          [quick-link {:title "菜单管理" :icon MenuOutlined :color "#faad14" :route :menu}]
          [quick-link {:title "部门管理" :icon TeamOutlined :color "#f5222d" :route :dept}]
          [quick-link {:title "字典管理" :icon DatabaseOutlined :color "#722ed1" :route :dict}]
          [quick-link {:title "参数设置" :icon SettingOutlined :color "#13c2c2" :route :config}]
          [quick-link {:title "系统接口" :icon CloudOutlined :color "#2f54eb" :route :swagger}]]]

        ;; 最近操作
        [antd/card {:title "最近操作" :style {:borderRadius 8}
                    :styles {:body {:padding 0}}}
         (if (empty? recent-ops)
           [:div {:style {:padding "24px" :textAlign "center"
                          :color "var(--ant-color-text-tertiary)"}}
            "暂无操作记录"]
           [antd/table {:size "small" :showHeader false :pagination false
                        :dataSource (clj->js
                                     (map-indexed
                                      (fn [idx op]
                                        {:key (str idx)
                                         :content (str (or (:oper_name op) "系统")
                                                       (case (:business_type op)
                                                         0 " "
                                                         1 "新增 "
                                                         2 "修改 "
                                                         3 "删除 "
                                                         4 "授权 "
                                                         5 "导出 "
                                                         6 "导入 "
                                                         " ")
                                                       (or (:title op) ""))
                                         :time (relative-time (:oper_time op))})
                                      recent-ops))
                        :columns (clj->js
                                  [{:dataIndex "content" :key "content" :width "70%"}
                                   {:dataIndex "time" :key "time" :align "right"}])}])]]

       ;; 右侧：系统信息
       [antd/card {:title "系统信息" :style {:borderRadius 8}
                   :styles {:body {:padding "0 24px"}}}
        [system-info-item {:label "操作系统"
                           :value (when os-info
                                    (str (:osName os-info) " " (:osVersion os-info)))}]
        [system-info-item {:label "系统架构" :value (:osArch os-info)}]
        [system-info-item {:label "主机名称" :value (:computerName os-info)}]
        [system-info-item {:label "Java 版本"
                           :value (when jvm-info
                                    (str (:jvmName jvm-info) " " (:jvmVersion jvm-info)))}]
        [system-info-item {:label "JVM 内存"
                           :value (when jvm-info
                                    (str "已用 " (:used jvm-info) "MB / 最大 "
                                         (:max jvm-info) "MB"))}]
        [system-info-item {:label "运行时长" :value (:runTime jvm-info)}]
        [system-info-item {:label "前端框架" :value "Reagent 2.0"}]
        [system-info-item {:label "UI 组件" :value "Ant Design 6"}]]]]]))
