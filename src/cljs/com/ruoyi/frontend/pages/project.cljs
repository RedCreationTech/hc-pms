(ns com.ruoyi.frontend.pages.project
  "项目信息管理页 - 对齐系统标准页。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.page-search :refer [page-search]]
   [com.ruoyi.frontend.components.page-toolbar :refer [page-toolbar]]
   [com.ruoyi.frontend.components.right-toolbar :refer [right-toolbar]]
   [com.ruoyi.frontend.components.status-tag :refer [status-tag]]
   [com.ruoyi.frontend.components.action-menu :refer [action-menu]]
   [com.ruoyi.frontend.components.search-input :refer [search-input]]
   [com.ruoyi.frontend.pages.business :refer [project-modal team-modal]]))

(defn- ok?
  [result]
  (= 200 (:code result)))

(defn- result-id
  [result]
  (get-in result [:data :id]))

(defn- upload-files!
  [{:keys [biz-type biz-id section-key file-purpose files on-done on-error]}]
  (let [files (vec files)
        total-count (count files)]
    (if (zero? total-count)
      (when on-done (on-done))
      (let [remaining (atom total-count)
            failed? (atom false)]
        (doseq [file files]
          (api/upload-business-attachment
           {:biz_type biz-type
            :biz_id biz-id
            :section_key section-key
            :file_purpose file-purpose}
           file
           (fn [result]
             (when-not (ok? result)
               (reset! failed? true)
               (antd/error! (or (:msg result) "附件上传失败")))
             (when (zero? (swap! remaining dec))
               (if @failed?
                 (when on-error (on-error))
                 (when on-done (on-done)))))
           (fn [_]
             (reset! failed? true)
             (antd/error! "附件上传失败")
             (when (zero? (swap! remaining dec))
               (when on-error (on-error))))))))))

(defn- project-columns []
  (let [columns-config @(rf/subscribe [:projects/columns])]
    (clj->js
     (filterv some?
              [(when (get-in columns-config [:project_name :visible?])
                 {:title "项目名称"
                  :dataIndex "project_name"
                  :key "project_name"
                  :render (fn [v record]
                            (r/as-element
                             [:a {:style {:cursor "pointer" :color "var(--ant-color-primary, #1677ff)"}
                                  :on-click #(rf/dispatch [:projects/view-detail (.-id ^js record)])}
                              v]))})
               (when (get-in columns-config [:engineering_industry :visible?])
                 {:title "工程业态" :dataIndex "engineering_industry" :key "engineering_industry"})
               (when (get-in columns-config [:engineering_nature :visible?])
                 {:title "工程性质" :dataIndex "engineering_nature" :key "engineering_nature"})
               (when (get-in columns-config [:status :visible?])
                 {:title "状态" :dataIndex "status" :key "status" :width 100
                  :render (fn [v _]
                            (r/as-element
                             [status-tag {:value v
                                          :options {"0" {:label "正常" :color "green"}
                                                    "1" {:label "停用" :color "red"}}}]))})
               (when (get-in columns-config [:create_time :visible?])
                 {:title "创建时间" :dataIndex "create_time" :key "create_time" :width 160})
               {:title "操作" :key "action" :width 200
                :render (fn [_ record]
                          (let [id (.-id ^js record)]
                            (r/as-element
                             [action-menu
                              {:on-edit #(rf/dispatch [:projects/open-edit id])
                               :on-delete #(rf/dispatch [:projects/delete id])
                               :more-items [{:key :view :label "查看"
                                             :on-click #(rf/dispatch [:projects/view-detail id])}
                                            {:key :team :label "新增分包队伍"
                                             :on-click #(rf/dispatch [:projects/open-team id])}]}])))}]))))

(defn- detail-drawer []
  (let [visible? @(rf/subscribe [:projects/detail-visible?])
        project @(rf/subscribe [:projects/detail-data])]
    [antd/drawer {:title "项目详情"
                  :open visible?
                  :size "large"
                  :onClose #(rf/dispatch [:projects/close-detail])}
     (when project
       [:div {:style {:padding "0 16px"}}
        [antd/descriptions {:column 1 :bordered true :size "small"}
         [antd/descriptions-item {:label "项目名称"} (:project_name project "-")]
         [antd/descriptions-item {:label "工程业态"} (:engineering_industry project "-")]
         [antd/descriptions-item {:label "工程性质"} (:engineering_nature project "-")]
         [antd/descriptions-item {:label "工程地址"} (:project_address project "-")]
         [antd/descriptions-item {:label "建设单位"} (:construction_unit project "-")]
         [antd/descriptions-item {:label "总承包单位"} (:general_contractor_unit project "-")]
         [antd/descriptions-item {:label "状态"}
          [antd/tag {:color (if (= (:status project "0") "0") "green" "red")}
           (if (= (:status project "0") "0") "正常" "停用")]]
         [antd/descriptions-item {:label "创建时间"} (:create_time project "-")]
         [antd/descriptions-item {:label "备注"} (:remark project "-")]]])]))

(defn project-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:projects/fetch {}])
     js/undefined)
   [])
  (let [items @(rf/subscribe [:projects/items])
        total @(rf/subscribe [:projects/total])
        loading? @(rf/subscribe [:projects/loading?])
        query-params @(rf/subscribe [:projects/query-params])
        show-search? @(rf/subscribe [:projects/show-search?])
        columns @(rf/subscribe [:projects/columns])
        selected-ids @(rf/subscribe [:projects/selected-ids])
        selected-empty? @(rf/subscribe [:projects/selected-empty?])
        modal-visible? @(rf/subscribe [:projects/modal-visible?])
        editing @(rf/subscribe [:projects/editing])
        page @(rf/subscribe [:projects/page])
        page-size @(rf/subscribe [:projects/page-size])
        team-visible? @(rf/subscribe [:projects/team-visible?])
        team-project @(rf/subscribe [:projects/team-project])]
    [:div {:style {:display "flex" :flexDirection "column" :gap 12}}
     [page-search {:visible? show-search?}
      [:div {:style {:display "flex" :flexWrap "wrap" :gap 12}}
       [search-input {:label "项目名称"}
        [antd/input {:placeholder "请输入项目名称"
                     :style {:width 200}
                     :value (:project_name query-params)
                     :on-change #(rf/dispatch [:projects/update-query :project_name (.. % -target -value)])}]]
       [search-input {:label "工程业态"}
        [antd/input {:placeholder "请输入工程业态"
                     :style {:width 200}
                     :value (:engineering_industry query-params)
                     :on-change #(rf/dispatch [:projects/update-query :engineering_industry (.. % -target -value)])}]]
       [search-input {:label "工程性质"}
        [antd/input {:placeholder "请输入工程性质"
                     :style {:width 200}
                     :value (:engineering_nature query-params)
                     :on-change #(rf/dispatch [:projects/update-query :engineering_nature (.. % -target -value)])}]]
       [search-input {:label "状态"}
        [antd/select {:placeholder "全部" :style {:width 200} :allowClear true
                      :value (:status query-params)
                      :on-change #(rf/dispatch [:projects/update-query :status %])}
         [antd/select-option {:value "0"} "正常"]
         [antd/select-option {:value "1"} "停用"]]]
       [:div {:style {:display "flex" :gap 8 :alignItems "flex-end"}}
        [antd/button {:type "primary"
                      :icon (r/as-element [:> SearchOutlined])
                      :on-click #(rf/dispatch [:projects/search])}
         "搜索"]
        [antd/button {:icon (r/as-element [:> ReloadOutlined])
                      :on-click #(rf/dispatch [:projects/reset-query])}
         "重置"]]]]
     [page-toolbar
      {:left [:div {:style {:display "flex" :gap 8}}
              [antd/button {:type "primary" :ghost true :size "small"
                            :icon (r/as-element [:> PlusOutlined])
                            :on-click #(rf/dispatch [:projects/open-add])}
               "新增"]
              [antd/button {:type "success" :ghost true :size "small"
                            :icon (r/as-element [:> EditOutlined])
                            :disabled selected-empty?
                            :on-click #(rf/dispatch [:projects/open-edit-selected])}
               "修改"]
              [antd/button {:type "danger" :ghost true :size "small"
                            :icon (r/as-element [:> DeleteOutlined])
                            :disabled selected-empty?
                            :on-click #(rf/dispatch [:projects/batch-delete])}
               "删除"]]
       :right [right-toolbar {:show-search? show-search?
                              :columns columns
                              :on-toggle-search #(rf/dispatch [:projects/toggle-search])
                              :on-refresh #(rf/dispatch [:projects/fetch-with-params])
                              :on-toggle-column #(rf/dispatch [:projects/toggle-column %])}]}]
     [antd/table {:scroll #js {:x "max-content"}
                  :rowKey "id"
                  :rowSelection #js {:type "checkbox"
                                     :selectedRowKeys (clj->js selected-ids)
                                     :onChange (fn [selected-keys _]
                                                 (rf/dispatch [:projects/set-selected (js->clj selected-keys)]))}
                  :columns (project-columns)
                  :dataSource (clj->js items)
                  :loading loading?
                  :pagination {:total total
                               :pageSize page-size
                               :current page
                               :showSizeChanger true
                               :showTotal (fn [total] (str "共 " total " 条"))
                               :onChange (fn [page page-size]
                                           (rf/dispatch [:projects/change-page page page-size]))}}]
     [project-modal {:open? modal-visible?
                     :editing editing
                     :readonly? false
                     :on-ok (fn [payload upload-files]
                              (let [all-files (vec (mapcat second upload-files))
                                    done (fn [biz-id]
                                           (upload-files!
                                            {:biz-type "project"
                                             :biz-id biz-id
                                             :section-key ""
                                             :file-purpose "attachment"
                                             :files all-files
                                             :on-done #(do (antd/success! "保存成功")
                                                           (rf/dispatch [:projects/close-modal])
                                                           (rf/dispatch [:projects/fetch {}]))
                                             :on-error #(rf/dispatch [:projects/fetch {}])}))]
                                (if (:id editing)
                                  (api/update-project (:id editing) payload
                                                      (fn [result]
                                                        (if (ok? result)
                                                          (done (:id editing))
                                                          (antd/error! (or (:msg result) "保存失败"))))
                                                      #(antd/error! "保存失败"))
                                  (api/create-project payload
                                                      (fn [result]
                                                        (if (ok? result)
                                                          (done (result-id result))
                                                          (antd/error! (or (:msg result) "保存失败"))))
                                                      #(antd/error! "保存失败")))))
                     :on-cancel #(rf/dispatch [:projects/close-modal])}]
     [team-modal {:open? team-visible?
                  :project team-project
                  :on-ok (fn [form]
                           (when (and team-project (:id team-project))
                             (api/create-subcontract-team (:id team-project) form
                                                          (fn [result]
                                                            (if (ok? result)
                                                              (do (antd/success! "保存成功")
                                                                  (rf/dispatch [:projects/close-team])
                                                                  (rf/dispatch [:projects/fetch {}]))
                                                              (antd/error! (or (:msg result) "保存失败"))))
                                                          #(antd/error! "保存失败"))))
                  :on-cancel #(rf/dispatch [:projects/close-team])}]
     [detail-drawer]]))
