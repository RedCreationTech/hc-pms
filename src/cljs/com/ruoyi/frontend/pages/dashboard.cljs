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
                                CloudOutlined CodeOutlined]]
   [com.ruoyi.frontend.antd :as antd]))

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
        [now set-now!] (hooks/use-state (js/Date.))
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
                   :else "晚上好")]
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
      [stat-card {:title "用户总量" :value "1,234" :icon UserOutlined :color "#1677ff"
                  :desc "今日新增 +12"}]
      [stat-card {:title "在线用户" :value "89" :icon TeamOutlined :color "#52c41a"
                  :desc "较昨日 +5"}]
      [stat-card {:title "操作日志" :value "5,678" :icon FileTextOutlined :color "#faad14"
                  :desc "今日 +256"}]
      [stat-card {:title "定时任务" :value "16" :icon ScheduleOutlined :color "#f5222d"
                  :desc "运行中 12"}]]

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
         [quick-link {:title "代码生成" :icon CodeOutlined :color "#eb2f96" :route :gen}]
         [quick-link {:title "系统接口" :icon CloudOutlined :color "#2f54eb" :route :swagger}]]]

       ;; 最近操作
       [antd/card {:title "最近操作" :style {:borderRadius 8}
                   :styles {:body {:padding 0}}}
        [antd/table {:size "small" :showHeader false :pagination false
                     :dataSource (clj->js
                                  [{:key "1" :content "管理员修改了用户信息" :time "2分钟前"}
                                   {:key "2" :content "新增角色：普通用户" :time "15分钟前"}
                                   {:key "3" :content "系统参数配置更新" :time "1小时前"}
                                   {:key "4" :content "定时任务执行成功" :time "2小时前"}
                                   {:key "5" :content "用户登录：admin" :time "3小时前"}])
                     :columns (clj->js
                               [{:dataIndex "content" :key "content" :width "70%"}
                                {:dataIndex "time" :key "time" :align "right"}])}]]]

      ;; 右侧：系统信息
      [antd/card {:title "系统信息" :style {:borderRadius 8}
                  :styles {:body {:padding "0 24px"}}}
       [system-info-item {:label "操作系统" :value "macOS Sonoma"}]
       [system-info-item {:label "系统架构" :value "aarch64"}]
       [system-info-item {:label "Java 版本" :value "JDK 17"}]
       [system-info-item {:label "Clojure" :value "1.12.0"}]
       [system-info-item {:label "数据库" :value "SQLite 3"}]
       [system-info-item {:label "前端框架" :value "Reagent 2.0"}]
       [system-info-item {:label "UI 组件" :value "Ant Design 6"}]
       [system-info-item {:label "构建工具" :value "shadow-cljs"}]]]]))
