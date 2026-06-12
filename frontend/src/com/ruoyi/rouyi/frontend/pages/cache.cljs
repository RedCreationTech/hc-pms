(ns com.ruoyi.rouyi.frontend.pages.cache
  "缓存监控页面。"
  (:require
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- info-card [{:keys [title children]}]
  [:div {:style {:background "var(--ant-color-bg-container, #fff)"
                 :borderRadius 8
                 :padding 24
                 :marginBottom 16
                 :boxShadow "0 1px 2px rgba(0,0,0,0.1)"}}
   [:h4 {:style {:margin "0 0 16px 0" :fontSize 16 :fontWeight 600}} title]
   children])

(defn- cache-info-section [cache-data]
  (let [name (:name cache-data)
        type (:type cache-data)
        keys-count (:keysCount cache-data)
        memory-used (:memoryUsed cache-data)
        memory-max (:memoryMax cache-data)]
    [info-card {:title "缓存信息"
                :children [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap 16}}
                           [:div [:span {:style {:color "#666"}} "缓存名称："] [:span (or name "N/A")]]
                           [:div [:span {:style {:color "#666"}} "缓存类型："] [:span (or type "N/A")]]
                           [:div [:span {:style {:color "#666"}} "键值数量："] [:span (str (or keys-count 0) " 个")]]
                           [:div [:span {:style {:color "#666"}} "内存使用："] [:span (str (or memory-used 0) " MB / " (or memory-max 0) " MB")]]]}]))

(defn- cache-keys-section [cache-keys]
  (let [keys (:keys cache-keys)
        count (:count cache-keys)]
    [info-card {:title (str "缓存键列表 (" (or count 0) " 个)")
                :children (if (and keys (seq keys))
                            [:div {:style {:maxHeight 400 :overflow "auto"}}
                             [:table {:style {:width "100%" :borderCollapse "collapse"}}
                              [:thead
                               [:tr {:style {:background "#fafafa"}}
                                [:th {:style {:padding "8px 12px" :textAlign "left" :borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"}} "序号"]
                                [:th {:style {:padding "8px 12px" :textAlign "left" :borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"}} "缓存键名"]]]
                              [:tbody
                               (for [[idx k] (map-indexed vector keys)]
                                 ^{:key idx}
                                 [:tr {:style {:borderBottom "1px solid var(--ant-color-border-secondary, #f0f0f0)"}}
                                  [:td {:style {:padding "8px 12px"}} (inc idx)]
                                  [:td {:style {:padding "8px 12px"}} (str k)]])]]]
                            [:div {:style {:textAlign "center" :padding 24 :color "#999"}}
                             "暂无缓存数据"])}]))

(defn cache-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:cache/fetch-info])
     (rf/dispatch [:cache/fetch-keys])
     (fn []))
   [])
  (let [cache-data @(rf/subscribe [:cache/data])
        cache-keys @(rf/subscribe [:cache/keys])
        loading? @(rf/subscribe [:cache/loading?])]
    [:div
     [:div {:style {:marginBottom 16}}
      [antd/button {:type "primary"
                    :onClick #(rf/dispatch [:cache/fetch-info])}
       "刷新"]
      [antd/button {:style {:marginLeft 8}
                    :danger true
                    :onClick #(rf/dispatch [:cache/clear])}
       "清空缓存"]]
     (if loading?
       [:div {:style {:textAlign "center" :padding 48}}
        [antd/button {:loading true} "加载中..."]]
       [:div
        [cache-info-section (or cache-data {})]
        [cache-keys-section (or cache-keys {})]])]))
