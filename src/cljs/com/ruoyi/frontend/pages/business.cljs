(ns com.ruoyi.frontend.pages.business
  "工程方案与资源管理页面。"
  (:require
   ["@ant-design/icons" :refer [FileTextOutlined PictureOutlined]]
   [clojure.string :as str]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]
   [com.ruoyi.frontend.components.pagination :refer [pagination]]
   [com.ruoyi.frontend.components.status-tag :refer [status-tag]]
   [com.ruoyi.frontend.components.action-menu :refer [action-menu]]
   [com.ruoyi.frontend.components.form-field :refer [form-field]]
   [com.ruoyi.frontend.components.form-section :refer [form-section] :rename {form-section generic-form-section}]
   [reagent.core :as r]
   [reagent.hooks :as hooks]))

(declare chat-panel progress-panel solution-export-panel)

(defn- ok?
  [result]
  (= 200 (:code result)))

(defn- rows
  [result]
  (get-in result [:data :rows] []))

(defn- total
  [result]
  (get-in result [:data :total] 0))

(defn- value
  [m k]
  (or (get m k) ""))

(defn- target-value
  [event]
  (.. event -target -value))

(defn- result-id
  [result]
  (get-in result [:data :id]))

(defn- file-name
  [file]
  (or (.-name file) (:original_name file) (:file_name file) (:name file) "未命名文件"))

(defn- handle-result!
  ([result success-msg success-fn]
   (handle-result! result success-msg success-fn "操作失败"))
  ([result success-msg success-fn fallback-msg]
   (if (ok? result)
     (do (when success-msg (antd/success! success-msg))
         (when success-fn (success-fn result)))
     (antd/error! (or (:msg result) fallback-msg)))))

(defn- page-card
  [title extra & children]
  [antd/card {:title title
              :extra (when extra (r/as-element extra))
              :style {:borderRadius 8}}
   (into [:<>] children)])

(defn- toolbar
  [children]
  [:div {:style {:display "flex" :gap 8 :alignItems "center" :marginBottom 16 :flexWrap "wrap"}} children])

(defn- input
  ([form set-form! k placeholder] (input form set-form! k placeholder false))
  ([form set-form! k placeholder readonly?]
   [antd/input {:value (value form k) :placeholder placeholder
                :disabled readonly?
                :style {:width "100%" :height 36}
                :on-change #(when-not readonly? (set-form! (assoc form k (target-value %))))}]))

(defn- textarea
  ([form set-form! k placeholder] (textarea form set-form! k placeholder false))
  ([form set-form! k placeholder readonly?]
   [antd/text-area {:value (value form k) :placeholder placeholder :rows 2
                    :disabled readonly?
                    :style {:width "100%"}
                    :on-change #(when-not readonly? (set-form! (assoc form k (target-value %))))}]))

(defn- select-status
  ([form set-form!] (select-status form set-form! false))
  ([form set-form! readonly?]
   [antd/select {:value (value form :status) :style {:width "100%"}
                 :disabled readonly?
                 :on-change #(when-not readonly? (set-form! (assoc form :status %)))}
    [antd/select-option {:value "0"} "正常"]
    [antd/select-option {:value "1"} "停用"]]))

(defn- select-input
  ([form set-form! k placeholder options] (select-input form set-form! k placeholder options false))
  ([form set-form! k placeholder options readonly?]
   [antd/select {:value (value form k)
                 :placeholder placeholder
                 :disabled readonly?
                 :style {:width "100%" :height 36}
                 :on-change #(when-not readonly? (set-form! (assoc form k %)))}
    (for [option options]
      ^{:key option} [antd/select-option {:value option} option])]))

(defn- unit-input
  ([form set-form! k placeholder unit] (unit-input form set-form! k placeholder unit false))
  ([form set-form! k placeholder unit readonly?]
   [:div {:style {:display "flex" :width "100%"}}
    [antd/input {:value (value form k)
                 :placeholder placeholder
                 :disabled readonly?
                 :style {:height 36
                         :flex 1
                         :minWidth 0
                         :borderRadius "4px 0 0 4px"}
                 :on-change #(when-not readonly? (set-form! (assoc form k (target-value %))))}]
    [:span {:style {:height 36
                    :minWidth 64
                    :padding "0 11px"
                    :display "inline-flex"
                    :flex "0 0 auto"
                    :alignItems "center"
                    :justifyContent "center"
                    :whiteSpace "nowrap"
                    :border "1px solid var(--ant-color-border, #d9d9d9)"
                    :borderLeft 0
                    :borderRadius "0 4px 4px 0"
                    :background "var(--ant-color-fill-quaternary, #fafafa)"
                    :color "var(--ant-color-text-secondary, #595959)"}}
     unit]]))

(defn- form-grid
  [& items]
  (into [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "10px 16px" :alignItems "start"}}] items))

(defn- form-item
  [label child]
  [:div {:style {:minWidth 0}}
   [:div {:style {:marginBottom 4 :fontWeight 500 :fontSize 13 :lineHeight "20px"}} label]
   child])


(defn- full-row
  [child]
  [:div {:style {:gridColumn "1 / -1"}} child])

(defn- upload-placeholder
  [text]
  [:div {:style {:height 86 :width "100%" :boxSizing "border-box"
                 :border "1px dashed var(--ant-color-primary-border, #91caff)"
                 :background "var(--ant-color-primary-bg, #f5fbff)"
                 :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                 :color "var(--ant-color-text-secondary, #8c8c8c)" :gap 6 :padding 12 :fontSize 13 :textAlign "center"}}
   [antd/upload-icon {:style {:fontSize 24 :color "var(--ant-color-primary, #1677ff)"}}]
   [:div text]])

(defn pending-upload-box
  [{:keys [files set-files! text readonly?]}]
  (let [content [:div {:style {:minHeight 96 :width "100%" :boxSizing "border-box"
                               :border "1px dashed var(--ant-color-primary-border, #91caff)"
                               :background "var(--ant-color-primary-bg, #f5fbff)"
                               :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                               :color "var(--ant-color-text-secondary, #8c8c8c)" :gap 8 :padding 12 :fontSize 13 :textAlign "center"
                               :cursor (if readonly? "default" "pointer")}}
                 [antd/upload-icon {:style {:fontSize 24 :color "var(--ant-color-primary, #1677ff)"}}]
                 [:div (if readonly? "附件" text)]
                 (if (seq files)
                   [:div {:style {:width "100%" :display "grid" :gap 4 :marginTop 4}}
                    (for [[idx file] (map-indexed vector files)]
                      ^{:key idx}
                      [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                                     :gap 8 :padding "4px 8px" :borderRadius 4
                                     :background "var(--ant-color-bg-container, #fff)"
                                     :border "1px solid var(--ant-color-primary-border, #d6e4ff)"
                                     :color "var(--ant-color-text, #1f2937)"}}
                       [:span {:style {:overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}} (file-name file)]
                       (when-not readonly?
                         [:button {:type "button"
                                   :style {:border 0 :background "transparent" :color "var(--ant-color-error, #ff4d4f)" :cursor "pointer"}
                                   :on-click (fn [e]
                                               (.stopPropagation e)
                                               (set-files! (vec (concat (subvec (vec files) 0 idx)
                                                                        (subvec (vec files) (inc idx))))))}
                          "移除"])])]
                   [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)"}} "暂无附件"])]]
    (if readonly?
      content
      [antd/upload {:showUploadList false
                    :multiple true
                    :beforeUpload (fn [file]
                                    (set-files! (conj (vec files) file))
                                    false)}
       content])))

(defn- attachment-list
  [{:keys [attachments readonly? on-delete]}]
  (let [attachments (vec (or attachments []))]
    [:div {:style {:display "grid" :gap 6 :marginTop 8}}
     (if (seq attachments)
       (for [attachment attachments]
         ^{:key (:id attachment)}
         [:div {:style {:display "flex"
                        :alignItems "center"
                        :justifyContent "space-between"
                        :gap 8
                        :padding "6px 10px"
                        :border "1px solid var(--ant-color-primary-border, #d6e4ff)"
                        :borderRadius 4
                        :background "var(--ant-color-bg-container, #fff)"}}
          [:span {:style {:minWidth 0 :overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}}
           (file-name attachment)]
          (when (and (not readonly?) on-delete)
            [antd/popconfirm {:title "确认删除该附件？" :okText "删除" :cancelText "取消"
                              :on-confirm #(on-delete attachment)}
             [antd/button {:type "link" :size "small" :danger true} "删除"]])])
       [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :fontSize 13}} "暂无已上传附件"])]))

(defn- upload-files!
  [{:keys [biz-type biz-id section-key file-purpose files on-uploaded on-done on-error]}]
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
             (when (and (ok? result) on-uploaded)
               (on-uploaded result))
             (when (zero? (swap! remaining dec))
               (if @failed?
                 (when on-error (on-error))
                 (when on-done (on-done)))))
           (fn [_]
             (reset! failed? true)
             (antd/error! "附件上传失败")
             (when (zero? (swap! remaining dec))
               (when on-error (on-error))))))))))

(defn- status-label
  [status]
  (case status
    "1" "停用"
    "inactive" "停用"
    "done" "已完成"
    "draft" "草稿"
    "正常" "正常"
    "0" "正常"
    (or status "正常")))

(defn- modal-size
  [width]
  {:className "biz-modal"
   :style {:top 24 :width width :maxWidth "calc(100vw - 32px)"}
   :styles {:body {:maxHeight "calc(100vh - 170px)" :overflowY "auto" :padding "14px 24px 18px"}
            :content {:overflow "hidden"}}})

(defn use-business-shell! []
  (hooks/use-effect
   (fn []
     (.add (.-classList js/document.body) "biz-shell-active")
     (fn []
       (.remove (.-classList js/document.body) "biz-shell-active")))
   []))

(defn business-shell-styles []
  [:style
   "
body.biz-shell-active .ant-layout-sider,
body.biz-shell-active .ant-layout-header,
body.biz-shell-active .app-tab-bar,
body.biz-shell-active .app-layout-footer,
body.biz-shell-active .app-layout-float {
  display: none !important;
}

body.biz-shell-active .ant-layout,
body.biz-shell-active .ant-layout-content {
  background: #f4f7fb !important;
}

.biz-page {
  min-height: 100vh;
  background: #f4f7fb;
  color: #1f2937;
  font-family: \"Helvetica Neue\", Helvetica, \"PingFang SC\", \"Microsoft YaHei\", Arial, sans-serif;
}

.biz-top-nav {
  height: 72px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 64px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}

.biz-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  color: #1e5fb8;
  font-size: 20px;
  font-weight: 800;
  white-space: nowrap;
}

.biz-logo {
  width: 42px;
  height: 42px;
  background: #2281c7;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 900;
  letter-spacing: 0;
  font-size: 15px;
}

.biz-nav {
  display: flex;
  align-items: center;
  gap: 22px;
}

.biz-nav a {
  height: 38px;
  padding: 0 18px;
  border-radius: 5px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #5d6675;
  font-weight: 700;
  text-decoration: none;
  font-size: 16px;
}

.biz-nav a.active {
  background: #e8f2ff;
  color: #2f86f6;
}

.biz-user {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #5d6675;
  font-size: 16px;
}

.biz-user-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #e8f2ff;
  color: #2f86f6;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
}

.biz-content {
  max-width: 1460px;
  margin: 0 auto;
  padding: 34px 32px 56px;
}

.biz-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28px;
}

.biz-title {
  margin: 0;
  font-size: 28px;
  line-height: 38px;
  font-weight: 800;
  color: #1f2937;
}

.biz-panel {
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.06);
  border: 1px solid #edf1f6;
}

.biz-search-panel {
  padding: 28px 28px 24px;
  margin-bottom: 24px;
}

.biz-search-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 22px;
}

.biz-search-actions {
  display: flex;
  justify-content: flex-end;
  gap: 14px;
  padding-top: 22px;
  margin-top: 22px;
  border-top: 1px solid #edf1f6;
}

.biz-field-label {
  font-size: 15px;
  line-height: 22px;
  margin-bottom: 10px;
  color: #566070;
  font-weight: 700;
}

.biz-page .ant-input,
.biz-page .ant-select-selector {
  border-color: #d9e0ea !important;
  border-radius: 4px !important;
  box-shadow: none !important;
}

.biz-page .ant-input,
.biz-page .ant-select-single {
  height: 40px;
}

.biz-primary-btn.ant-btn {
  height: 42px;
  min-width: 108px;
  border-radius: 5px;
  background: #2f86f6;
  border-color: #2f86f6;
  font-weight: 700;
  box-shadow: 0 6px 12px rgba(47, 134, 246, 0.24);
}

.biz-soft-btn.ant-btn {
  height: 42px;
  min-width: 96px;
  border-radius: 5px;
  color: #5d6675;
  border-color: #d9e0ea;
  font-weight: 700;
}

.biz-table-panel {
  overflow: hidden;
  margin-top: 24px;
}

.biz-page .ant-table-thead > tr > th {
  background: #fbfcfe !important;
  color: #303846 !important;
  font-weight: 800 !important;
  border-bottom: 1px solid #edf1f6 !important;
  padding: 18px 24px !important;
}

.biz-page .ant-table-tbody > tr > td {
  padding: 20px 24px !important;
  border-bottom: 1px solid #edf1f6 !important;
  color: #2f3745;
}

.biz-modal .ant-modal-content {
  border-radius: 4px;
  overflow: hidden;
}

.biz-modal .ant-modal-header {
  margin: 0;
  padding: 18px 24px;
  border-bottom: 1px solid #edf1f6;
}

.biz-modal .ant-modal-title {
  font-weight: 800;
  color: #1f2937;
}

.biz-modal .ant-modal-footer {
  padding: 14px 24px;
  border-top: 1px solid #edf1f6;
}

.biz-form-section {
  background: #fff;
  border-top: 10px solid #f5f8fc;
  padding: 24px 18px 18px;
}

.biz-form-section-title {
  border-left: 3px solid #2f86f6;
  padding-left: 10px;
  font-size: 15px;
  font-weight: 800;
  color: #1f2937;
  margin-bottom: 18px;
}

.biz-project-editor-modal.ant-modal {
  width: min(1480px, calc(100vw - 40px)) !important;
  max-width: none;
  top: 32px !important;
  margin: 0 auto;
  padding-bottom: 24px;
}

.biz-project-editor-modal .ant-modal-content {
  min-height: calc(100vh - 88px);
  border-radius: 6px;
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.16);
}

.biz-project-editor-modal .ant-modal-header {
  height: 62px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #edf1f6;
}

.biz-project-editor-modal .ant-modal-title {
  width: 100%;
}

.biz-project-editor-modal .ant-modal-body {
  background: #f5f8fc;
}

.biz-editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.biz-editor-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  color: #1f2937;
  font-weight: 800;
}

.biz-editor-back {
  border: 0;
  background: transparent;
  color: #566070;
  font-size: 20px;
  cursor: pointer;
  padding: 4px 6px;
}

.biz-project-form-page {
  display: grid;
  gap: 18px;
  max-width: 1420px;
  margin: 0 auto;
}

.biz-inline-warning {
  height: 32px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  background: #fff7e6;
  border: 1px solid #ffd591;
  color: #d46b08;
  font-size: 13px;
}

.biz-resource-layout {
  min-height: calc(100vh - 72px);
  display: grid;
  grid-template-columns: 190px 210px 1fr;
  background: #fff;
}

.biz-resource-primary-side {
  border-right: 1px solid #edf1f6;
  background: #fff;
}

.biz-resource-secondary-side {
  border-right: 1px solid #edf1f6;
  background: #fff;
}

.biz-resource-menu-item {
  height: 56px;
  padding: 0 22px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #3f4652;
  font-weight: 700;
  text-decoration: none;
  border-left: 4px solid transparent;
}

.biz-resource-menu-item.active {
  background: #e7f6ff;
  border-left-color: #1890ff;
  color: #1890ff;
}

.biz-resource-type-title {
  height: 58px;
  display: flex;
  align-items: center;
  padding: 0 18px;
  font-size: 18px;
  font-weight: 800;
  color: #303846;
}

.biz-resource-type-search {
  padding: 0 14px 12px;
}

.biz-resource-category {
  height: 42px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  color: #4b5563;
  font-weight: 500;
}

.biz-resource-category.active {
  background: #dff5ff;
  color: #1890ff;
  font-weight: 800;
}

.biz-resource-main {
  min-width: 0;
  padding: 0 0 24px;
}

.biz-resource-title {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid #edf1f6;
  font-size: 20px;
  font-weight: 800;
}

.biz-resource-body {
  padding: 20px 18px;
}

.biz-project-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(260px, 1fr));
  gap: 24px;
  margin-top: 24px;
}

.biz-project-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #edf1f6;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.biz-project-card-body {
  padding: 24px 26px 22px;
}

.biz-project-card-title {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  min-height: 74px;
}

.biz-project-icon {
  flex: 0 0 50px;
  width: 50px;
  height: 50px;
  border-radius: 8px;
  background: #e8f2ff;
  color: #2f86f6;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 900;
}

.biz-project-name {
  color: #1f2937;
  font-size: 18px;
  line-height: 28px;
  font-weight: 800;
}

.biz-project-meta {
  display: grid;
  gap: 14px;
  margin-top: 20px;
  color: #5d6675;
  font-size: 15px;
  line-height: 22px;
}

.biz-project-footer {
  height: 66px;
  border-top: 1px solid #edf1f6;
  background: #fbfcfe;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  padding: 0 24px;
}

.biz-danger-btn.ant-btn {
  height: 36px;
  border-radius: 7px;
  color: #ff4d4f;
  background: #fff1f1;
  border-color: #fff1f1;
  font-weight: 700;
}

.biz-resource-drawer-modal.ant-modal {
  width: 50vw !important;
  max-width: 50vw;
  top: 0 !important;
  margin: 0 0 0 auto;
  padding-bottom: 0;
}

.biz-resource-drawer-modal .ant-modal-content {
  min-height: 100vh;
  border-radius: 0;
}

.biz-resource-drawer-modal .ant-modal-body {
  min-height: calc(100vh - 116px);
}

.biz-resource-drawer-modal .ant-upload {
  display: block;
  width: 100%;
}

.biz-resource-upload-wide {
  min-height: 174px;
  background: #f6f8fb;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  margin: -14px -24px 22px;
}

.biz-atlas-upload {
  height: 174px;
  width: 100%;
  background: #f6f8fb;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #9aa3af;
  gap: 10px;
  margin-bottom: 22px;
  cursor: pointer;
}

.biz-atlas-upload-icon {
  color: #0091ff;
  font-size: 40px;
  line-height: 1;
}

.biz-atlas-detail-table {
  width: 100%;
  border-collapse: collapse;
  color: #3f4652;
}

.biz-atlas-detail-table th {
  height: 42px;
  text-align: left;
  border-bottom: 1px solid #edf1f6;
  font-weight: 800;
  color: #566070;
}

.biz-atlas-detail-table td {
  height: 48px;
  border-bottom: 1px solid #edf1f6;
}

.biz-atlas-empty {
  height: 360px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8c8c8c;
}

@media (max-width: 1100px) {
  .biz-top-nav { padding: 0 24px; }
  .biz-search-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .biz-resource-layout { grid-template-columns: 1fr; }
  .biz-resource-primary-side,
  .biz-resource-secondary-side { display: none; }
  .biz-project-grid { grid-template-columns: 1fr; }
}
" ])

(defn business-top-nav
  [active]
  [:div.biz-top-nav
   [:div.biz-brand
    [:div.biz-logo "CS"]
    [:span "领睿·方案智能编制系统"]]
   [:nav.biz-nav
    [:a {:href "/solution" :class (when (= active :solution) "active")} "⌂" "首页"]
    [:a {:href "/project/info" :class (when (= active :project) "active")} "▣" "项目信息管理"]
    [:a {:href "/resource/standard" :class (when (= active :resource) "active")} "◈" "资源管理"]]
   [:div.biz-user
    [:div.biz-user-avatar "●"]
    [:span "开发者1"]
    [:span "⌄"]]])

(defn business-page-shell
  [active & children]
  [:div.biz-page
   [business-shell-styles]
   [business-top-nav active]
   (into [:div.biz-content] children)])

(defn business-field
  [label child]
  [:div
   [:div.biz-field-label label]
   child])

(defn business-primary-button
  [props & children]
  (into [antd/button (merge {:type "primary" :className "biz-primary-btn"} props)] children))

(defn business-soft-button
  [props & children]
  (into [antd/button (merge {:className "biz-soft-btn"} props)] children))

(defn business-form-section
  [title & children]
  (into [:div.biz-form-section
         [:div.biz-form-section-title title]]
        children))

(def project-extra-keys
  [:engineering_industry :engineering_nature :province :construction_scale :contract_scope
   :total_land_area :total_building_area :general_contractor_unit :main_subproject
   :quality_requirement :safety_requirement :technology_requirement :main_function
   :management_staff :subcontract_teams])

(def project-select-options
  {:engineering_industry ["房建" "基础设施" "市政公用" "装饰装修"]
   :engineering_nature ["医院/医疗卫生" "商业商场" "住宅建筑" "公共建筑"]
   :province ["山东省" "北京市" "上海市" "江苏省" "广东省" "浙江省" "四川省"]
   :title ["工程师" "高级工程师" "一级建造师" "安全员" "资料员" "助理工程师"]})

(def management-roles
  [{:role_key "project_manager" :role_name "项目经理"}
   {:role_key "chief_engineer" :role_name "项目总工"}
   {:role_key "professional_engineer" :role_name "专业工程师"}
   {:role_key "commercial_manager" :role_name "商务经理"}
   {:role_key "responsible_engineer" :role_name "责任工程师"}
   {:role_key "quality_director" :role_name "质量总监"}
   {:role_key "safety_director" :role_name "安全总监"}
   {:role_key "safety_engineer" :role_name "安全工程师"}
   {:role_key "technical_engineer" :role_name "技术工程师"}
   {:role_key "material_engineer" :role_name "物资工程师"}
   {:role_key "mechanical_admin" :role_name "机械管理员"}
   {:role_key "document_controller" :role_name "资料员"}
   {:role_key "survey_engineer" :role_name "测量工程师"}
   {:role_key "commercial_engineer" :role_name "商务工程师"}
   {:role_key "test_engineer" :role_name "试验工程师"}])

(defn- default-management-staff
  []
  (mapv (fn [{:keys [role_key role_name]}]
          {:role_key role_key :role_name role_name :person_name "" :title ""})
        management-roles))

(defn- default-project-form
  []
  {:status "0" :management_staff (default-management-staff) :subcontract_teams []})

(defn- parse-extra-json
  [s]
  (try
    (if (seq s) (js->clj (.parse js/JSON s) :keywordize-keys true) {})
    (catch :default _ {})))

(defn- hydrate-project
  [project]
  (let [hydrated (merge (parse-extra-json (:extra_json project)) project)]
    (-> hydrated
        (update :management_staff #(if (seq %) % (default-management-staff)))
        (update :subcontract_teams #(if (vector? %) % [])))))

(defn- project-payload
  [form]
  (let [extra (select-keys form project-extra-keys)
        base (-> form
                 (as-> payload (reduce dissoc payload project-extra-keys))
                 (assoc :extra_json (.stringify js/JSON (clj->js extra))))]
    ;; 后端 schema 对可选字段不接受 nil，提交前过滤掉未填写的 nil 值
    (into {} (remove #(nil? (second %)) base))))

(def project-overview-fields
  [{:key :project_name :label "项目名称" :type :full-input :rules [{:required true :message "请输入项目名称"}]}
   {:key :engineering_industry :label "工程业态" :type :select :options (:engineering_industry project-select-options)}
   {:key :engineering_nature :label "工程性质" :type :select :options (:engineering_nature project-select-options)}
   {:key :project_address :label "工程地址" :type :input}
   {:key :construction_scale :label "建设规模" :type :input}
   {:key :province :label "所属省份" :type :select :options (:province project-select-options)}
   {:key :contract_scope :label "承包范围" :type :input}
   {:key :total_land_area :label "总占地面积" :type :unit :unit "万㎡"}
   {:key :total_building_area :label "总建筑面积" :type :unit :unit "万㎡"}
   {:key :construction_unit :label "建设单位" :type :input}
   {:key :survey_unit :label "勘察单位" :type :input}
   {:key :design_unit :label "设计单位" :type :input}
   {:key :supervision_unit :label "监理单位" :type :input}
   {:key :general_contractor_unit :label "总承包单位" :type :input}
   {:key :main_subproject :label "主要分包工程" :type :input}
   {:key :contract_period :label "工期" :type :unit :unit "天"}
   {:key :quality_requirement :label "质量" :type :input}
   {:key :safety_requirement :label "安全" :type :input}
   {:key :technology_requirement :label "科技" :type :input}
   {:key :start_date :label "开工时间" :type :input}
   {:key :end_date :label "竣工时间" :type :input}
   {:key :main_function :label "工程主要功能或用途" :type :textarea :full? true}])

(def project-fields
  [[:project_name "项目名称" :full-input]
   [:engineering_industry "工程业态" :select]
   [:engineering_nature "工程性质" :select]
   [:project_address "工程地址" :input]
   [:construction_scale "建设规模" :input]
   [:province "所属省份" :select]
   [:contract_scope "承包范围" :input]
   [:total_land_area "总占地面积" :area]
   [:total_building_area "总建筑面积" :area]
   [:construction_unit "建设单位" :input]
   [:survey_unit "勘察单位" :input]
   [:design_unit "设计单位" :input]
   [:supervision_unit "监理单位" :input]
   [:general_contractor_unit "总承包单位" :input]
   [:main_subproject "主要分包工程" :input]
   [:contract_period "工期" :days]
   [:quality_requirement "质量" :input]
   [:safety_requirement "安全" :input]
   [:technology_requirement "科技" :input]
   [:start_date "开工时间" :input]
   [:end_date "竣工时间" :input]
   [:main_function "工程主要功能或用途" :textarea]])

(defn- empty-subcontract-team
  []
  {:team_name "" :manager_name "" :technical_leader_name "" :safety_leader_name ""})

(defn- management-staff-table
  [{:keys [value readonly? on-change]}]
  (let [staff (or value (default-management-staff))]
    [:div {:style {:marginTop 14}}
     [:div {:style {:borderLeft "3px solid var(--ant-color-primary, #1677ff)"
                    :paddingLeft 10 :marginBottom 10 :fontWeight 600
                    :color "var(--ant-color-text, #1f2937)"}}
      "人员组织"]
     [:div {:style {:marginBottom 8 :fontWeight 600}} "总承包项目管理人员及职责分工"]
     [:div {:style {:border "1px solid var(--ant-color-border-secondary, #e5e7eb)" :borderRadius 4 :overflow "hidden"}}
      [:div {:style {:display "grid" :gridTemplateColumns "70px 1.2fr 1.4fr 1.4fr"
                     :background "var(--ant-color-fill-quaternary, #f8fafc)"
                     :fontWeight 600 :color "var(--ant-color-text, #374151)"}}
       (for [title ["序号" "岗位名称" "姓名" "职称（资质）"]]
         ^{:key title} [:div {:style {:padding "9px 12px" :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)"}} title])]
      (for [[idx row] (map-indexed vector staff)]
        ^{:key (:role_key row)}
        [:div {:style {:display "grid" :gridTemplateColumns "70px 1.2fr 1.4fr 1.4fr"
                       :borderTop "1px solid var(--ant-color-border-secondary, #e5e7eb)" :alignItems "center"}}
         [:div {:style {:padding "8px 12px" :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)" :textAlign "center"}} (inc idx)]
         [:div {:style {:padding "8px 12px" :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)"}} (:role_name row)]
         [:div {:style {:padding 8 :borderRight "1px solid var(--ant-color-border-secondary, #e5e7eb)"}}
          [antd/input {:value (or (:person_name row) "")
                       :placeholder "姓名"
                       :disabled readonly?
                       :style {:height 34}
                       :on-change #(when-not readonly?
                                     (on-change (assoc-in staff [idx :person_name] (target-value %))))}]]
         [:div {:style {:padding 8}}
          [antd/select {:value (or (:title row) "")
                        :placeholder "职称（资质）"
                        :disabled readonly?
                        :style {:width "100%" :height 34}
                        :on-change #(when-not readonly?
                                      (on-change (assoc-in staff [idx :title] %)))}
           (for [title (:title project-select-options)]
             ^{:key title} [antd/select-option {:value title} title])]]])]]))

(defn- management-staff-form-item [form readonly?]
  (let [form-staff (or (js->clj (.getFieldValue form "management_staff") :keywordize-keys true)
                       (default-management-staff))
        form-staff-key (.stringify js/JSON (clj->js form-staff))
        [staff set-staff!] (hooks/use-state form-staff)
        update-staff! (fn [next-staff]
                        (set-staff! next-staff)
                        (.setFieldsValue form #js {:management_staff (clj->js next-staff)}))]
    (hooks/use-effect
     (fn []
       (set-staff! form-staff)
       js/undefined)
     [form-staff-key])
    [antd/form-item {:name :management_staff :noStyle true}
     [management-staff-table
      {:value staff
       :readonly? readonly?
       :on-change update-staff!}]]))

(defn- subcontract-team-card
  [{:keys [teams idx readonly? on-change]}]
  (let [team (get teams idx)
        roles [{:label "项目经理" :field :manager_name}
               {:label "项目技术负责人" :field :technical_leader_name}
               {:label "项目安全负责人" :field :safety_leader_name}]
        cell-style {:padding "14px 16px" :borderBottom "1px solid var(--ant-color-border-secondary, #f1f3f7)"}]
    [:div {:style {:border "1px solid var(--ant-color-border-secondary, #eef0f4)"
                   :borderRadius 4 :overflow "hidden"
                   :background "var(--ant-color-bg-container, #fff)"}}
     [:div {:style {:display "grid" :gridTemplateColumns "30% 13% 57%"
                    :background "var(--ant-color-fill-quaternary, #fafafa)"
                    :fontWeight 700 :color "var(--ant-color-text, #3f4652)"}}
      [:div {:style {:padding "12px 16px"}} "分包队伍"]
      [:div {:style {:padding "12px 16px"}} "管理职务"]
      [:div {:style {:padding "12px 16px"}} "姓名"]]
     [:div {:style {:display "grid" :gridTemplateColumns "30% 70%" :minHeight 220}}
      [:div {:style {:padding "18px 16px" :borderRight "1px solid var(--ant-color-border-secondary, #eef0f4)"}}
       [antd/input {:value (value team :team_name)
                    :placeholder "请输入分包队伍名称"
                    :disabled readonly?
                    :style {:height 40 :width "100%"}
                    :on-change #(when-not readonly?
                                  (on-change (assoc-in teams [idx :team_name] (target-value %))))}]
       (when-not readonly?
         [antd/button {:type "link" :danger true :style {:padding 0 :marginTop 30}
                       :on-click #(on-change (vec (concat (subvec teams 0 idx)
                                                          (subvec teams (inc idx)))))}
          "删除本组"])]
      [:div
       (for [{:keys [label field]} roles]
         ^{:key label}
         [:div {:style {:display "grid" :gridTemplateColumns "18.6% 81.4%" :alignItems "center"}}
          [:div {:style (merge cell-style {:fontWeight 600})} label]
          [:div {:style cell-style}
           [antd/input {:value (value team field)
                        :placeholder "请输入姓名"
                        :disabled readonly?
                        :style {:height 40 :width "100%"}
                        :on-change #(when-not readonly?
                                      (on-change (assoc-in teams [idx field] (target-value %))))}]]])]]]))

(defn- subcontract-teams-list
  [{:keys [value readonly? on-change]}]
  (let [teams (vec (or value []))]
    [:div {:style {:marginTop 14}}
     [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 10}}
      [:div {:style {:borderLeft "3px solid var(--ant-color-primary, #1677ff)"
                     :paddingLeft 10 :fontWeight 600
                     :color "var(--ant-color-text, #1f2937)"}}
       "分包单位及岗位人员的安全职责表"]
      (when-not readonly?
        [antd/button {:type "primary"
                      :icon (r/as-element [antd/plus-icon])
                      :on-click #(on-change (conj teams (empty-subcontract-team)))}
         "新增分包队伍"])]
     (if (empty? teams)
       [:div {:style {:height 64 :border "1px dashed var(--ant-color-border, #d9d9d9)"
                      :borderRadius 4 :display "flex" :alignItems "center" :justifyContent "center"
                      :color "var(--ant-color-text-secondary, #8c8c8c)"}}
        "暂无分包队伍，请点击上方按钮添加"]
       [:div {:style {:display "grid" :gap 16}}
        (for [[idx team] (map-indexed vector teams)]
          ^{:key idx}
          [subcontract-team-card
           {:teams teams
            :idx idx
            :readonly? readonly?
            :on-change on-change}])])]))

(defn- subcontract-teams-form-item [form readonly?]
  (let [form-teams (or (js->clj (.getFieldValue form "subcontract_teams") :keywordize-keys true) [])
        form-teams-key (.stringify js/JSON (clj->js form-teams))
        [teams set-teams!] (hooks/use-state form-teams)
        update-teams! (fn [next-teams]
                        (set-teams! next-teams)
                        (.setFieldsValue form #js {:subcontract_teams (clj->js next-teams)}))]
    (hooks/use-effect
     (fn []
       (set-teams! form-teams)
       js/undefined)
     [form-teams-key])
    [antd/form-item {:name :subcontract_teams :noStyle true}
     [subcontract-teams-list
      {:value teams
       :readonly? readonly?
       :on-change update-teams!}]]))

(defn project-form
  ([form] (project-form form {} (fn [_]) false))
  ([form readonly?] (project-form form {} (fn [_]) readonly?))
  ([form upload-files set-upload-files! readonly?]
   [:div.biz-project-form-page
    [business-form-section "数据来源"
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap 12}}
      [pending-upload-box {:files (get upload-files :source_file [])
                           :set-files! #(set-upload-files! (assoc upload-files :source_file %))
                           :readonly? readonly?
                           :text "支持 docx、pdf、json、xlsx，文件大小不超过 100M"}]
      [pending-upload-box {:files (get upload-files :engineering_source [])
                           :set-files! #(set-upload-files! (assoc upload-files :engineering_source %))
                           :readonly? readonly?
                           :text "从项目综合管理系统导入项目基础信息"}]]]
    [:div.biz-inline-warning
     "可通过施工组织设计方案解析，或从综合管理系统导入项目基础数据；人员组织可通过文件解析生成。"]
    [business-form-section "项目概况"
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "16px 22px"}}
      (for [field project-overview-fields]
        ^{:key (name (:key field))}
        [form-field (assoc field :form form :name (:key field) :disabled readonly?)])]]
    [management-staff-form-item form readonly?]
    [subcontract-teams-form-item form readonly?]]))

(defn upload-box
  [{:keys [biz-type biz-id section-key file-purpose on-uploaded]}]
  [antd/upload {:showUploadList false
                :beforeUpload (fn [file]
                                (api/upload-business-attachment
                                 {:biz_type biz-type :biz_id biz-id :section_key section-key :file_purpose file-purpose}
                                 file
                                 (fn [result]
                                   (if (ok? result)
                                     (do (antd/success! "上传成功") (when on-uploaded (on-uploaded result)))
                                     (antd/error! (:msg result))))
                                 (fn [_] (antd/error! "上传失败")))
                                false)}
   [antd/button {:icon (r/as-element [antd/upload-icon])} "上传附件"]])

(defn project-modal
  [{:keys [open? editing readonly? on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)
        [upload-files set-upload-files!] (hooks/use-state {})
        [attachments set-attachments!] (hooks/use-state [])
        delete-attachment! (fn [attachment]
                             (api/delete-business-attachment (:id attachment)
                                                             (fn [result]
                                                               (handle-result! result "删除成功"
                                                                               (fn [_]
                                                                                 (set-attachments! (vec (remove #(= (:id %) (:id attachment)) attachments))))
                                                                               "删除失败"))
                                                             (fn [_] (antd/error! "删除失败"))))]
    (hooks/use-effect
     (fn []
       (when open?
         (set-upload-files! {})
         (.resetFields form)
         (if (:id editing)
           (do
             (set-attachments! (vec (or (:attachments editing) [])))
             (.setFieldsValue form (clj->js (hydrate-project editing)))
             (api/get-project (:id editing)
                              (fn [result]
                                (when (ok? result)
                                  (let [detail (:data result)]
                                    (set-attachments! (vec (or (:attachments detail) [])))
                                    (.setFieldsValue form (clj->js (hydrate-project detail))))))
                              (fn [_] nil)))
           (do
             (.setFieldsValue form (clj->js (default-project-form)))
             (set-attachments! []))))
       js/undefined)
     [open? editing])
    [antd/modal (merge (modal-size "100vw")
                       {:className "biz-modal biz-project-editor-modal"
                        :open open?
                        :mask false
                        :closable false
                        :footer nil
                        :title (r/as-element
                                [:div.biz-editor-header
                                 [:div.biz-editor-title
                                  [:button.biz-editor-back {:type "button" :on-click on-cancel} "←"]
                                  [:span (cond
                                           readonly? "查看项目"
                                           (:id editing) "编辑项目"
                                           :else "新建项目")]]
                                 (when-not readonly?
                                   [business-primary-button {:on-click #(.submit form)}
                                    "保存"])])
                        :destroyOnHidden true
                        :on-cancel on-cancel
                        :styles {:body {:height "calc(100vh - 150px)"
                                         :overflowY "auto"
                                         :padding "22px 24px 30px"}
                                 :content {:overflow "hidden"}}})
     [antd/form {:form form
                 :layout "vertical"
                 :disabled readonly?
                 :on-finish (fn [values]
                              (let [values (js->clj values :keywordize-keys true)]
                                (on-ok (project-payload values) upload-files)))}
      [project-form form upload-files set-upload-files! readonly?]
      [generic-form-section {:title "已上传附件" :columns 1}
       [full-row [attachment-list {:attachments attachments
                                   :readonly? readonly?
                                   :on-delete delete-attachment!}]]]]]))

(defn team-modal
  [{:keys [open? project on-ok on-cancel]}]
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when open?
         (.resetFields form)
         (.setFieldsValue form #js {}))
       js/undefined)
     [open?])
    [antd/modal (merge (modal-size 640)
                       {:open open? :title "新增分包队伍" :okText "保存" :cancelText "取消"
                        :on-ok #(.submit form)
                        :on-cancel on-cancel})
     [antd/form {:form form :layout "vertical"
                 :on-finish (fn [values]
                              (on-ok (js->clj values :keywordize-keys true)))}
      [form-field {:type :input :form form :name :team_name :label "分包队伍名称"
                   :rules [{:required true :message "请输入分包队伍名称"}]}]
      [form-field {:type :input :form form :name :leader_name :label "负责人"}]
      [form-field {:type :input :form form :name :contact_phone :label "联系电话"}]
      [form-field {:type :textarea :form form :name :work_scope :label "分包内容"}]
      [form-field {:type :textarea :form form :name :remark :label "备注"}]
      [:div {:style {:marginTop 8 :color "var(--ant-color-text-disabled, #999)"}}
       "所属项目：" (:project_name project)]]]))

(def section-fields
  {"project-info" project-fields
   "people-org" [[:org_name "组织名称" :input] [:position "岗位/职务" :input] [:person_name "姓名" :input] [:phone "联系方式" :input] [:responsibility "职责" :textarea] [:remark "备注" :textarea]]
   "cover" [[:title "方案名称" :input] [:compile_unit "编制单位" :input] [:compiler "编制人" :input] [:reviewer "审核人" :input] [:approver "审批人" :input] [:compile_date "编制日期" :input]]
   "design-overview" [[:engineering_overview "工程概况" :textarea] [:design_basis "设计依据" :textarea] [:design_scope "设计范围" :textarea] [:technical_params "主要技术参数" :textarea] [:design_description "设计说明" :textarea]]
   "layout-plan" [[:drawing_name "图纸名称" :input] [:drawing_desc "图纸说明" :textarea] [:sort_order "排序" :input]]})

(def section-labels
  [{:key "project-info" :label "项目信息"}
   {:key "people-org" :label "人员和组织"}
   {:key "cover" :label "封面"}
   {:key "design-overview" :label "设计概况"}
   {:key "layout-plan" :label "平面布置图"}])

(defn- parse-json
  [s]
  (try (if (seq s) (js->clj (.parse js/JSON s) :keywordize-keys true) {}) (catch :default _ {})))

(defn- stringify
  [m]
  (.stringify js/JSON (clj->js m)))

(defn- solution-payload
  [form]
  (assoc form :progress (or (js/parseInt (value form :progress) 10) 0)))

(defn solution-editor
  [{:keys [solution on-back]}]
  (let [[section set-section!] (hooks/use-state "project-info")
        [form set-form!] (hooks/use-state {})
        [loading? set-loading!] (hooks/use-state false)
        load-section! (fn [key]
                        (set-loading! true)
                        (api/get-solution-section (:id solution) key
                                                  (fn [result]
                                                    (set-loading! false)
                                                    (set-form! (parse-json (get-in result [:data :content_json]))))
                                                  (fn [_] (set-loading! false))))]
    (hooks/use-effect (fn [] (load-section! section) js/undefined) [section])
    [page-card (str "编辑方案 - " (:solution_name solution))
     [antd/space {:style {:display "flex" :gap 8 :alignItems "center" :flexWrap "wrap"}}
      [antd/button {:on-click on-back} "返回"]
      [antd/button {:type "primary" :on-click #(api/save-solution-section (:id solution) section {:content_json (stringify form)}
                                                                          (fn [result]
                                                                            (handle-result! result "保存成功" nil "保存失败"))
                                                                          (fn [_] (antd/error! "保存失败")))} "保存章节"]]
       [solution-export-panel {:solution-id (:id solution) :solution-name (:solution_name solution)}]
       [antd/button {:on-click #(set-section! "chat")} "AI问答"]
     [:div {:style {:display "grid" :gridTemplateColumns "220px 1fr" :gap 16}}
      [antd/card {:size "small"}
       (for [{:keys [key label]} section-labels]
         ^{:key key}
         [antd/button {:block true :type (if (= key section) "primary" "default") :style {:marginBottom 8}
                       :on-click #(set-section! key)} label])]
      [antd/card {:size "small" :loading loading?}
       [form-grid
        (for [[k label type] (get section-fields section)]
          ^{:key (name k)} [form-item label (if (= type :textarea) [textarea form set-form! k label] [input form set-form! k label])])]
       [:div {:style {:marginTop 16}}
        [upload-box {:biz-type "solution" :biz-id (:id solution) :section-key section :file-purpose (if (= section "cover") "cover" "attachment")}]]]]]))

(def solution-categories
  ["超危大工程（A类）" "危大工程（B类）" "一般工程（C/D类）"])

(def solution-plan-cards
  [{:key "ceiling" :title "顶面工程" :desc "顶棚专项方案" :icon "⌂" :available? false}
   {:key "ground" :title "地面工程" :desc "墙地砖粘贴方案" :icon "▦" :available? false}
   {:key "wall" :title "墙面工程" :desc "内墙专项方案" :icon "▣" :available? true}])

(def solution-level-options ["一般" "重点" "示范"])
(def solution-type-options ["墙面工程" "地面工程" "顶面工程"])

(defn- short-date
  [text]
  (if (and text (>= (count text) 10)) (subs text 0 10) (or text "-")))

(defn- solution-shell
  [& children]
  (into [business-page-shell :solution] children))

(defn- solution-filter-select
  [value placeholder options on-change]
  [antd/select {:value value :placeholder placeholder :style {:width "100%" :height 40} :on-change on-change}
   [antd/select-option {:value ""} "全部"]
   (for [option options]
     ^{:key option} [antd/select-option {:value option} option])])

(defn- solution-project-filter-select
  [value projects on-change]
  [antd/select {:value (or value "") :placeholder "全部" :style {:width "100%" :height 40} :on-change on-change}
   [antd/select-option {:value ""} "全部"]
   (for [project projects]
     ^{:key (:id project)} [antd/select-option {:value (:id project)} (:project_name project)])])

(defn- solution-columns
  [columns-config on-edit on-delete]
  (clj->js
   (filterv some?
            [(when (get-in columns-config [:solution_name :visible?])
               {:title "方案名称" :dataIndex "solution_name" :key "solution_name"
                :render (fn [v _]
                          (r/as-element
                           [:a {:style {:cursor "pointer" :color "var(--ant-color-primary, #1677ff)"}}
                            (or v "-")]))})
             (when (get-in columns-config [:solution_type :visible?])
               {:title "方案类型" :dataIndex "solution_type" :key "solution_type"
                :render (fn [v record]
                          (let [row (js->clj record :keywordize-keys true)]
                            (or v (:remark row) "墙面工程")))})
             (when (get-in columns-config [:project_name :visible?])
               {:title "所属项目" :dataIndex "project_name" :key "project_name"
                :render (fn [v _] (or v "-"))})
             (when (get-in columns-config [:solution_level :visible?])
               {:title "方案级别" :dataIndex "solution_level" :key "solution_level"
                :render (fn [v _]
                          (r/as-element
                           [antd/tag {:color (case v "重点" "red" "示范" "gold" "blue")}
                            (or v "一般")]))})
             (when (get-in columns-config [:create_time :visible?])
               {:title "生成时间" :dataIndex "create_time" :key "create_time"
                :render (fn [v _] (short-date v))})
             (when (get-in columns-config [:create_by :visible?])
               {:title "编制人" :dataIndex "create_by" :key "create_by"
                :render (fn [v _] (or v "开发者1"))})
             {:title "操作" :key "action" :width 150
              :render (fn [_ record]
                        (let [row (js->clj record :keywordize-keys true)]
                          (r/as-element
                           [action-menu
                            {:on-edit #(on-edit row)
                             :on-delete #(on-delete row)
                             :more-items []}])))}])))

(defn- solution-project-card
  [project selected? on-click]
  [:div {:on-click on-click
         :style {:display "flex" :gap 14 :alignItems "center" :padding "14px 16px"
                 :border (if selected? "1px solid var(--ant-color-primary, #3b82f6)" "1px solid var(--ant-color-border-secondary, #e5e7eb)")
                 :borderRadius 8
                 :background (if selected? "var(--ant-color-primary-bg, #eff6ff)" "var(--ant-color-bg-container, #fff)")
                 :cursor "pointer"}}
   [:div {:style {:width 46 :height 46 :borderRadius 8
                  :background "var(--ant-color-primary-bg, #e8f1ff)"
                  :display "flex" :alignItems "center" :justifyContent "center"
                  :color "var(--ant-color-primary, #3b82f6)" :fontSize 22}} "▦"]
   [:div {:style {:flex 1 :minWidth 0}}
    [:div {:style {:fontWeight 700 :fontSize 15 :whiteSpace "nowrap" :overflow "hidden" :textOverflow "ellipsis"
                   :color "var(--ant-color-text, #1f2937)"}}
     (or (:project_name project) "未命名项目")]
    [:div {:style {:marginTop 6 :display "flex" :gap 16 :color "var(--ant-color-text-secondary, #6b7280)" :fontSize 13}}
     [:span "▣ " (or (:solution_count project) 0) "个方案"]
     [:span "◆"]]]])

(defn- solution-supplement-card
  [title completed? body & [on-preview on-edit]]
  [:div {:style {:border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                 :borderRadius 8
                 :background "var(--ant-color-bg-container, #fff)"
                 :minHeight 230 :padding 18}}
   [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                  :borderBottom "1px solid var(--ant-color-border-secondary, #eef0f4)"
                  :paddingBottom 14}}
    [:div {:style {:fontWeight 700 :fontSize 16 :color "var(--ant-color-text, #1f2937)"}} title]
    [:div {:style {:display "flex" :gap 10}}
     [antd/button {:icon (r/as-element [antd/eye-icon]) :on-click #(when on-preview (on-preview))} "预览"]
     [antd/button {:icon (r/as-element [antd/edit-icon]) :on-click #(when on-edit (on-edit))} "编辑"]]]
   [:div {:style {:marginTop 16}}
    (if completed?
      [antd/tag {:color "success"} "已填写完成"]
      [antd/tag {:color "warning"} "尚未填写"])]
   [:div {:style {:marginTop 14 :color "var(--ant-color-text-secondary, #4b5563)" :lineHeight 1.7}} body]])

(defn- solution-modal-input
  [label placeholder]
  [form-item label [antd/input {:placeholder placeholder :style {:height 40}}]])

(defn- solution-modal-select
  [label value options]
  [form-item label
   [antd/select {:value value :style {:width "100%" :height 40}}
    (for [option options]
      ^{:key option} [antd/select-option {:value option} option])]])

(defn- solution-modal-section
  [title & children]
  [:div {:style {:border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                 :borderRadius 12 :padding 24 :marginTop 18}}
   [:div {:style {:fontSize 18 :fontWeight 800
                  :borderBottom "1px solid var(--ant-color-border-secondary, #edf0f5)"
                  :paddingBottom 14 :marginBottom 18
                  :color "var(--ant-color-text, #1f2937)"}} title]
   (into [:div] children)])

(defn- simple-staff-table
  [roles]
  [:div {:style {:border "1px solid var(--ant-color-border-secondary, #edf0f5)" :borderRadius 4 :overflow "hidden"}}
   [:div {:style {:display "grid" :gridTemplateColumns "80px 1.2fr 1.6fr 1.6fr"
                  :background "var(--ant-color-fill-quaternary, #fafafa)" :fontWeight 700}}
    (for [h ["序号" "岗位名称" "姓名" "职称（资质）"]]
      ^{:key h} [:div {:style {:padding 12 :borderRight "1px solid var(--ant-color-border-secondary, #edf0f5)"}} h])]
   (for [[idx role] (map-indexed vector roles)]
     ^{:key role}
     [:div {:style {:display "grid" :gridTemplateColumns "80px 1.2fr 1.6fr 1.6fr"
                    :borderTop "1px solid var(--ant-color-border-secondary, #edf0f5)"}}
      [:div {:style {:padding 10}} (inc idx)]
      [:div {:style {:padding 10 :fontWeight 600}} role]
      [:div {:style {:padding 8}} [antd/input {:placeholder "姓名" :style {:height 36}}]]
      [:div {:style {:padding 8}} [antd/select {:value "工程师" :style {:width "100%" :height 36}}
                                   [antd/select-option {:value "工程师"} "工程师"]
                                   [antd/select-option {:value "高级工程师"} "高级工程师"]]]])])

(defn- solution-edit-modal-static
  [section open? on-close project]
  (let [project (hydrate-project project)
        title (case section
                :project "编辑 · 项目概况"
                :people "编辑 · 人员组织"
                :cover "编辑 · 方案封面"
                :design "编辑 · 设计概况"
                :layout "编辑 · 平面布置图"
                "编辑")]
    [antd/modal (merge (modal-size 1180)
                       {:open open? :title title :okText "保存" :cancelText "取消" :destroyOnHidden true
                        :on-ok on-close :on-cancel on-close})
     (case section
       :project
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "以下为项目概况编辑模式。"]
        [solution-modal-section "项目概况"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
          [full-row [solution-modal-input "项目名称 *" (or (:project_name project) "项目名称")]]
          [solution-modal-select "工程业态 *" (or (:engineering_industry project) "基础设施") ["基础设施" "房建" "装饰装修"]]
          [solution-modal-select "工程性质 *" (or (:engineering_nature project) "商业商场") ["商业商场" "医院/医疗卫生" "工业建筑"]]
          [solution-modal-input "工程地址 *" (or (:project_address project) "工程地址")]
          [solution-modal-input "建设规模 *" (or (:construction_scale project) "建设规模")]
          [solution-modal-select "所属省份 *" (or (:province project) "河北省") ["河北省" "山东省" "广东省" "江苏省"]]
          [solution-modal-input "承包范围 *" (or (:contract_scope project) "承包范围")]
          [form-item "总占地面积" [unit-input project (fn [_]) :total_land_area "总占地面积" "万㎡"]]
          [form-item "总建筑面积" [unit-input project (fn [_]) :total_building_area "总建筑面积" "万㎡"]]
          [solution-modal-input "建设单位" (or (:construction_unit project) "建设单位")]
          [solution-modal-input "勘察单位" (or (:survey_unit project) "勘察单位")]
          [solution-modal-input "设计单位" (or (:design_unit project) "设计单位")]
          [solution-modal-input "监理单位" (or (:supervision_unit project) "监理单位")]
          [solution-modal-input "总承包单位" (or (:general_contractor_unit project) "总承包单位")]
          [solution-modal-input "主要分包工程" (or (:main_subproject project) "主要分包工程")]
          [form-item "工期" [unit-input project (fn [_]) :contract_period "工期" "天"]]
          [solution-modal-input "质量" (or (:quality_requirement project) "质量")]
          [solution-modal-input "安全" (or (:safety_requirement project) "安全")]
          [solution-modal-input "科技" (or (:technology_requirement project) "科技")]
          [solution-modal-input "开工时间" (or (:start_date project) "开工时间")]
          [solution-modal-input "竣工时间" (or (:end_date project) "竣工时间")]
          [full-row [form-item "工程主要功能或用途" [antd/text-area {:placeholder "工程主要功能或用途" :rows 4 :style {:width "100%"}}]]]]]]

       :people
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "以下为人员组织编辑模式。"]
        [solution-modal-section "总承包项目管理人员及职责分工"
         [simple-staff-table ["安全工程师" "技术工程师" "项目总工" "安全总监" "专业工程师" "责任工程师" "测量工程师" "商务经理" "质量总监" "试验工程师" "物资工程师" "商务工程师" "资料员" "项目经理"]]]
        [solution-modal-section "装饰管理部管理人员及职责分工"
         [simple-staff-table ["专业工程师" "装饰经理" "试验工程师" "深化设计师" "测量工程师" "资料员" "技术工程师" "装饰商务经理" "装饰安全总监" "装饰总工" "装饰生产经理" "设计总监" "物资工程师" "安全工程师" "机械管理员"]]]
        [solution-modal-section "分包单位及岗位人员的安全职责表"
         [:div {:style {:display "flex" :justifyContent "flex-end" :marginBottom 16}} [antd/button {:type "primary" :icon (r/as-element [antd/plus-icon])} "新增分包队伍"]]
         [:div {:style {:height 90 :display "flex" :alignItems "center" :justifyContent "center" :color "var(--ant-color-text-secondary, #8c8c8c)"}} "暂无分包队伍，请点击上方按钮添加"]]]

       :cover
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "填写封面排版用字段；取消或遮罩关闭不保存本次修改。"]
        [solution-modal-section "封面信息"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "18px 28px"}}
          [full-row [solution-modal-input "项目名称" (or (:project_name project) "项目名称")]]
          [solution-modal-select "方案类型" "墙面工程" solution-type-options]
          [solution-modal-input "日期" (short-date (or (:create_time project) "2026-06-12"))]
          [full-row [form-item "项目渲染图" [upload-placeholder "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"]]]
          [solution-modal-input "编制单位" "编制单位"]
          [solution-modal-input "编制人" "编制人"]
          [solution-modal-input "制图人" "制图人"]
          [solution-modal-input "项目负责人" "项目负责人"]
          [solution-modal-input "审核人" "审核人"]]]]

       :design
       [:div
        [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}} "当前仅编辑「设计概况」；保存只更新本区块。"]
        [solution-modal-section "设计概况"
         [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
          [solution-modal-input "总建筑面积（㎡）" "如 181247.08"]
          [solution-modal-input "地上建筑面积（㎡）" "如 101064.08"]
          [solution-modal-input "地下建筑面积（㎡）" "如 80183"]
          [solution-modal-input "地下层数（层）" "如 3"]
          [solution-modal-input "地上层数（层）" "如 17"]
          [solution-modal-input "裙房层数（层）" "如 5"]
          [solution-modal-input "地下层高（m）" "如 4"]
          [solution-modal-input "首层层高（m）" "如 5.4"]
          [solution-modal-input "标准层层高（m）" "如 4.2"]
          [solution-modal-input "防火等级" "如 A1"]]
         (for [label ["楼地面" "墙面" "顶棚" "楼梯" "机房（地面 / 墙面 / 顶棚）" "窗" "防水"]]
           ^{:key label}
           [:div {:style {:marginTop 18}}
            [solution-modal-select label "" ["选择材料添加..." "乳胶漆" "瓷砖" "石材"]]])
         [:div {:style {:marginTop 18}} [form-item "环境保护" [antd/text-area {:placeholder "遵守国家及地方政府关于环境保护、水土保持等要求" :rows 3 :style {:width "100%"}}]]]]]

       :layout
       [:div
        [solution-modal-section "平面布置图"
         [form-item "文字说明" [antd/text-area {:placeholder "描述施工区域、楼层、道路与应急布置等。" :rows 5 :style {:width "100%"}}]]
         [:div {:style {:marginTop 18}} [form-item "上传平面布置图" [upload-placeholder "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"]]]]]
       [:span])]))

(def solution-section-key
  {:project "project-info"
   :people "people-org"
   :cover "cover"
   :design "design-overview"
   :layout "layout-plan"})

(defn- solution-section-default
  [section project]
  (let [project (hydrate-project project)]
    (case section
      :project project
      :people {:management_staff (or (:management_staff project) (default-management-staff))
               :subcontract_teams (or (:subcontract_teams project) [])}
      :cover {:project_name (:project_name project)
              :solution_type "墙面工程"
              :compile_date (short-date (or (:create_time project) ""))
              :compile_unit ""
              :compiler ""
              :draftsman ""
              :project_leader (:project_leader project)
              :reviewer ""}
      :design {:total_building_area (:total_building_area project)
               :ground_building_area ""
               :underground_building_area ""
               :underground_floors ""
               :ground_floors ""
               :podium_floors ""
               :underground_height ""
               :first_floor_height ""
               :standard_floor_height ""
               :fire_rating ""
               :floor_material ""
               :wall_material ""
               :ceiling_material ""
               :stair_material ""
               :machine_room_material ""
               :window_material ""
               :waterproof_material ""
               :environment_protection ""}
      :layout {:drawing_desc ""}
      {})))

(defn- solution-edit-modal
  [{:keys [section mode open? on-save on-close project initial-data files set-files!]}]
  (let [[form] (antd/form-use-form)
        readonly? (= mode :view)
        title (case section
                :project (str (if readonly? "预览" "编辑") " · 项目概况")
                :people (str (if readonly? "预览" "编辑") " · 人员组织")
                :cover (str (if readonly? "预览" "编辑") " · 方案封面")
                :design (str (if readonly? "预览" "编辑") " · 设计概况")
                :layout (str (if readonly? "预览" "编辑") " · 平面布置图")
                (if readonly? "预览" "编辑"))]
    (hooks/use-effect
     (fn []
       (when open?
         (.resetFields form)
         (.setFieldsValue form (clj->js (merge (solution-section-default section project) initial-data))))
       js/undefined)
     [open? section])
    [antd/modal (merge (modal-size 1180)
                       (cond-> {:open open? :title title
                                :okText "保存" :cancelText "取消"
                                :destroyOnHidden true
                                :on-ok #(when-not readonly? (.submit form))
                                :on-cancel on-close}
                         readonly? (assoc :footer nil)))
     [antd/form {:form form :layout "vertical"
                 :disabled readonly?
                 :on-finish (fn [values]
                              (let [values (js->clj values :keywordize-keys true)]
                                (when on-save (on-save section values files))
                                (antd/success! "保存成功")
                                (on-close)))}
      (case section
        :project
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "保存后会用于生成方案的项目信息章节。")]
         [project-form form readonly?]]

        :people
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "保存后会用于生成方案的人员组织章节。")]
         [management-staff-form-item form readonly?]
         [subcontract-teams-form-item form readonly?]]

        :cover
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "封面附件会在生成方案后自动上传。")]
         [solution-modal-section "封面信息"
          [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap "18px 28px"}}
           [full-row [form-field {:type :input :form form :name :project_name :label "项目名称"}]]
           [form-field {:type :select :form form :name :solution_type :label "方案类型" :options solution-type-options}]
           [form-field {:type :input :form form :name :compile_date :label "日期"}]
           [form-field {:type :input :form form :name :compile_unit :label "编制单位"}]
           [form-field {:type :input :form form :name :compiler :label "编制人"}]
           [form-field {:type :input :form form :name :draftsman :label "制图人"}]
           [form-field {:type :input :form form :name :project_leader :label "项目负责人"}]
           [form-field {:type :input :form form :name :reviewer :label "审核人"}]
           [full-row [form-item "项目渲染图"
                      [pending-upload-box {:files files
                                           :set-files! set-files!
                                           :readonly? readonly?
                                           :text "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"}]]]]]]

        :design
        [:div
         [:div {:style {:color "var(--ant-color-text-secondary, #8c8c8c)" :marginBottom 18}}
          (if readonly? "当前为预览模式，表单不可编辑。" "当前仅编辑「设计概况」；保存只更新本区块。")]
         [solution-modal-section "设计概况"
          [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap "18px 28px"}}
           [form-field {:type :input :form form :name :total_building_area :label "总建筑面积（㎡）"}]
           [form-field {:type :input :form form :name :ground_building_area :label "地上建筑面积（㎡）"}]
           [form-field {:type :input :form form :name :underground_building_area :label "地下建筑面积（㎡）"}]
           [form-field {:type :input :form form :name :underground_floors :label "地下层数（层）"}]
           [form-field {:type :input :form form :name :ground_floors :label "地上层数（层）"}]
           [form-field {:type :input :form form :name :podium_floors :label "裙房层数（层）"}]
           [form-field {:type :input :form form :name :underground_height :label "地下层高（m）"}]
           [form-field {:type :input :form form :name :first_floor_height :label "首层层高（m）"}]
           [form-field {:type :input :form form :name :standard_floor_height :label "标准层层高（m）"}]
           [form-field {:type :input :form form :name :fire_rating :label "防火等级"}]]
          (for [[k label] [[:floor_material "楼地面"] [:wall_material "墙面"] [:ceiling_material "顶棚"]
                           [:stair_material "楼梯"] [:machine_room_material "机房（地面 / 墙面 / 顶棚）"]
                           [:window_material "窗"] [:waterproof_material "防水"]]]
            ^{:key (name k)}
            [:div {:style {:marginTop 18}}
             [form-field {:type :select :form form :name k :label label :options ["选择材料添加..." "乳胶漆" "瓷砖" "石材"] :disabled readonly?}]])
          [:div {:style {:marginTop 18}}
           [form-field {:type :textarea :form form :name :environment_protection :label "环境保护" :full? true}]]]]

        :layout
        [:div
         [solution-modal-section "平面布置图"
          [form-field {:type :textarea :form form :name :drawing_desc :label "文字说明" :full? true}]
          [:div {:style {:marginTop 18}}
           [form-item "上传平面布置图"
            [pending-upload-box {:files files
                                 :set-files! set-files!
                                 :readonly? readonly?
                                 :text "点击或将文件拖拽至框内上传；文件类型：PNG/JPG，支持多选"}]]]]]

        [:span])]]))

(defn solution-home-page
  []
  (use-business-shell!)
  (let [[solutions set-solutions!] (hooks/use-state [])
        [projects set-projects!] (hooks/use-state [])
        [engineerings set-engineerings!] (hooks/use-state [])
        [editing set-editing!] (hooks/use-state nil)
        [query set-query!] (hooks/use-state {})
        [columns-config _set-columns-config!] (hooks/use-state
                                               {:solution_name {:label "方案名称" :visible? true}
                                                :solution_type {:label "方案类型" :visible? true}
                                                :project_name {:label "所属项目" :visible? true}
                                                :solution_level {:label "方案级别" :visible? true}
                                                :create_time {:label "生成时间" :visible? true}
                                                :create_by {:label "编制人" :visible? true}})
        [page set-page!] (hooks/use-state 1)
        [page-size set-page-size!] (hooks/use-state 10)
        [workflow set-workflow!] (hooks/use-state :list)
        [selected-category set-selected-category!] (hooks/use-state "一般工程（C/D类）")
        [selected-plan set-selected-plan!] (hooks/use-state (last solution-plan-cards))
        [selected-project set-selected-project!] (hooks/use-state nil)
        [project-picker? set-project-picker!] (hooks/use-state false)
        [project-modal? set-project-modal!] (hooks/use-state false)
        [section-modal set-section-modal!] (hooks/use-state nil)
        [supplement-data set-supplement-data!] (hooks/use-state {})
        [supplement-files set-supplement-files!] (hooks/use-state {})
        [generating-solution-id set-generating-solution-id!] (hooks/use-state nil)
        fetch! (fn []
                 (api/list-solutions query #(when (ok? %) (set-solutions! (rows %))) (fn [_]))
                 (api/list-projects {} #(when (ok? %) (set-projects! (rows %))) (fn [_]))
                 (api/list-engineerings {} #(when (ok? %) (set-engineerings! (rows %))) (fn [_])))]
    (hooks/use-effect (fn [] (fetch!) js/undefined) [])
    (if editing
      [solution-editor {:solution editing :on-back #(do (set-editing! nil) (fetch!))}]
      (let [filtered-projects (if-let [engineering-id (:id (:engineering selected-project))]
                                (filter #(= (:engineering_id %) engineering-id) projects)
                                projects)
            chosen-plan-title (:title selected-plan)
            create-solution! (fn []
                               (when selected-project
                                 (let [payload {:solution_name (str (:project_name selected-project) "-" chosen-plan-title "施工方案")
                                                :engineering_id (:engineering_id selected-project)
                                                :engineering_name (:engineering_name selected-project)
                                                :project_id (:id selected-project)
                                                :project_name (:project_name selected-project)
                                                :solution_type chosen-plan-title
                                                :solution_level "一般"
                                                :current_version 1
                                                :recommend_scene (stringify {:schemeLevel "一般"})
                                                :status "draft"
                                                :progress 0
                                                :remark ""}
                                       persist-supplement! (fn [solution-id]
                                                             (doseq [[section data] supplement-data
                                                                     :let [section-key (solution-section-key section)]
                                                                     :when section-key]
                                                               (api/save-solution-section solution-id section-key
                                                                                          {:content_json (stringify data)}
                                                                                          (fn [_])
                                                                                          (fn [_] (antd/error! "补充信息保存失败"))))
                                                             (doseq [[section files] supplement-files
                                                                     :let [section-key (solution-section-key section)]
                                                                     :when (and section-key (seq files))]
                                                               (upload-files! {:biz-type "solution"
                                                                               :biz-id solution-id
                                                                               :section-key section-key
                                                                               :file-purpose (if (= section :cover) "cover" "attachment")
                                                                               :files files})))]
                                   (api/create-solution payload
                                                        #(handle-result!
                                                          %
                                                          nil
                                                          (fn [result]
                                                            (persist-supplement! (result-id result))
                                                            (let [sol-id (result-id result)]
                                                              (api/generate-solution
                                                               sol-id
                                                               (fn [gen-result]
                                                                 (if (ok? gen-result)
                                                                   (do (antd/success! "方案生成已启动")
                                                                       (set-generating-solution-id! sol-id)
                                                                       (set-workflow! :progress))
                                                                   (do (antd/success! "方案创建成功")
                                                                       (set-workflow! :list))))
                                                               (fn [_]
                                                                 (antd/success! "方案创建成功")
                                                                 (set-workflow! :list)))))
                                                          "生成失败")
                                                        (fn [_] (antd/error! "生成失败"))))))]
        (case workflow
          :engineering
          [solution-shell
           [:div {:style {:marginBottom 56}}
            [antd/button {:on-click #(set-workflow! :list)} "←"]]
           [:div {:style {:display "flex" :justifyContent "center" :gap 18 :marginBottom 76}}
            (for [category solution-categories]
              ^{:key category}
              [:button {:on-click #(set-selected-category! category)
                        :style {:height 58 :minWidth 240 :borderRadius 30
                                :border (if (= category selected-category)
                                          "1px solid var(--ant-color-primary, #8bbcff)"
                                          "1px solid var(--ant-color-border-secondary, #d9dee7)")
                                :background (if (= category selected-category)
                                              "var(--ant-color-primary-bg, #eaf4ff)"
                                              "var(--ant-color-bg-container, #fff)")
                                :color (if (= category selected-category)
                                         "var(--ant-color-primary, #2f7ff0)"
                                         "var(--ant-color-text, #374151)")
                                :fontSize 18 :fontWeight 700 :cursor "pointer"}}
               category])]
           [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(260px, 1fr))" :gap 28}}
            (for [{:keys [key title desc icon available?] :as card} solution-plan-cards]
              ^{:key key}
              [:div {:on-click #(when available?
                                  (set-selected-plan! card)
                                  (set-project-picker! true))
                     :style {:height 290
                             :border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                             :borderRadius 16
                             :background "var(--ant-color-bg-container, #fff)"
                             :display "flex" :flexDirection "column" :alignItems "center" :justifyContent "center"
                             :cursor (if available? "pointer" "not-allowed")
                             :opacity (if available? 1 0.45)}}
               [:div {:style {:width 60 :height 60 :borderRadius 12
                              :background "var(--ant-color-primary-bg, #eef5ff)"
                              :display "flex" :alignItems "center" :justifyContent "center"
                              :fontSize 28 :color "var(--ant-color-primary, #3b82f6)"}} icon]
               [:div {:style {:marginTop 22 :fontSize 21 :fontWeight 700
                              :color "var(--ant-color-text, #1f2937)"}} title]
               [:div {:style {:marginTop 8 :color "var(--ant-color-text-secondary, #8c8c8c)"}} desc]
               [:div {:style {:marginTop 16 :padding "5px 16px" :borderRadius 20
                              :background (if available?
                                            "var(--ant-color-primary-bg, #eaf4ff)"
                                            "var(--ant-color-fill-quaternary, #f1f2f4)")
                              :color (if available?
                                       "var(--ant-color-primary, #2f7ff0)"
                                       "var(--ant-color-text-secondary, #8c8c8c)")
                              :fontWeight 700}}
                (if available? "• 可使用" "暂未开放")]])]
           [antd/modal (merge (modal-size 620)
                              {:open project-picker? :title "选择项目" :footer nil :on-cancel #(set-project-picker! false)})
            [:div {:style {:display "grid" :gap 12}}
             [:div {:style {:fontSize 16 :color "var(--ant-color-text, #374151)"}} "请选择当前方案所属项目："]
             (for [project filtered-projects]
               ^{:key (:id project)}
               [solution-project-card project (= (:id project) (:id selected-project))
                #(do (set-selected-project! project)
                     (set-project-picker! false)
                     (set-workflow! :supplement))])
             [:div {:style {:borderTop "1px solid var(--ant-color-border-secondary, #edf0f5)"
                            :marginTop 10 :paddingTop 18
                            :color "var(--ant-color-text-secondary, #6b7280)"}}
              [:div "没有找到合适的项目？"]
              [antd/button {:type "link" :icon (r/as-element [antd/plus-icon]) :on-click #(set-project-modal! true)} "新建项目"]]]]
           [project-modal {:open? project-modal? :editing nil
                           :on-ok (fn [form upload-files]
                                    (api/create-project form
                                                        #(handle-result!
                                                          %
                                                          nil
                                                          (fn [result]
                                                            (let [new-project-id (result-id result)
                                                                  all-files (vec (mapcat second upload-files))]
                                                              (upload-files! {:biz-type "project"
                                                                              :biz-id new-project-id
                                                                              :section-key ""
                                                                              :file-purpose "attachment"
                                                                              :files all-files
                                                                              :on-done (fn []
                                                                                         (set-project-modal! false)
                                                                                         (fetch!))})))
                                                          "保存失败")
                                                        (fn [_] (antd/error! "保存失败"))))
                           :on-cancel #(set-project-modal! false)}]]

          :supplement
          [solution-shell
           [:div {:style {:background "var(--ant-color-bg-container, #fff)"
                          :borderRadius 18 :padding 26
                          :boxShadow "0 8px 24px rgba(15,23,42,0.06)"}}
            [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                           :borderBottom "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                           :paddingBottom 22}}
             [:div {:style {:display "flex" :alignItems "center" :gap 14}}
              [antd/button {:on-click #(set-workflow! :engineering)} "←"]
              [:h1 {:style {:margin 0 :fontSize 24 :fontWeight 800 :color "var(--ant-color-text, #1f2937)"}} "补充信息"]]
             [antd/button {:type "primary" :size "large" :on-click create-solution!} "生成方案"]]
            [:div {:style {:marginTop 30}}
             [:div {:style {:fontSize 18 :fontWeight 800 :marginBottom 6 :color "var(--ant-color-text, #1f2937)"}} "关联项目信息"]
             [:div {:style {:color "var(--ant-color-text-secondary, #667085)" :marginBottom 22}} "确认本次生成方案所使用的项目概况、人员组织与方案封面信息。"]
             [:div {:style {:display "grid" :gridTemplateColumns "repeat(3, minmax(0, 1fr))" :gap 22}}
              [solution-supplement-card "项目概况" true
               [:div
                [:div "项目名称：" (or (:project_name selected-project) "-")]
                [:div "工程性质：" (or (:engineering_nature (hydrate-project selected-project)) "-")]
                [:div "工程地址：" (or (:project_address selected-project) "-")]]
               #(set-section-modal! {:section :project :mode :view})
               #(set-section-modal! {:section :project :mode :edit})]
              [solution-supplement-card "人员组织" true "已维护总承包项目管理人员及职责分工，可继续编辑人员组织信息。"
               #(set-section-modal! {:section :people :mode :view})
               #(set-section-modal! {:section :people :mode :edit})]
              [solution-supplement-card "方案封面" true "已填写完成"
               #(set-section-modal! {:section :cover :mode :view})
               #(set-section-modal! {:section :cover :mode :edit})]]
             [:div {:style {:marginTop 34 :fontSize 18 :fontWeight 800 :color "var(--ant-color-text, #1f2937)"}} "个性化参数"]
             [:div {:style {:marginTop 14 :border "1px solid var(--ant-color-border-secondary, #e5e7eb)"
                            :borderRadius 8 :padding 18}}
              [form-item "方案级别" [antd/select {:value "一般" :style {:width 300 :height 40}}
                                 [antd/select-option {:value "一般"} "一般"]
                                 [antd/select-option {:value "重点"} "重点"]]]
              [:div {:style {:height 1 :background "var(--ant-color-border-secondary, #edf0f5)" :margin "18px 0"}}]
              [:div {:style {:fontSize 16 :fontWeight 700 :marginBottom 16 :color "var(--ant-color-text, #1f2937)"}} "设计概况与平面布置图"]
              [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, minmax(0, 1fr))" :gap 22}}
               [solution-supplement-card "设计概况" false "尚未填写设计概况。请在本卡片右上角「预览」或「编辑」打开弹窗。"
                #(set-section-modal! {:section :design :mode :view})
                #(set-section-modal! {:section :design :mode :edit})]
               [solution-supplement-card "平面布置图" false "尚未填写平面布置图说明或登记附件。请在本卡片右上角「预览」或「编辑」打开弹窗。"
                #(set-section-modal! {:section :layout :mode :view})
                #(set-section-modal! {:section :layout :mode :edit})]]]]]
           (let [active-section (:section section-modal)]
             [solution-edit-modal {:section active-section
                                   :mode (:mode section-modal)
                                   :open? (some? section-modal)
                                   :project selected-project
                                   :initial-data (get supplement-data active-section)
                                   :files (get supplement-files active-section [])
                                   :set-files! #(set-supplement-files! (assoc supplement-files active-section %))
                                   :on-save (fn [section form files]
                                              (set-supplement-data! (assoc supplement-data section form))
                                              (set-supplement-files! (assoc supplement-files section files)))
                                   :on-close #(set-section-modal! nil)}])]

          :progress
          [solution-shell
           [:div {:style {:marginBottom 24}}
            [antd/button {:on-click #(do (set-workflow! :list) (set-generating-solution-id! nil) (fetch!))} "← 返回列表"]]
           [progress-panel {:solution-id generating-solution-id
                            :on-close #(do (set-workflow! :list) (set-generating-solution-id! nil) (fetch!))}]]

          :chat
          [solution-shell
           [:div {:style {:marginBottom 24}}
            [antd/button {:on-click #(set-workflow! :list)} "← 返回列表"]]
           [:div {:style {:height "calc(100vh - 180px)"}}
            [chat-panel {:solution-id generating-solution-id}]]]

          (let [total (count solutions)
                paginated-solutions (vec (take page-size (drop (* (dec page) page-size) solutions)))
                columns (solution-columns
                         columns-config
                         #(set-editing! %)
                         #(api/delete-solution (:id %)
                                               (fn [_] (antd/success! "删除成功") (fetch!))
                                               (fn [_] (antd/error! "删除失败"))))]
            [solution-shell
             [:div.biz-title-row
              [:h1.biz-title "方案管理"]
              [business-primary-button {:icon (r/as-element [antd/plus-icon])
                                        :on-click #(set-workflow! :engineering)}
               "新增编制"]]
             [:div.biz-panel.biz-search-panel
              [:div.biz-search-grid
               [business-field "方案名称"
                [antd/input {:placeholder "请输入方案名称"
                             :value (value query :solution_name)
                             :on-change #(set-query! (assoc query :solution_name (target-value %)))}]]
               [business-field "所属项目"
                [solution-project-filter-select (:project_id query) projects #(set-query! (assoc query :project_id %))]]
               [business-field "方案类型"
                [solution-filter-select (value query :solution_type) "全部" solution-type-options #(set-query! (assoc query :solution_type %))]]
               [business-field "方案级别"
                [solution-filter-select (value query :solution_level) "全部" solution-level-options #(set-query! (assoc query :solution_level %))]]
               [business-field "生成时间"
                [antd/input {:placeholder "请选择日期"}]]]
              [:div.biz-search-actions
               [business-soft-button {:icon (r/as-element [antd/reload-icon])
                                      :on-click (fn []
                                                  (set-query! {})
                                                  (api/list-solutions {}
                                                                      (fn [result] (when (ok? result) (set-solutions! (rows result))))
                                                                      (fn [_])))}
                "重置"]
               [business-primary-button {:icon (r/as-element [antd/search-icon]) :on-click fetch!} "查询"]]]
             [:div.biz-panel.biz-table-panel
              [antd/table {:rowKey "id"
                           :columns columns
                           :dataSource (clj->js paginated-solutions)
                           :scroll #js {:x "max-content"}
                           :pagination false}]]
             (when (> total page-size)
               [:div {:style {:display "flex" :justifyContent "flex-end" :marginTop 18}}
                [pagination {:page page
                             :page-size page-size
                             :total total
                             :on-change (fn [p s]
                                          (set-page! p)
                                          (set-page-size! s))}]])]))))))

(def resource-config
  {:standard {:type "standard" :title "标准规范库" :button "添加资源"
              :fields [[:category "资源类型" :select] [:code "规范编码" :input] [:name "规范名称" :input]
                       [:tags "适用专项类型" :multi-select] [:attachment "上传文档" :upload]]}
   :vector-kb {:type "vector-kb" :title "通用知识库-章节级" :button "批量新增资源"
               :fields [[:attachment "上传文档" :upload] [:name "章节名称" :input]
                        [:category "适用方案类型" :select] [:tags "适用省份" :select]
                        [:vector_status "章节级别" :select]]}
   :structured-kb {:type "structured-kb" :title "通用知识库 / 条目级" :button "添加资源"
                   :fields [[:name "救援程序名称" :input] [:content "救援程序内容" :textarea]
                            [:category "适用方案类型" :select] [:province "所属省份" :select]
                            [:remark "备注" :textarea]]}
   :case {:type "case" :title "优秀案例库" :button "新增案例"
          :fields [[:name "方案名称" :input] [:related_project_name "项目名称" :input]
                   [:category "方案类型" :select] [:engineering_nature "工程性质" :select]
                   [:engineering_industry "工程业态" :select] [:province "项目所在地" :select]
                   [:approval_time "审核通过时间" :input] [:case_level "方案级别" :select]
                   [:attachment "上传文档" :upload]]}
   :atlas {:type "atlas" :title "通用图集库" :button "批量新增图集"
           :fields [[:attachment "上传图片/图纸" :upload] [:name "图片名称" :input]
                    [:category "所属方案类型" :select] [:tags "适配地区" :select]
                    [:gallery "图库明细" :gallery] [:sort_order "排序" :input]]}})

(def resource-nav
  [{:kind :standard :label "标准规范" :path "/resource/standard" :icon "▤"}
   {:kind :knowledge :label "通用知识库" :path "/resource/vector-kb" :icon "▣"
    :children [{:kind :vector-kb :label "向量知识库" :path "/resource/vector-kb"}
               {:kind :structured-kb :label "结构化知识库" :path "/resource/structured-kb"}]}
   {:kind :case :label "优秀案例库" :path "/resource/case" :icon "◉"}
   {:kind :atlas :label "通用图集库" :path "/resource/atlas" :icon "▰"}])

(def resource-categories
  {:standard ["国家行政文件" "地方行政文件" "国家标准" "地方标准" "行业标准" "企业文件"]
   :vector-kb ["应急救援程序" "施工重难点及应对措施" "应急处置措施" "质量保证措施" "成品保证措施" "风险辨识与分级" "安全生产管理措施" "安全生产管理制度" "应急响应程序"]
   :structured-kb ["应急救援程序" "施工重难点及应对措施" "应急处置措施" "质量保证措施" "成品保证措施" "风险辨识与分级" "安全生产管理措施" "安全生产管理制度" "应急响应程序"]
   :case ["顶面工程" "墙面工程" "地面工程" "基础设施"]
   :atlas ["墙面工程" "地面工程" "吊篮工程" "顶面工程"]})

(def resource-select-options
  {:engineering_nature ["医院/医疗卫生" "酒店" "教育建筑" "工业建筑" "商业商场"]
   :engineering_industry ["基础设施" "房建" "装饰装修" "市政公用"]
   :province ["山东省" "广东省" "河北省" "江苏省" "北京市" "上海市"]
   :case_level ["一般" "重点" "示范" "优秀"]
   :vector_status ["一级标题" "二级标题" "三级标题"]})

(def resource-extra-keys
  [:engineering_nature :engineering_industry :province :approval_time :case_level
   :project_location :engineering_status :submitter :vector_status :structured_fields
   :rescue_content :atlas_region])

(defn- hydrate-resource
  [row]
  (let [row (merge (parse-extra-json (:extra_json row)) row)]
    (cond-> row
      (string? (:tags row)) (assoc :tags (if (seq (:tags row))
                                           (filterv seq (str/split (:tags row) #","))
                                           [])))))

(defn- resource-payload
  [form]
  (let [form (cond-> form
               (vector? (:tags form)) (assoc :tags (str/join "," (:tags form))))
        extra (select-keys form resource-extra-keys)]
    (-> form
        (as-> payload (reduce dissoc payload resource-extra-keys))
        (assoc :extra_json (.stringify js/JSON (clj->js extra))))))

(defn- resource-options
  [kind k]
  (case k
    :category (get resource-categories kind [])
    :tags (if (#{:vector-kb :atlas} kind)
            (:province resource-select-options)
            ["墙面工程" "地面工程" "吊篮工程" "顶面工程"])
    :vector_status (:vector_status resource-select-options)
    :engineering_nature (:engineering_nature resource-select-options)
    :engineering_industry (:engineering_industry resource-select-options)
    :province (:province resource-select-options)
    :case_level (:case_level resource-select-options)
    []))

(defn- multi-select-input
  [form set-form! k placeholder options]
  (let [selected (if (string? (get form k))
                   (filterv seq (str/split (get form k) #","))
                   (vec (or (get form k) [])))]
    [antd/select {:mode "multiple"
                  :value selected
                  :placeholder placeholder
                  :style {:width "100%" :height 36}
                  :on-change #(set-form! (assoc form k (str/join "," (js->clj %))))}
     (for [option options]
       ^{:key option} [antd/select-option {:value option} option])]))

(defn- resource-upload-placeholder
  [kind]
  (let [text (case kind
               :atlas "点击或将文件拖拽至框内上传；文件类型：JPG、JPEG、PNG"
               :vector-kb "点击或将文件拖拽至框内替换；文件类型：DOC/DOCX，文件大小不超过100MB"
               "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX/PDF，文件大小不超过100MB")]
    [:div {:style {:height (if (#{:vector-kb :atlas} kind) 170 140)
                   :width "100%"
                   :boxSizing "border-box"
                   :border "1px dashed var(--ant-color-border, #d9d9d9)"
                   :background "var(--ant-color-fill-quaternary, #f8fafc)"
                   :display "flex"
                   :flexDirection "column"
                   :alignItems "center"
                   :justifyContent "center"
                   :color "var(--ant-color-text-secondary, #8c8c8c)"
                   :gap 10
                   :padding 12
                   :fontSize 13
                   :textAlign "center"}}
     [antd/upload-icon {:style {:fontSize 30 :color "var(--ant-color-primary, #1677ff)"}}]
     [:div text]]))

(defn- resource-form-grid
  [kind & children]
  (into [:div {:style {:display "grid"
                       :gridTemplateColumns (if (#{:vector-kb :atlas} kind)
                                              "repeat(5, minmax(0, 1fr))"
                                              "1fr")
                       :gap "12px 16px"
                       :alignItems "start"}}]
        children))

(defn- resource-columns-config
  [kind]
  (case kind
    :standard {:code {:label "规范编码" :visible? true}
               :name {:label "规范名称" :visible? true}
               :category {:label "适用方案类型" :visible? true}
               :create_time {:label "创建时间" :visible? true}}
    :vector-kb {:name {:label "章节名称" :visible? true}
                :category {:label "适用方案类型" :visible? true}
                :tags {:label "适用省份" :visible? true}
                :vector_status {:label "章节级别" :visible? true}
                :create_time {:label "创建时间" :visible? true}}
    :structured-kb {:name {:label "救援程序名称" :visible? true}
                    :content {:label "救援程序内容" :visible? true}
                    :category {:label "适用方案类型" :visible? true}
                    :province {:label "所属省份" :visible? true}
                    :create_time {:label "创建时间" :visible? true}}
    :case {:name {:label "方案名称" :visible? true}
           :related_project_name {:label "项目名称" :visible? true}
           :category {:label "方案类型" :visible? true}
           :case_level {:label "方案级别" :visible? true}
           :engineering_nature {:label "工程性质" :visible? true}
           :engineering_industry {:label "工程业态" :visible? true}
           :province {:label "项目所在地" :visible? true}
           :approval_time {:label "审批通过时间" :visible? true}
           :submitter {:label "提交人" :visible? true}}
    :atlas {:image {:label "图片" :visible? true}
            :name {:label "图片名称" :visible? true}
            :category {:label "所属方案类型" :visible? true}
            :tags {:label "适配地区" :visible? true}
            :action {:label "操作" :visible? true}}
    {}))

(defn- resource-columns
  [kind columns-config open-view! open-editor! delete!]
  (let [action {:title "操作" :key "action" :fixed "right" :width 150
                :render (fn [_ record]
                          (let [row (js->clj record :keywordize-keys true)]
                            (r/as-element
                             [antd/space {:size 4}
                              [antd/button {:type "link" :size "small" :on-click #(open-view! row)} "查看"]
                              [antd/button {:type "link" :size "small" :on-click #(open-editor! row)} "编辑"]
                              (when (= kind :case)
                                [antd/button {:type "link" :size "small" :on-click #(open-view! row)} "预览"])
                              [antd/button {:type "link" :danger true :size "small" :on-click #(delete! row)} "删除"]])))}]
    (clj->js
     (conj
      (into [{:title "序号" :key "index" :width 70
              :render (fn [_ _ idx] (inc idx))}]
            (filterv some?
                     (case kind
                       :standard [(when (get-in columns-config [:code :visible?])
                              {:title "规范编码" :dataIndex "code" :key "code"})
                            (when (get-in columns-config [:name :visible?])
                              {:title "规范名称" :dataIndex "name" :key "name"})
                            (when (get-in columns-config [:category :visible?])
                              {:title "适用方案类型" :dataIndex "category" :key "category"})
                            (when (get-in columns-config [:create_time :visible?])
                              {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :vector-kb [(when (get-in columns-config [:name :visible?])
                               {:title "章节名称" :dataIndex "name" :key "name"})
                             (when (get-in columns-config [:category :visible?])
                               {:title "适用方案类型" :dataIndex "category" :key "category"})
                             (when (get-in columns-config [:tags :visible?])
                               {:title "适用省份" :dataIndex "tags" :key "tags"})
                             (when (get-in columns-config [:vector_status :visible?])
                               {:title "章节级别" :dataIndex "vector_status" :key "vector_status"})
                             (when (get-in columns-config [:create_time :visible?])
                               {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :structured-kb [(when (get-in columns-config [:name :visible?])
                                   {:title "救援程序名称" :dataIndex "name" :key "name"})
                                 (when (get-in columns-config [:content :visible?])
                                   {:title "救援程序内容" :dataIndex "content" :key "content"})
                                 (when (get-in columns-config [:category :visible?])
                                   {:title "适用方案类型" :dataIndex "category" :key "category"})
                                 (when (get-in columns-config [:province :visible?])
                                   {:title "所属省份" :dataIndex "province" :key "province"})
                                 (when (get-in columns-config [:create_time :visible?])
                                   {:title "创建时间" :dataIndex "create_time" :key "create_time"})]
                 :case [(when (get-in columns-config [:name :visible?])
                          {:title "方案名称" :dataIndex "name" :key "name" :width 320})
                        (when (get-in columns-config [:related_project_name :visible?])
                          {:title "项目名称" :dataIndex "related_project_name" :key "related_project_name" :width 280})
                        (when (get-in columns-config [:category :visible?])
                          {:title "方案类型" :dataIndex "category" :key "category"})
                        (when (get-in columns-config [:case_level :visible?])
                          {:title "方案级别" :dataIndex "case_level" :key "case_level"})
                        (when (get-in columns-config [:engineering_nature :visible?])
                          {:title "工程性质" :dataIndex "engineering_nature" :key "engineering_nature"})
                        (when (get-in columns-config [:engineering_industry :visible?])
                          {:title "工程业态" :dataIndex "engineering_industry" :key "engineering_industry"})
                        (when (get-in columns-config [:province :visible?])
                          {:title "项目所在地" :dataIndex "province" :key "province"})
                        (when (get-in columns-config [:approval_time :visible?])
                          {:title "审批通过时间" :dataIndex "approval_time" :key "approval_time"})
                        (when (get-in columns-config [:submitter :visible?])
                          {:title "提交人" :dataIndex "submitter" :key "submitter"
                           :render (fn [v _] (or v "开发者1"))})]
                 :atlas [(when (get-in columns-config [:image :visible?])
                           {:title "图片" :key "image" :width 80 :render (fn [] (r/as-element [:> PictureOutlined {:style {:fontSize 20 :color "var(--ant-color-primary, #1677ff)"}}]))})
                         (when (get-in columns-config [:name :visible?])
                           {:title "图片名称" :dataIndex "name" :key "name"})
                         (when (get-in columns-config [:category :visible?])
                           {:title "所属方案类型" :dataIndex "category" :key "category"})
                       (when (get-in columns-config [:tags :visible?])
                         {:title "适配地区" :dataIndex "tags" :key "tags"})]
                       [])))
      action))))

(defn- gallery-grid
  [gallery]
  [:div {:style {:marginTop 12}}
   [:h4 "图库"]
   [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(140px, 1fr))" :gap 12}}
    (for [g gallery]
      ^{:key (:id g)}
      [antd/card {:size "small"
                  :cover (r/as-element
                          [:div {:style {:height 90
                                         :background "var(--ant-color-fill-secondary, #f5f5f5)"
                                         :display "flex" :alignItems "center" :justifyContent "center"}}
                           [:> PictureOutlined {:style {:fontSize 32 :color "var(--ant-color-text-disabled, #999)"}}]])}
       [:div {:style {:fontSize 12}} (:image_title g)]
       (when (= "Y" (:is_cover g)) [antd/tag {:color "blue"} "封面"])])]])

(defn- atlas-batch-form
  [{:keys [files set-files! readonly?]}]
  [:div
   (if readonly?
     [:div.biz-atlas-upload
      [:div.biz-atlas-upload-icon "▰"]
      [:div "图片附件"]
      [:div {:style {:fontSize 12}} "文件类型：JPG、JPEG、PNG"]]
     [antd/upload {:showUploadList false
                   :multiple true
                   :beforeUpload (fn [file]
                                   (set-files! (conj (vec files) file))
                                   false)}
      [:div.biz-atlas-upload
       [:div.biz-atlas-upload-icon "▰"]
       [:div "点击或将文件拖拽至框内上传"]
       [:div {:style {:fontSize 12}} "文件类型：JPG、JPEG、PNG"]]])
   [:table.biz-atlas-detail-table
    [:thead
     [:tr
      [:th {:style {:width 70}} "序号"]
      [:th {:style {:width 100}} "图片"]
      [:th "图片名称"]
      [:th "所属方案类型"]
      [:th "适配地区"]
      [:th {:style {:width 90}} "操作"]]]
    (into
     [:tbody]
     (if (seq files)
       (for [[idx file] (map-indexed vector files)]
         ^{:key idx}
         [:tr
          [:td (inc idx)]
          [:td [:> PictureOutlined {:style {:fontSize 22 :color "#0091ff"}}]]
          [:td (file-name file)]
          [:td
           [antd/select {:placeholder "请选择"
                         :style {:width 160}
                         :disabled readonly?}
            (for [option (resource-options :atlas :category)]
              ^{:key option} [antd/select-option {:value option} option])]]
          [:td
           [antd/select {:placeholder "请选择"
                         :style {:width 140}
                         :disabled readonly?}
            (for [option (:province resource-select-options)]
              ^{:key option} [antd/select-option {:value option} option])]]
          [:td
           (when-not readonly?
             [antd/button {:type "link"
                           :danger true
                           :size "small"
                           :on-click #(set-files! (vec (concat (subvec (vec files) 0 idx)
                                                               (subvec (vec files) (inc idx)))))}
              "删除"])]])
       [[:tr
         [:td {:colSpan 6}
          [:div.biz-atlas-empty "暂无数据"]]]]))]])

(defn- resource-modal-width
  [kind]
  (case kind
    :standard 520
    :case 600
    :vector-kb "50vw"
    :atlas "50vw"
    760))

(defn- resource-modal-title
  [kind viewing? editing title]
  (cond
    viewing? (str "查看" title)
    (= kind :atlas) (if (:id editing) "编辑图集" "批量新增图集")
    (= kind :case) (if (:id editing) "编辑案例" "新增案例")
    (= kind :vector-kb) (if (:id editing) "编辑资源" "批量新增资源")
    (:id editing) (str "编辑" title)
    :else (str "新增" title)))

(defn- resource-drawer-kind?
  [kind]
  (contains? #{:vector-kb :atlas} kind))

(defn- resource-has-secondary?
  [kind]
  (contains? #{:standard :vector-kb :structured-kb} kind))

(defn- resource-form-field*
  [kind form gallery pending-files set-pending-files! attachments delete-attachment! readonly? [k label field-type]]
  (let [upload-text (case kind
                      :atlas "点击或将文件拖拽至框内上传；文件类型：JPG、JPEG、PNG"
                      :vector-kb "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX，文件大小不超过100MB"
                      "点击或将文件拖拽至框内上传；文件类型：DOC/DOCX/PDF，文件大小不超过100MB")]
    (case field-type
      :upload [full-row
               [form-item label
                [:div
                 (if (resource-drawer-kind? kind)
                   [:div.biz-resource-upload-wide
                    [pending-upload-box {:files pending-files
                                         :set-files! set-pending-files!
                                         :readonly? readonly?
                                         :text upload-text}]]
                   [:<>
                    [attachment-list {:attachments attachments
                                      :readonly? readonly?
                                      :on-delete delete-attachment!}]
                    (when-not readonly?
                      [:div {:style {:marginTop 8}}
                       [pending-upload-box {:files pending-files
                                            :set-files! set-pending-files!
                                            :readonly? false
                                            :text upload-text}]])])]]]
      :gallery [full-row
                [form-item label
                 (if (= kind :atlas) [gallery-grid gallery] [:span])]]
      :textarea [full-row
                 [form-field {:type :textarea :form form :name k :label label :full? true :disabled readonly?}]]
      :select [form-field {:type :select :form form :name k :label label :options (resource-options kind k) :disabled readonly?}]
      :multi-select [form-field {:type :multi-select :form form :name k :label label :options (resource-options kind k) :disabled readonly?}]
      :status [form-field {:type :status :form form :name k :label label :disabled readonly?}]
      [form-field {:type :input :form form :name k :label label :disabled readonly?}])))

(defn- resource-filter-field
  [kind query set-query! k label]
  [business-field label
   (cond
     (= k :approval_time)
     [antd/input {:placeholder "请选择"
                  :style {:width 180}
                  :value (value query k)
                  :on-change #(set-query! (assoc query k (target-value %)))}]

     (contains? #{:category :case_level :engineering_nature :engineering_industry :province :tags} k)
     [solution-filter-select (value query k)
      "请选择"
      (case k
        :category (or (seq (resource-options kind :category)) solution-type-options)
        :case_level (:case_level resource-select-options)
        :engineering_nature (:engineering_nature resource-select-options)
        :engineering_industry (:engineering_industry resource-select-options)
        :province (:province resource-select-options)
        :tags (:province resource-select-options)
        [])
      #(set-query! (assoc query k %))]

     :else
     [antd/input {:placeholder (str "请输入" label)
                  :style {:width 220}
                  :value (value query k)
                  :on-change #(set-query! (assoc query k (target-value %)))}])])

(defn- resource-filter-fields
  [kind]
  (case kind
    :vector-kb [[:name "章节名称"] [:category "适用方案类型"] [:tags "适用省份"]]
    :structured-kb [[:category "适用方案类型"] [:province "所属省份"]]
    :case [[:category "方案类型"] [:case_level "方案级别"] [:engineering_nature "工程性质"]
           [:province "项目所在地区"] [:approval_time "审批通过时间"]]
    :atlas [[:category "方案类型"] [:name "图片名称"] [:tags "地区"]]
    [[:name "规范名称"] [:category "适用方案类型"]]))

(defn resource-page
  [kind]
  (use-business-shell!)
  (let [{:keys [type title fields button]} (get resource-config kind)
        [items set-items!] (hooks/use-state [])
        [modal? set-modal!] (hooks/use-state false)
        [editing set-editing!] (hooks/use-state nil)
        [viewing? set-viewing!] (hooks/use-state false)
        [form] (antd/form-use-form)
        [gallery set-gallery!] (hooks/use-state [])
        [attachments set-attachments!] (hooks/use-state [])
        [pending-files set-pending-files!] (hooks/use-state [])
        [query set-query!] (hooks/use-state {})
        [columns-config _set-columns-config!] (hooks/use-state (resource-columns-config kind))
        [page set-page!] (hooks/use-state 1)
        [page-size set-page-size!] (hooks/use-state 10)
        fetch! (fn []
                 (api/list-resources type query
                                     #(when (ok? %) (set-items! (rows %)))
                                     (fn [_])))]
    (hooks/use-effect (fn [] (fetch!) js/undefined) [type])
    (let [reload-gallery! (fn [resource-id]
                            (when (= kind :atlas)
                              (api/list-gallery-items resource-id
                                                      #(when (ok? %) (set-gallery! (:data %)))
                                                      (fn [_]))))
          delete-attachment! (fn [attachment]
                               (api/delete-business-attachment (:id attachment)
                                                               (fn [result]
                                                                 (handle-result! result "删除成功"
                                                                                 (fn [_]
                                                                                   (set-attachments! (vec (remove #(= (:id %) (:id attachment)) attachments)))
                                                                                   (when (:id editing)
                                                                                     (reload-gallery! (:id editing))))
                                                                                 "删除失败"))
                                                               (fn [_] (antd/error! "删除失败"))))
          open-resource! (fn [row readonly?]
                           (set-editing! row)
                           (set-viewing! readonly?)
                           (set-gallery! [])
                           (set-attachments! (vec (or (:attachments row) [])))
                           (set-pending-files! [])
                           (.resetFields form)
                           (.setFieldsValue form (clj->js (hydrate-resource row)))
                           (set-modal! true)
                           (api/get-resource type (:id row)
                                             (fn [result]
                                               (when (ok? result)
                                                 (let [detail (:data result)]
                                                   (set-editing! detail)
                                                   (set-attachments! (vec (or (:attachments detail) [])))
                                                   (.resetFields form)
                                                   (.setFieldsValue form (clj->js (hydrate-resource detail))))))
                                             (fn [_] nil))
                           (reload-gallery! (:id row)))
          open-view! (fn [row] (open-resource! row true))
          open-editor! (fn [row] (open-resource! row false))
          delete! (fn [row]
                    (api/delete-resource type (:id row)
                                         (fn [_] (antd/success! "删除成功") (fetch!))
                                         (fn [_] (antd/error! "删除失败"))))
          columns (resource-columns kind columns-config open-view! open-editor! delete!)
          total (count items)
          paginated-items (mapv hydrate-resource (take page-size (drop (* (dec page) page-size) items)))]
      [:div.biz-page
       [business-shell-styles]
       [business-top-nav :resource]
       [:div.biz-resource-layout {:style {:gridTemplateColumns (if (resource-has-secondary? kind)
                                                                 "190px 210px 1fr"
                                                                 "190px 1fr")}}
        [:aside.biz-resource-primary-side
         (for [{nav-kind :kind label :label path :path icon :icon children :children} resource-nav]
           ^{:key (name nav-kind)}
           [:div
            [:a.biz-resource-menu-item {:href path
                                        :class (when (or (= nav-kind kind)
                                                         (some #(= (:kind %) kind) children))
                                                 "active")}
             icon label]
            (when (seq children)
              [:div
               (for [{child-kind :kind child-label :label child-path :path} children]
                 ^{:key (name child-kind)}
                 [:a.biz-resource-menu-item {:href child-path
                                             :class (when (= child-kind kind) "active")
                                             :style {:height 40 :paddingLeft 48 :fontSize 13 :borderLeftWidth 4}}
                  child-label])])])]
        (when (resource-has-secondary? kind)
          [:aside.biz-resource-secondary-side
           [:div.biz-resource-type-title
            (case kind
              :standard "规范库类型"
              :vector-kb "知识类型"
              :structured-kb "知识类型"
              "资源类型")]
           [:div.biz-resource-type-search
            [antd/input {:placeholder "搜索关键字"}]]
           [:div
            (for [[idx category] (map-indexed vector (get resource-categories kind []))]
              ^{:key category}
              [:div.biz-resource-category {:class (when (zero? idx) "active")}
               category])]])
        [:main.biz-resource-main
         [:div.biz-resource-title title]
         [:div.biz-resource-body
          [:div {:style {:display "flex" :alignItems "flex-end" :gap 14 :marginBottom 20 :flexWrap "wrap"}}
           (for [[field-key label] (resource-filter-fields kind)]
             ^{:key (name field-key)}
             [resource-filter-field kind query set-query! field-key label])
           [business-primary-button {:icon (r/as-element [antd/search-icon])
                                     :on-click #(do (set-page! 1) (fetch!))}
            "查询"]
           [business-soft-button {:on-click #(do (set-query! {})
                                                 (api/list-resources type {}
                                                                     (fn [result] (when (ok? result) (set-items! (rows result))))
                                                                     (fn [_])))}
            "重置"]
           [business-primary-button {:icon (r/as-element [antd/plus-icon])
                                     :on-click #(do (set-editing! nil)
                                                    (set-viewing! false)
                                                    (set-gallery! [])
                                                    (set-attachments! [])
                                                    (set-pending-files! [])
                                                    (.resetFields form)
                                                    (.setFieldsValue form #js {:status "0"})
                                                    (set-modal! true))}
            (or button "新增")]]
          [:div.biz-table-panel
           [antd/table {:rowKey "id"
                        :columns columns
                        :dataSource (clj->js paginated-items)
                        :scroll #js {:x "max-content"}
                        :pagination false}]]
          (when (> total page-size)
            [:div {:style {:display "flex" :justifyContent "flex-end" :marginTop 12}}
             [pagination {:page page
                          :page-size page-size
                          :total total
                          :on-change (fn [p s]
                                       (set-page! p)
                                       (set-page-size! s))}]])]]]
       [antd/modal (merge (modal-size (resource-modal-width kind))
                          (cond-> {:open modal?
                                   :className (str "biz-modal" (when (resource-drawer-kind? kind) " biz-resource-drawer-modal"))
                                   :title (resource-modal-title kind viewing? editing title)
                                   :okText (if (= kind :atlas) "批量保存" "确定")
                                   :cancelText "取消"
                                   :destroyOnHidden true
                                   :on-cancel #(set-modal! false)
                                   :on-ok #(when-not viewing? (.submit form))}
                            viewing? (assoc :footer nil)))
        [antd/form {:form form :layout "vertical"
                    :disabled viewing?
                    :on-finish (fn [values]
                                 (let [values (js->clj values :keywordize-keys true)
                                       finish! (fn []
                                                 (antd/success! "保存成功")
                                                 (set-modal! false)
                                                 (set-pending-files! [])
                                                 (fetch!))
                                       upload-after-save! (fn [resource-id]
                                                            (upload-files! {:biz-type type
                                                                            :biz-id resource-id
                                                                            :section-key ""
                                                                            :file-purpose (if (= kind :atlas) "gallery" "attachment")
                                                                            :files pending-files
                                                                            :on-uploaded (when (= kind :atlas)
                                                                                           (fn [result]
                                                                                             (api/create-gallery-item
                                                                                              resource-id
                                                                                              {:attachment_id (get-in result [:data :id])
                                                                                               :image_title (get-in result [:data :original_name])
                                                                                               :is_cover (if (empty? gallery) "Y" "N")}
                                                                                              (fn [_])
                                                                                              (fn [_] (antd/error! "图库明细创建失败")))))
                                                                            :on-done finish!}))]
                                   (if (:id editing)
                                     (api/update-resource type (:id editing) (resource-payload values)
                                                          (fn [result]
                                                            (handle-result! result nil (fn [_] (upload-after-save! (:id editing))) "保存失败"))
                                                          (fn [_] (antd/error! "保存失败")))
                                     (api/create-resource type (resource-payload values)
                                                          (fn [result]
                                                            (handle-result! result nil (fn [saved] (upload-after-save! (result-id saved))) "保存失败"))
                                                          (fn [_] (antd/error! "保存失败"))))))}
         (if (= kind :atlas)
           [atlas-batch-form {:files pending-files
                              :set-files! set-pending-files!
                              :readonly? viewing?}]
           (into [resource-form-grid kind]
                 (map (fn [field]
                        ^{:key (name (first field))}
                        [resource-form-field* kind form gallery pending-files set-pending-files! attachments delete-attachment! viewing? field])
                      fields)))]]])))

;; ========== 方案生成进度面板 ==========

(defn- progress-status-label
  "章节状态文字。"
  [status]
  (case status
    -1 "等待生成"
    0 "生成中"
    1 "已完成"
    2 "生成失败"
    "未知"))

(defn- progress-status-color
  "章节状态颜色。"
  [status]
  (case status
    -1 "default"
    0 "processing"
    1 "success"
    2 "error"
    "default"))

(defn progress-panel
  "方案生成进度面板。"
  [{:keys [solution-id on-close]}]
  (let [[status-data set-status-data!] (hooks/use-state nil)
        [polling? set-polling!] (hooks/use-state false)
        poll-ref (hooks/use-ref nil)]
    (hooks/use-effect
     (fn []
       (let [fetch-status! (fn []
                              (api/get-generate-status
                               solution-id
                               (fn [result]
                                 (when (ok? result)
                                   (set-status-data! (:data result))
                                   (when (contains? #{"generating"} (get-in result [:data :status]))
                                     (set-polling! true))
                                   (when (contains? #{"complete" "failed"} (get-in result [:data :status]))
                                     (set-polling! false))))
                               (fn [_] nil)))]
         (fetch-status!)
         (fn []
           (when-let [interval-id (.-current poll-ref)]
             (js/clearInterval interval-id)))))
     [solution-id])
    (hooks/use-effect
     (fn []
       (if polling?
        (let [interval-id (atom nil)
              id (js/setInterval
                  (fn []
                    (api/get-generate-status
                     solution-id
                     (fn [result]
                       (when (ok? result)
                         (set-status-data! (:data result))
                         (when (contains? #{"complete" "failed"} (get-in result [:data :status]))
                           (set-polling! false)
                           (when @interval-id
                             (js/clearInterval @interval-id)))))
                     (fn [_] nil)))
                  3000)]
          (reset! interval-id id)
          (set! (.-current poll-ref) id)
          (fn [] (js/clearInterval id)))
        js/undefined))
     [polling? solution-id])
    (let [data status-data
          chapters (or (:chapters data) [])
          total (count chapters)
          completed (count (filter #(= 1 (:status %)) chapters))
          progress (if (pos? total) (js/Math.round (* 100 (/ completed total))) 0)]
      [:div
       [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
        [:h3 {:style {:margin 0}} "方案生成进度"]
        (when on-close [antd/button {:type "link" :on-click on-close} "关闭"])]
       (when data
         [:div
          [:div {:style {:marginBottom 16}}
           [antd/tag {:color (case (:status data) "complete" "success" "failed" "error" "processing")}
            (case (:status data) "generating" "生成中" "complete" "已完成" "failed" "生成失败" (:status data))]
           [:span {:style {:marginLeft 8}} "总进度: " progress "%"]]
          [antd/progress {:percent progress :status (if (= "failed" (:status data)) "exception" "active")}]
          (when (seq chapters)
            [:div {:style {:marginTop 16}}
             [:h4 "章节进度"]
             (for [ch chapters]
               ^{:key (:id ch)}
               [:div {:style {:display "flex" :alignItems "center" :gap 8 :padding "6px 0" :borderBottom "1px solid #f0f0f0"}}
                [antd/tag {:color (progress-status-color (:status ch)) :style {:minWidth 70 :textAlign "center"}}
                 (progress-status-label (:status ch))]
                [:span {:style {:flex 1}} (:chapter_name ch)]
                (when (:error_msg ch)
                  [:span {:style {:color "#ff4d4f" :fontSize 12}} (:error_msg ch)])])])])])))

;; ========== AI对话面板 ==========

(defn chat-panel
  "方案AI问答对话面板。"
  [{:keys [solution-id]}]
  (let [[session set-session!] (hooks/use-state nil)
        [messages set-messages!] (hooks/use-state [])
        [input-text set-input-text!] (hooks/use-state "")
        [sending? set-sending!] (hooks/use-state false)
        messages-end-ref (hooks/use-ref nil)]
    (hooks/use-effect
     (fn []
       (api/get-or-create-chat-session
        solution-id
        (fn [result]
          (when (ok? result)
            (let [sess (:data result)]
              (set-session! sess)
              (api/list-chat-messages
               (:id sess)
               (fn [msg-result]
                 (when (ok? msg-result)
                   (set-messages! (:data msg-result))))
               (fn [_] nil)))))
        (fn [_] nil)))
     [solution-id])
    (let [scroll-to-bottom! (fn []
                              (when-let [el (.-current messages-end-ref)]
                                (.-scrollIntoView el)))]
      (hooks/use-effect (fn [] (scroll-to-bottom!) js/undefined) [(count messages)])
      [:div {:style {:display "flex" :flexDirection "column" :height "100%"}}
       [:div {:style {:flex 1 :overflowY "auto" :padding "16px" :display "grid" :gap 12}}
        (if (empty? messages)
          [:div {:style {:textAlign "center" :color "#8c8c8c" :padding 40}} "暂无对话记录，输入问题开始问答"]
          (for [msg messages]
            ^{:key (:id msg)}
            [:div {:style {:display "flex" :justifyContent (if (= "user" (:role msg)) "flex-end" "flex-start")}}
             [:div {:style {:maxWidth "80%"
                            :padding "10px 14px"
                            :borderRadius 8
                            :background (if (= "user" (:role msg)) "#1677ff" "#f5f5f5")
                            :color (if (= "user" (:role msg)) "#fff" "#333")}}
              [:div {:style {:whiteSpace "pre-wrap" :fontSize 14}} (:content msg)]
              (when (seq (:reasoning_content msg))
                [:div {:style {:marginTop 6 :paddingTop 6 :borderTop "1px dashed rgba(0,0,0,0.1)"
                               :fontSize 12 :opacity 0.7 :whiteSpace "pre-wrap"}}
                 "思考过程: " (:reasoning_content msg)])]]))
        [:div {:ref messages-end-ref}]]
       [:div {:style {:borderTop "1px solid #f0f0f0" :padding "12px 16px" :display "flex" :gap 8}}
        [antd/input {:value input-text
                     :placeholder "请输入问题..."
                     :on-change #(set-input-text! (target-value %))
                     :on-press-enter (fn [e]
                                       (when (and (seq input-text) (not sending?) session)
                                         (let [user-msg {:role "user" :content input-text :id (str "temp-" (js/Date.now))}]
                                           (set-messages! (conj messages user-msg))
                                           (set-input-text! "")
                                           (set-sending! true)
                                           (api/save-chat-message
                                            (:id session)
                                            {:role "user" :content input-text}
                                            (fn [_]
                                              (api/save-chat-message
                                               (:id session)
                                               {:role "assistant" :content "收到您的问题，正在思考中...AI生成功能开发中，敬请期待。" :model_name "deepseek-r1"}
                                               (fn [result]
                                                 (set-sending! false)
                                                 (when (ok? result)
                                                   (api/list-chat-messages
                                                    (:id session)
                                                    (fn [r] (when (ok? r) (set-messages! (:data r))))
                                                    (fn [_] nil))))
                                               (fn [_] (set-sending! false))))
                                            (fn [_] (set-sending! false))))))}]
        [antd/button {:type "primary"
                      :disabled (or (str/blank? input-text) sending?)}
         (if sending? "发送中..." "发送")]]])))

;; ========== 方案文件版本面板 ==========

(defn solution-files-panel
  "方案文件版本管理面板。"
  [{:keys [solution-id]}]
  (let [[files set-files!] (hooks/use-state [])
        [loading? set-loading!] (hooks/use-state false)]
    (hooks/use-effect
     (fn []
       (set-loading! true)
       (api/list-solution-files
        solution-id
        (fn [result]
          (set-loading! false)
          (when (ok? result) (set-files! (:data result))))
        (fn [_] (set-loading! false))))
     [solution-id])
    [:div
     [:h4 "方案文件版本"]
     [antd/table {:loading loading?
                  :rowKey "id"
                  :dataSource (clj->js files)
                  :pagination false
                  :columns (clj->js
                            [{:title "版本" :dataIndex "version" :key "version" :width 80
                              :render (fn [v _] (str "V" v))}
                             {:title "文件名" :dataIndex "file_name" :key "file_name"}
                             {:title "大小" :dataIndex "file_size" :key "file_size"
                              :render (fn [v _] (if (and v (pos? v)) (str (js/Math.round (/ v 1024)) " KB") "-"))}
                             {:title "创建时间" :dataIndex "create_time" :key "create_time"
                              :render (fn [v _] (short-date v))}
                             {:title "操作" :key "action" :width 100
                              :render (fn [_ record]
                                        (let [row (js->clj record :keywordize-keys true)]
                                          (r/as-element
                                           [antd/button {:type "link" :size "small"
                                                         :on-click #(api/download-solution-file solution-id (:id row))} "下载"])))}])}]]))

;; ========== 方案导出面板 ==========

(defn solution-export-panel
  "方案导出功能面板。"
  [{:keys [solution-id solution-name]}]
  (let [[exporting? set-exporting!] (hooks/use-state false)
        handle-export! (fn []
                         (set-exporting! true)
                         (api/export-solution-html
                          solution-id
                          (fn [result]
                            (set-exporting! false)
                            (if (ok? result)
                              (let [html (get-in result [:data :html])
                                    blob (js/Blob. #js [html] #js {:type "text/html;charset=utf-8"})
                                    url (js/URL.createObjectURL blob)
                                    a (.createElement js/document "a")]
                                (set! (.-href a) url)
                                (set! (.-download a) (str (or solution-name "方案") ".html"))
                                (.click a)
                                (js/URL.revokeObjectURL url)
                                (antd/success! "导出成功"))
                              (antd/error! "导出失败")))
                          (fn [_]
                            (set-exporting! false)
                            (antd/error! "导出失败"))))]
    [:div {:style {:display "flex" :gap 8}}
     [antd/button {:type "primary" :loading exporting? :on-click handle-export!} "导出HTML"]]))

;; ========== 知识库搜索面板 ==========

(defn knowledge-search-panel
  "知识库全文搜索面板。"
  []
  (let [[keyword set-keyword!] (hooks/use-state "")
        [results set-results!] (hooks/use-state [])
        [loading? set-loading!] (hooks/use-state false)
        do-search! (fn []
                     (when (seq keyword)
                       (set-loading! true)
                       (api/search-knowledge
                        keyword
                        (fn [result]
                          (set-loading! false)
                          (when (ok? result) (set-results! (rows result))))
                        (fn [_] (set-loading! false)))))]
    [:div
     [:div {:style {:display "flex" :gap 8 :marginBottom 16}}
      [antd/input {:value keyword
                   :placeholder "搜索知识库..."
                   :on-change #(set-keyword! (target-value %))
                   :on-press-enter do-search!}]
      [antd/button {:type "primary" :loading loading? :on-click do-search!} "搜索"]]
     (when (seq results)
       [:div
        [:div {:style {:marginBottom 8 :color "#666"}} (str "找到 " (count results) " 条结果")]
        (for [item results]
          ^{:key (:id item)}
          [antd/card {:size "small" :style {:marginBottom 8}}
           [:div {:style {:fontWeight 600}} (:name item)]
           (when (:category item) [:div {:style {:color "#666" :fontSize 12}} (:category item)])
           (when (:content item)
             [:div {:style {:marginTop 8 :fontSize 13 :color "#333" :maxHeight 100 :overflow "hidden"}}
              (subs (:content item) 0 (min 200 (count (:content item))))])])])]))

;; ========== 资源关联管理面板 ==========

(defn resource-relations-panel
  "资源关联管理面板，管理方案类型和省份关联。"
  [{:keys [resource-type resource-id]}]
  (let [[relations set-relations!] (hooks/use-state [])
        [loading? set-loading!] (hooks/use-state false)
        [add-type set-add-type!] (hooks/use-state "scheme_type")
        [add-value set-add-value!] (hooks/use-state "")
        fetch! (fn []
                 (set-loading! true)
                 (api/list-resource-relations
                  resource-type resource-id
                  (fn [result]
                    (set-loading! false)
                    (when (ok? result) (set-relations! (:data result))))
                  (fn [_] (set-loading! false))))]
    (hooks/use-effect (fn [] (when resource-id (fetch!)) js/undefined) [resource-id])
    (let [add-relation! (fn []
                          (when (seq add-value)
                            (let [new-relations (conj relations {:route_type add-type :route_value add-value})]
                              (api/save-resource-relations
                               resource-type resource-id new-relations
                               (fn [result]
                                 (when (ok? result)
                                   (set-relations! new-relations)
                                   (set-add-value! "")
                                   (antd/success! "关联已保存")))
                               (fn [_] (antd/error! "保存失败"))))))]
      [:div
       [:h4 "资源关联"]
       [antd/table {:loading loading? :rowKey #(str (:route_type %) "-" (:route_value %))
                    :dataSource (clj->js relations) :pagination false :size "small"
                    :columns (clj->js
                              [{:title "关联类型" :dataIndex "route_type" :key "route_type"
                                :render (fn [v _] (if (= "scheme_type" v) "方案类型" "省份"))}
                               {:title "关联值" :dataIndex "route_value" :key "route_value"}
                               {:title "操作" :key "action" :width 80
                                :render (fn [_ record]
                                          (let [row (js->clj record :keywordize-keys true)]
                                            (r/as-element
                                             [antd/button {:type "link" :danger true :size "small"
                                                           :on-click (fn []
                                                                       (let [filtered (vec (remove #(and (= (:route_type %) (:route_type row))
                                                                                                         (= (:route_value %) (:route_value row)))
                                                                                                   relations))]
                                                                         (api/save-resource-relations
                                                                          resource-type resource-id filtered
                                                                          (fn [result]
                                                                            (when (ok? result) (set-relations! filtered)))
                                                                          (fn [_] (antd/error! "删除失败")))))} "删除"])))}])}]
       [:div {:style {:display "flex" :gap 8 :marginTop 8}}
        [antd/select {:value add-type :style {:width 120}
                      :on-change #(set-add-type! %)}
         [antd/select-option {:value "scheme_type"} "方案类型"]
         [antd/select-option {:value "region"} "省份"]]
        [antd/input {:value add-value :placeholder "输入关联值" :style {:flex 1}
                     :on-change #(set-add-value! (target-value %))
                     :on-press-enter add-relation!}]
        [antd/button {:type "primary" :on-click add-relation!} "添加"]]])))
