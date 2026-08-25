(ns com.ruoyi.frontend.components.form-render
  "动态表单渲染器 —— 把 @form-create 风格 conf/fields 渲染为 antd 表单（预览/发起/详情复用）。
   field: {:type :field :title :value :props :validate}
   type: input/textarea/number/date/time/radio/checkbox/select/switch/rate/user/dept
   options: {:disabled? :values :on-change}（values 覆盖默认值，on-change 接收 {field value}）"
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]))

(def ^:private default-props
  {"input" {:placeholder "请输入"} "textarea" {:placeholder "请输入" :rows 3}
   "number" {:placeholder "请输入数字"} "date" {:placeholder "请选择日期"}
   "time" {:placeholder "请选择时间"} "select" {:placeholder "请选择"}
   "user" {:placeholder "请选择用户"} "dept" {:placeholder "请选择部门"}})

(defn- render-options
  "渲染 options 序列（label/value）。"
  [opts mode]
  (doall
   (for [o (or opts [])]
     ^{:key (str (:value o) (:label o))}
     (case mode
       :select [antd/select-option {:value (:value o)} (:label o)]
       :radio [antd/radio {:value (:value o)} (:label o)]
       :checkbox [antd/checkbox {:value (:value o)} (:label o)]))))

(defn render-field
  "渲染单个表单字段控件（不含 Form.Item）。"
  [f disabled? value on-change]
  (let [props (merge (get default-props (:type f) {}) (:props f))
        opts (or (:options f) (get props :options))
        change (fn [v] (when on-change (on-change (:field f) v)))]
    (case (:type f)
      "textarea"
      [antd/text-area {:value (or value "") :disabled disabled?
                       :placeholder (:placeholder props) :rows (or (:rows props) 3)
                       :onChange (fn [e] (change (-> e .-target .-value)))}]
      "number"
      [antd/input-number {:style {:width "100%"} :value value :disabled disabled?
                          :min (:min props) :max (:max props) :step (or (:step props) 1)
                          :placeholder (:placeholder props) :onChange change}]
      "radio"
      [antd/radio-group {:value value :disabled disabled? :onChange (fn [e] (change (-> e .-target .-value)))}
       (render-options opts :radio)]
      "checkbox"
      [antd/checkbox-group {:value value :disabled disabled? :onChange change}
       (render-options opts :checkbox)]
      "select"
      [antd/select {:style {:width "100%"} :value value :disabled disabled?
                    :allowClear true :placeholder (:placeholder props)
                    :mode (when (:multiple props) "multiple") :onChange change}
       (render-options opts :select)]
      "switch"
      [antd/switch {:checked (boolean value) :disabled disabled? :onChange change}]
      "rate"
      [antd/rate {:value value :disabled disabled? :onChange change}]
      "user"
      [antd/select {:style {:width "100%"} :value value :disabled disabled?
                    :allowClear true :placeholder (:placeholder props) :onChange change}
       (doall (for [u (or (:user-options props) [])]
                ^{:key (:value u)} [antd/select-option {:value (:value u)} (:label u)]))]
      "dept"
      [antd/select {:style {:width "100%"} :value value :disabled disabled?
                    :allowClear true :placeholder (:placeholder props) :onChange change}
       (doall (for [d (or (:dept-options props) [])]
                ^{:key (:value d)} [antd/select-option {:value (:value d)} (:label d)]))]
      ;; input / date / time（字符串值模型，date/time 用文本框）
      [antd/input {:value (or value "") :disabled disabled?
                   :placeholder (:placeholder props)
                   :onChange (fn [e] (change (-> e .-target .-value)))}])))

(defn form-render
  "渲染动态表单。schema: {:conf {} :fields [...]}。
   options: {:disabled? bool :values {field value} :on-change (fn [{field value}])}
   values/on-change 由父组件管理（可编辑模式）。"
  [{:keys [schema disabled? values on-change layout]}]
  (let [fields (or (:fields schema) [])
        vals (or values {})
        on-field-change (fn [field v]
                          (when on-change (on-change (assoc vals field v))))]
    [antd/form {:layout (or layout "vertical")}
     (doall
      (for [f fields]
        (let [field (:field f)
              value (get vals field (:value f))
              required? (some (fn [v] (:required v)) (or (:validate f) []))
              label (:title f)]
          ^{:key (or field (str "f-" (random-uuid)))}
          [antd/form-item {:label (if (str/blank? label) (:type f) label)
                           :required required?}
           (render-field f disabled? value on-field-change)])))]))
