(ns com.ruoyi.rouyi.frontend.pages.form-builder
  "在线表单构建器 — 拖拽式表单设计，参考 RuoYi-Vue 在线构建器。"
  (:require
   [reagent.core :as r]
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   ["@ant-design/icons" :refer [PlusOutlined DeleteOutlined DragOutlined EyeOutlined CodeOutlined
                                CopyOutlined ClearOutlined]]
   [clojure.string :as str]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

;; ─── 组件面板定义 ──────────────────────────────────────────────────

(def component-palette
  "可用表单组件列表。"
  [{:type :input :label "文本输入" :icon "Input" :tag :antd/input
    :defaults {:field "" :label "" :placeholder "" :required false :width "100%"}}
   {:type :textarea :label "多行文本" :icon "TextArea" :tag :antd/textarea
    :defaults {:field "" :label "" :placeholder "" :required false :rows 4}}
   {:type :number :label "数字输入" :icon "Number" :tag :antd/input-number
    :defaults {:field "" :label "" :placeholder "" :required false :min 0 :max 99999}}
   {:type :select :label "下拉选择" :icon "Select" :tag :antd/select
    :defaults {:field "" :label "" :placeholder "请选择" :required false :options ""}}
   {:type :radio :label "单选框" :icon "Radio" :tag :antd/radio-group
    :defaults {:field "" :label "" :required false :options ""}}
   {:type :date :label "日期选择" :icon "Date" :tag :antd/date-picker
    :defaults {:field "" :label "" :placeholder "" :required false}}
   {:type :switch :label "开关" :icon "Switch" :tag :antd/switch
    :defaults {:field "" :label ""}}
   {:type :upload :label "文件上传" :icon "Upload" :tag :antd/upload
    :defaults {:field "" :label ""}}])

;; ─── 辅助函数 ──────────────────────────────────────────────────────

(defn- generate-hiccup
  "根据表单设计数据生成 Reagent Hiccup 代码。"
  [items]
  (str "(fn []\n  [antd/form {:layout \"vertical\"}\n"
       (->> items
            (map (fn [item]
                   (let [props (:props item)
                         typ (:type item)
                         field (or (:field props) "field")
                         label (or (:label props) "Label")]
                     (case typ
                       :input (str "   [antd/form-item {:label \"" label "\" :name \"" field "\""
                                   (when (:required props) " :rules #js [#js {:required true :message \"请输入" label "\"}]")
                                   "}\n    [antd/input {:placeholder \"" (or (:placeholder props) "") "\"}]]")
                       :textarea (str "   [antd/form-item {:label \"" label "\" :name \"" field "\""
                                      (when (:required props) " :rules #js [#js {:required true}]")
                                      "}\n    [antd/text-area {:rows " (or (:rows props) 4) " :placeholder \"" (or (:placeholder props) "") "\"}]]")
                       :number (str "   [antd/form-item {:label \"" label "\" :name \"" field "\"}\n    [antd/input-number {:style {:width \"100%\"} :placeholder \"" (or (:placeholder props) "") "\"}]]")
                       :select (str "   [antd/form-item {:label \"" label "\" :name \"" field "\""
                                    (when (:required props) " :rules #js [#js {:required true}]")
                                    "}\n    [antd/select {:placeholder \"" (or (:placeholder props) "请选择") "\"}\n     [antd/select-option {:value \"1\"} \"选项1\"]\n     [antd/select-option {:value \"2\"} \"选项2\"]]]")
                       :radio (str "   [antd/form-item {:label \"" label "\" :name \"" field "\"}\n    [antd/radio-group\n     [antd/radio {:value \"1\"} \"选项1\"]\n     [antd/radio {:value \"2\"} \"选项2\"]]]")
                       :date (str "   [antd/form-item {:label \"" label "\" :name \"" field "\""
                                  (when (:required props) " :rules #js [#js {:required true}]")
                                  "}\n    [antd/date-picker {:style {:width \"100%\"}}]]")
                       :switch (str "   [antd/form-item {:label \"" label "\" :name \"" field "\"}\n    [antd/switch]]")
                       :upload (str "   [antd/form-item {:label \"" label "\" :name \"" field "\"}\n    [antd/upload {:action \"/api/upload\"}\n     [antd/button \"上传文件\"]]]")
                       (str "   ;; " (name typ) " - " label))))))
       (str/join "\n")
       "\n   [antd/form-item]\n    [antd/button {:type \"primary\" :htmlType \"submit\"} \"提交\"]\n   ]])\n"))

;; ─── 属性编辑器 ──────────────────────────────────────────────────────

(defn- prop-editor
  "右侧属性编辑面板。"
  [selected-item]
  (when selected-item
    (let [props (:props selected-item)
          typ (:type selected-item)]
      [:div {:style {:padding 16 :borderLeft "1px solid var(--ant-color-border-secondary, #f0f0f0)" :minWidth 260}}
       [:h4 {:style {:margin "0 0 16px 0"}} "组件属性"]
       [antd/form {:layout "vertical" :size "small"}
        [antd/form-item {:label "字段名" :required true}
         [antd/input {:value (:field props "")
                      :on-change #(rf/dispatch [:fb/update-prop :field (.. % -target -value)])}]]
        [antd/form-item {:label "标签名" :required true}
         [antd/input {:value (:label props "")
                      :on-change #(rf/dispatch [:fb/update-prop :label (.. % -target -value)])}]]
        (when (contains? #{:input :textarea :select} typ)
          [antd/form-item {:label "占位文本"}
           [antd/input {:value (:placeholder props "")
                        :on-change #(rf/dispatch [:fb/update-prop :placeholder (.. % -target -value)])}]])
        (when (contains? #{:input :textarea :select :number :date :radio} typ)
          [antd/form-item {:label "必填"}
           [antd/switch {:checked (:required props false)
                         :onChange #(rf/dispatch [:fb/update-prop :required %])}]])
        (when (= typ :textarea)
          [antd/form-item {:label "行数"}
           [antd/input {:type "number" :value (:rows props 4)
                        :on-change #(rf/dispatch [:fb/update-prop :rows (js/parseInt (.. % -target -value) 10)])}]])
        (when (= typ :number)
          [:<>
           [antd/form-item {:label "最小值"}
            [antd/input {:type "number" :value (:min props 0)
                         :on-change #(rf/dispatch [:fb/update-prop :min (js/parseInt (.. % -target -value) 10)])}]]
           [antd/form-item {:label "最大值"}
            [antd/input {:type "number" :value (:max props 99999)
                         :on-change #(rf/dispatch [:fb/update-prop :max (js/parseInt (.. % -target -value) 10)])}]]])
        [antd/button {:type "primary" :danger true :size "small" :block true
                      :onClick #(rf/dispatch [:fb/remove-item (:id selected-item)])}
         "删除此组件"]]])))

;; ─── 组件面板 ──────────────────────────────────────────────────────

(defn- palette-panel []
  [:div {:style {:padding 16 :borderRight "1px solid var(--ant-color-border-secondary, #f0f0f0)" :minWidth 200}}
   [:h4 {:style {:margin "0 0 12px 0"}} "组件面板"]
   (for [comp component-palette]
     ^{:key (:type comp)}
     [:div {:draggable true
            :style {:padding "10px 16px" :margin "0 0 8px 0" :background "#fafafa"
                    :border "1px solid var(--ant-color-border-secondary, #e8e8e8)" :borderRadius 4 :cursor "grab"
                    :userSelect "none" :fontSize 13}
            :on-drag-start (fn [e]
                             (set! (.-dataTransfer (.-dataTransfer e)) "text/plain")
                             (.setData (.-dataTransfer e) "text/plain" (str (:type comp))))}
      [:span {:style {:color "#666"}} (:label comp)]])])

;; ─── 设计画布 ──────────────────────────────────────────────────────

(defn- design-canvas []
  (let [items @(rf/subscribe [:fb/items])
        selected-id @(rf/subscribe [:fb/selected-id])]
    [:div {:style {:flex 1 :padding 16 :minHeight 400
                   :background "var(--ant-color-bg-container, #fff)"}
           :on-drag-over (fn [e] (.preventDefault e))
           :on-drop (fn [e]
                      (.preventDefault e)
                      (let [comp-type (keyword (.getData (.-dataTransfer e) "text/plain"))
                            comp (first (filter #(= (:type %) comp-type) component-palette))]
                        (when comp
                          (rf/dispatch [:fb/add-item comp]))))}
     (if (empty? items)
       [:div {:style {:textAlign "center" :padding 64 :color "#ccc" :border "2px dashed #f0f0f0" :borderRadius 8}}
        "拖拽左侧组件到此处"]
       (for [item items]
         ^{:key (:id item)}
         [:div {:style {:padding "8px 12px" :margin "0 0 8px 0"
                        :border (if (= selected-id (:id item)) "2px solid var(--ant-color-primary, #1677ff)" "1px solid var(--ant-color-border-secondary, #e8e8e8)")
                        :borderRadius 4 :cursor "pointer" :display "flex" :alignItems "center"
                        :transition "border 0.2s" :background (if (= selected-id (:id item)) "#e6f4ff" "#fff")}
                :on-click #(rf/dispatch [:fb/select-item (:id item)])}
          [:span {:style {:marginRight 8 :color "#999"}} [antd/button {:type "text" :size "small" :icon (r/as-element [:> DragOutlined])}]]
          [:span {:style {:marginRight 12 :color "#666" :fontSize 12 :fontFamily "monospace"}}
           (str "[" (name (:type item)) "]")]
          [:span {:style {:fontSize 14 :flex 1}} (or (:label (:props item)) "(未命名)")]
          [antd/tag (case (:type item)
                      :input "Input" :textarea "TextArea" :number "Number"
                      :select "Select" :radio "Radio" :date "DatePicker"
                      :switch "Switch" :upload "Upload" (name (:type item)))]
          [antd/button {:type "text" :danger true :size "small"
                        :icon (r/as-element [:> DeleteOutlined])
                        :on-click (fn [e] (.stopPropagation e) (rf/dispatch [:fb/remove-item (:id item)]))}]]))]))

;; ─── 代码预览弹窗 ────────────────────────────────────────────────────

(defn- code-preview-modal []
  (let [visible? @(rf/subscribe [:fb/code-visible?])
        items @(rf/subscribe [:fb/items])
        code (when (seq items) (generate-hiccup items))]
    [antd/modal {:title "生成 Hiccup 代码" :open visible? :width 700
                 :onCancel #(rf/dispatch [:fb/toggle-code])
                 :footer (r/as-element
                          [:div {:style {:display "flex" :justifyContent "space-between"}}
                           [antd/button {:icon (r/as-element [:> CopyOutlined])
                                         :onClick #(js/navigator.clipboard.writeText code)}
                            "复制代码"]
                           [antd/button {:onClick #(rf/dispatch [:fb/toggle-code])} "关闭"]])}
     [:pre {:style {:background "#1e1e1e" :color "#d4d4d4" :padding 16 :borderRadius 4
                    :maxHeight 500 :overflow "auto" :fontSize 13 :lineHeight 1.6}}
      (if code code "请先添加表单组件")]]))

;; ─── 主页面 ──────────────────────────────────────────────────────

(defn form-builder-page []
  (let [items @(rf/subscribe [:fb/items])
        selected-id @(rf/subscribe [:fb/selected-id])
        selected-item (first (filter #(= (:id %) selected-id) items))]
    (fn []
      [:div
       [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center" :marginBottom 16}}
        [antd/space
         [antd/button {:icon (r/as-element [:> EyeOutlined])}
          "预览"]
         [antd/button {:icon (r/as-element [:> CodeOutlined])
                       :onClick #(rf/dispatch [:fb/toggle-code])
                       :disabled (empty? items)}
          "生成代码"]
         [antd/button {:icon (r/as-element [:> ClearOutlined])
                       :onClick #(rf/dispatch [:fb/clear])}
          "清空"]]]
       [:div {:style {:display "flex" :border "1px solid var(--ant-color-border-secondary, #f0f0f0)" :borderRadius 8 :overflow "hidden"}}
        [palette-panel]
        [design-canvas]
        [prop-editor selected-item]]
       [code-preview-modal]])))
