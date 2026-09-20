(ns com.ruoyi.frontend.theme
  "主题配置，支持亮色/暗色切换。"
  (:require
    ["antd" :refer [theme]]))


(def algorithms
  {:default  #js []
   :dark     #js [theme.darkAlgorithm]
   :compact  #js [theme.compactAlgorithm]
   :dark-compact #js [theme.darkAlgorithm theme.compactAlgorithm]})


(def ruoyi-font-family
  "\"Helvetica Neue\", Helvetica, \"PingFang SC\", \"Hiragino Sans GB\", \"Microsoft YaHei\", Arial, sans-serif")


(defn theme-config
  "根据当前主题设置构建 antd ConfigProvider 主题配置。"
  [{:keys [mode primary-color algorithm font-size]
    :or   {mode :light primary-color "#1677ff" algorithm "default" font-size "middle"}}]
  (let [is-dark? (= mode :dark)
        token-font-size (case font-size
                          "small" 13
                          "large" 16
                          14)
        algo-key (cond
                   (and is-dark? (= algorithm "compact")) :dark-compact
                   is-dark? :dark
                   (= algorithm "dark") :dark
                   (= algorithm "compact") :compact
                   :else :default)
        base-tokens (if is-dark?
                      #js {:colorPrimary primary-color
                           :colorBgBase "#000"
                           :colorBgContainer "#141414"
                           :colorBgElevated "#1f1f1f"
                           :colorBgLayout "#000"
                           :colorText "rgba(255,255,255,0.88)"
                           :colorTextBase "#fff"
                           :colorTextSecondary "rgba(255,255,255,0.65)"
                           :colorBorder "#424242"
                           :colorBorderSecondary "#303030"
                           :fontSize token-font-size
                           :fontFamily ruoyi-font-family
                           :borderRadius 6}
                      #js {:colorPrimary primary-color
                           :colorBgBase "#fff"
                           :colorBgContainer "#fff"
                           :colorBgElevated "#fff"
                           :colorBgLayout "#f5f5f5"
                           :colorText "rgba(0,0,0,0.88)"
                           :colorTextBase "#000"
                           :colorTextSecondary "rgba(0,0,0,0.65)"
                           :colorBorder "#d9d9d9"
                           :colorBorderSecondary "#f0f0f0"
                           :fontSize token-font-size
                           :fontFamily ruoyi-font-family
                           :borderRadius 6})
        components (if is-dark?
                     #js {:Layout #js {:headerBg "#141414" :bodyBg "#000" :triggerBg "#1f1f1f"}
                          :Menu #js {:itemBg "transparent"
                                     :itemColor "rgba(255,255,255,0.88)"
                                     :itemSelectedColor primary-color
                                     :itemSelectedBg "rgba(255,255,255,0.08)"
                                     :subMenuItemBg "#000"}}
                     #js {:Layout #js {:headerBg "#fff" :bodyBg "#f5f5f5" :triggerBg "#fff"}
                          :Menu #js {:itemBg "transparent"
                                     :itemColor "rgba(0,0,0,0.88)"
                                     :itemSelectedColor primary-color
                                     :itemSelectedBg "rgba(0,0,0,0.06)"
                                     :subMenuItemBg "#fff"}})]
    #js {:algorithm (get algorithms algo-key #js [])
         :token base-tokens
         :components components}))
