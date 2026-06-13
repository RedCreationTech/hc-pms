(ns com.ruoyi.frontend.components.form-field
  "统一表单字段渲染组件。"
  (:require
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]))

(defn- form-input [{:keys [name label rules placeholder disabled full?]}]
  [antd/form-item {:name name :label label :rules rules
                   :wrapperCol (when full? {:span 24})}
   [antd/input {:placeholder placeholder :disabled disabled
                :style {:width "100%"}}]])

(defn- form-textarea [{:keys [name label rules rows placeholder disabled full?]}]
  [antd/form-item {:name name :label label :rules rules
                   :wrapperCol (when full? {:span 24})}
   [antd/text-area {:placeholder placeholder
                    :rows (or rows 2)
                    :disabled disabled
                    :style {:width "100%"}}]])

(defn- form-select [{:keys [name label rules options placeholder disabled allow-clear?]}]
  [antd/form-item {:name name :label label :rules rules}
   [antd/select {:placeholder placeholder :disabled disabled :allowClear allow-clear?}
    (for [opt options]
      ^{:key opt} [antd/select-option {:value opt} opt])]])

(defn- form-multi-select [{:keys [name label rules options placeholder disabled]}]
  [antd/form-item {:name name :label label :rules rules}
   [antd/select {:mode "multiple" :placeholder placeholder :disabled disabled}
    (for [opt options]
      ^{:key opt} [antd/select-option {:value opt} opt])]])

(defn- form-status [{:keys [name label rules disabled]}]
  [antd/form-item {:name name :label label :rules rules}
   [antd/select {:disabled disabled}
    [antd/select-option {:value "0"} "正常"]
    [antd/select-option {:value "1"} "停用"]]])

(defn- form-unit [{:keys [name label rules unit placeholder disabled]}]
  [antd/form-item {:name name :label label :rules rules}
   [:div {:style {:display "flex"}}
    [antd/input {:placeholder placeholder :disabled disabled
                 :style {:borderRadius "4px 0 0 4px"}}]
    [:span {:style {:height 32
                    :minWidth 48
                    :padding "0 11px"
                    :display "inline-flex"
                    :alignItems "center"
                    :justifyContent "center"
                    :border "1px solid var(--ant-color-border, #d9d9d9)"
                    :borderLeft 0
                    :borderRadius "0 4px 4px 0"
                    :background "var(--ant-color-fill-quaternary, #fafafa)"
                    :color "var(--ant-color-text-secondary, #595959)"}}
     unit]]])

(defn- form-date [{:keys [name label rules placeholder disabled]}]
  [antd/form-item {:name name :label label :rules rules}
   [antd/date-picker {:placeholder placeholder :disabled disabled
                      :style {:width "100%"}}]])

(defn form-field [{:keys [type full?] :as props}]
  (let [props (cond-> props
                full? (assoc :full? true))]
    (case type
      :input [form-input props]
      :textarea [form-textarea props]
      :select [form-select props]
      :multi-select [form-multi-select props]
      :status [form-status props]
      :unit [form-unit props]
      :date [form-date props]
      :full-input [form-input (assoc props :full? true)]
      [form-input props])))
