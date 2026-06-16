(ns com.ruoyi.frontend.pages.project
  "项目信息管理页 - 对齐系统标准页。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined EditOutlined DeleteOutlined ReloadOutlined SearchOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.pages.business :as business :refer [project-modal team-modal]]))

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

(defn- project-card
  [project]
  [:div.biz-project-card
   [:div.biz-project-card-body
    [:div.biz-project-card-title
     [:div.biz-project-icon "▦"]
     [:div.biz-project-name (or (:project_name project) "未命名项目")]]
    [:div.biz-project-meta
     [:div "▤  方案数量： " [:span {:style {:color "#2f86f6" :fontWeight 800}} (or (:solution_count project) 0)] " 个"]
     [:div "◆  业态： " (or (:engineering_industry project) "-")]
     [:div "▣  创建时间： " (or (:create_time project) "-")]
     [:div "⚙  工程性质： " (or (:engineering_nature project) "-")]]]
   [:div.biz-project-footer
    [antd/button {:className "biz-soft-btn"
                  :icon (r/as-element [:> EditOutlined])
                  :on-click #(rf/dispatch [:projects/open-edit (:id project)])}
     "编辑"]
    [antd/popconfirm {:title "确认删除该项目？"
                      :okText "删除"
                      :cancelText "取消"
                      :on-confirm #(rf/dispatch [:projects/delete (:id project)])}
     [antd/button {:className "biz-danger-btn"
                   :icon (r/as-element [:> DeleteOutlined])}
      "删除"]]]])

(defn project-page []
  (business/use-business-shell!)
  (hooks/use-effect
   (fn []
     (rf/dispatch [:projects/fetch {}])
     js/undefined)
   [])
  (let [items @(rf/subscribe [:projects/items])
        total @(rf/subscribe [:projects/total])
        loading? @(rf/subscribe [:projects/loading?])
        query-params @(rf/subscribe [:projects/query-params])
        modal-visible? @(rf/subscribe [:projects/modal-visible?])
        editing @(rf/subscribe [:projects/editing])
        page @(rf/subscribe [:projects/page])
        page-size @(rf/subscribe [:projects/page-size])
        team-visible? @(rf/subscribe [:projects/team-visible?])
        team-project @(rf/subscribe [:projects/team-project])]
    [business/business-page-shell :project
     [:div.biz-title-row
      [:h1.biz-title "项目信息管理"]
      [business/business-primary-button {:icon (r/as-element [:> PlusOutlined])
                                         :on-click #(rf/dispatch [:projects/open-add])}
       "新增项目"]]
     [:div.biz-panel.biz-search-panel
      [:div {:class "biz-search-grid"
             :style {:gridTemplateColumns "repeat(4, minmax(0, 1fr))"}}
       [business/business-field "项目名称"
        [antd/input {:placeholder "请输入项目名称"
                     :value (:project_name query-params)
                     :on-change #(rf/dispatch [:projects/update-query :project_name (.. % -target -value)])}]]
       [business/business-field "创建时间"
        [antd/input {:placeholder "请选择日期"}]]
       [business/business-field "工程业态"
        [antd/select {:placeholder "全部" :style {:width "100%"} :allowClear true
                      :value (:engineering_industry query-params)
                      :on-change #(rf/dispatch [:projects/update-query :engineering_industry %])}
         [antd/select-option {:value "房建"} "房建"]
         [antd/select-option {:value "基础设施"} "基础设施"]
         [antd/select-option {:value "市政公用"} "市政公用"]]]
       [business/business-field "工程性质"
        [antd/select {:placeholder "全部" :style {:width "100%"} :allowClear true
                      :value (:engineering_nature query-params)
                      :on-change #(rf/dispatch [:projects/update-query :engineering_nature %])}
         [antd/select-option {:value "医院/医疗卫生"} "医院/医疗卫生"]
         [antd/select-option {:value "商业商场"} "商业商场"]
         [antd/select-option {:value "公共建筑"} "公共建筑"]]]]
      [:div.biz-search-actions
       [business/business-soft-button {:icon (r/as-element [:> ReloadOutlined])
                                       :on-click #(rf/dispatch [:projects/reset-query])}
        "重置"]
       [business/business-primary-button {:icon (r/as-element [:> SearchOutlined])
                                          :on-click #(rf/dispatch [:projects/search])}
        "查询"]]]
     (if loading?
       [antd/spin {:style {:width "100%"}}]
       [:div.biz-project-grid
        (for [project items]
          ^{:key (:id project)}
          [project-card project])])
     [:div.biz-panel {:style {:marginTop 28 :padding "18px 24px" :display "flex" :justifyContent "flex-end"}}
      [antd/pagination {:total total
                        :pageSize page-size
                        :current page
                        :showSizeChanger true
                        :showTotal (fn [total] (str "共 " total " 条"))
                        :onChange (fn [page page-size]
                                    (rf/dispatch [:projects/change-page page page-size]))}]]
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
