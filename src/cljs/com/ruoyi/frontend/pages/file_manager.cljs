(ns com.ruoyi.frontend.pages.file-manager
  "文件管理页面 — 文件上传、列表、下载、删除。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [UploadOutlined DownloadOutlined DeleteOutlined ReloadOutlined FileTextOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.page-toolbar :as page-toolbar]))

;; ─── 工具函数 ──────────────────────────────────────────────────────

(defn- format-size [bytes]
  (cond (< bytes 1024) (str bytes " B")
        (< bytes 1048576) (str (.toFixed (/ bytes 1024) 1) " KB")
        :else (str (.toFixed (/ bytes 1048576) 1) " MB")))

;; ─── 文件列表列 ──────────────────────────────────────────────────────

(defn- file-columns []
  #js [#js {:title "文件名" :dataIndex "name" :key "name"
            :render (fn [v _]
                      (r/as-element [:> FileTextOutlined {:style {:marginRight 8 :color "#1677ff"}} v]))}
       #js {:title "大小" :dataIndex "size" :key "size" :width 120
            :render (fn [v _] (r/as-element [:span (format-size v)]))}
       #js {:title "修改时间" :dataIndex "modified" :key "modified" :width 180
            :render (fn [v _] (r/as-element [:span (js/Date. v)]))}
       #js {:title "操作" :key "action" :width 180
            :render (fn [_ ^js record]
                      (r/as-element
                       [antd/space
                        [antd/button {:type "link" :size "small"
                                      :icon (r/as-element [:> DownloadOutlined])
                                      :onClick #(rf/dispatch [:file/download (.-name record)])}
                         "下载"]
                        [antd/popconfirm {:title "确认删除该文件？"
                                          :onConfirm #(rf/dispatch [:file/delete (.-name record)])}
                         [antd/button {:type "link" :danger true :size "small"
                                       :icon (r/as-element [:> DeleteOutlined])}
                          "删除"]]]))}])

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn file-manager-page []
  (hooks/use-effect
   (fn [] (rf/dispatch [:file/fetch]) js/undefined)
   [])
  (let [items @(rf/subscribe [:file/items])
        loading? @(rf/subscribe [:file/loading?])]
    [:div
     [page-toolbar/page-toolbar
      {:left [page-toolbar/toolbar-left
              [antd/upload {:showUploadList false
                            :beforeUpload (fn [file]
                                            (rf/dispatch [:file/upload file])
                                            false)}
               [page-toolbar/toolbar-button {:kind :import
                                             :icon (r/as-element [:> UploadOutlined])
                                             :label "上传"}]]]
       :right [page-toolbar/toolbar-right
               [page-toolbar/round-tool-button {:title "刷新"
                                                :icon (r/as-element [:> ReloadOutlined])
                                                :on-click #(rf/dispatch [:file/fetch])}]]}]
     [antd/table {:scroll #js {:x "max-content"} :rowKey "name" :loading loading? :columns (file-columns)
                  :dataSource (clj->js items) :pagination false}]]))
