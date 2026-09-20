(ns com.ruoyi.frontend.components.pagination
  "分页组件封装。"
  (:require
    [com.ruoyi.frontend.antd :as antd]))


(defn pagination
  [{:keys [page page-size total on-change]}]
  [antd/pagination {:current page
                    :pageSize page-size
                    :total total
                    :showSizeChanger true
                    :showTotal (fn [total] (str "共 " total " 条"))
                    :onChange on-change}])
