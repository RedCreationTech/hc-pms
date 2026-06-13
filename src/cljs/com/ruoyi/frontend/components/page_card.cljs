(ns com.ruoyi.frontend.components.page-card
  "页面内容卡片容器。"
  (:require
   [com.ruoyi.frontend.antd :as antd]))

(defn page-card [{:keys [title extra]} & children]
  (into [antd/card {:title title
                    :extra extra
                    :styles {:body {:background "var(--ant-color-bg-container, #fff)"}}
                    :style {:border "1px solid var(--ant-color-border-secondary, #e8e8e8)"
                            :borderRadius 8}}]
        children))
