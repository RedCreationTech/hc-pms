(ns com.ruoyi.frontend.components.form-render
  "动态表单渲染器 —— 把 @form-create 风格 conf/fields 渲染为 antd 表单（预览/发起/详情复用）。
   field: {:type :field :title :value :props :validate}
   type: input/textarea/number/date/time/radio/checkbox/select/switch/rate/user/dept
   options: {:disabled? :values :on-change}（values 覆盖默认值，on-change 接收 {field value}）"
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.api :as api]))

(def ^:private default-props
  {"input" {:placeholder "请输入"} "textarea" {:placeholder "请输入" :rows 3}
   "number" {:placeholder "请输入数字"} "date" {:placeholder "请选择日期"}
   "time" {:placeholder "请选择时间"} "select" {:placeholder "请选择"}
   "date-range" {:placeholder "开始日期"} "datetime" {:placeholder "如 2026-01-01 12:00"}
   "user" {:placeholder "请选择用户"} "dept" {:placeholder "请选择部门"}
   "slider" {:min 0 :max 100 :step 1} "cascader" {:placeholder "请选择"} "tree-select" {:placeholder "请选择"}})

(defn- render-divider
  "分割线字段：antd Divider + 标题。"
  [f]
  [:div {:style {:margin "4px 0"}}
   [antd/divider {:orientation "left" :plain true :style {:fontSize 14 :fontWeight 600 :color "#303133"}}
    (or (:title f) "")]])

(defn- flatten-tree-options
  "树节点 → 拉平 select 选项（带层级缩进）。"
  [nodes depth]
  (mapcat (fn [n]
            (cons {:label (str (apply str (repeat depth "　")) (:title n)) :value (:value n)}
                  (flatten-tree-options (:children n) (inc depth))))
          (or nodes [])))

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
        disabled? (or disabled? (get props :disabled))
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
      "date-range"
      (let [parts (str/split (or (str value) "") #"~")
            start (nth parts 0 "")
            end (nth parts 1 "")]
        [:div {:style {:display "flex" :gap 6}}
         [antd/input {:style {:flex 1} :value start :disabled disabled?
                      :placeholder (:placeholder props)
                      :onChange (fn [e] (change (str (-> e .-target .-value) "~" end)))}]
         [antd/input {:style {:flex 1} :value end :disabled disabled?
                      :placeholder "结束日期"
                      :onChange (fn [e] (change (str start "~" (-> e .-target .-value))))}]])
      "slider"
      [antd/slider {:value (or value (:min props 0)) :disabled disabled?
                    :min (:min props 0) :max (or (:max props) 100) :step (or (:step props) 1)
                    :onChange change}]
      "cascader"
      [antd/cascader {:style {:width "100%"} :value value :disabled disabled?
                      :allowClear true :placeholder (:placeholder props)
                      :options (clj->js (or opts [])) :onChange change}]
      "tree-select"
      [antd/select {:style {:width "100%"} :value value :disabled disabled?
                    :allowClear true :placeholder (:placeholder props)
                    :onChange change}
       (doall (for [{:keys [label value]} (flatten-tree-options (:tree-data props) 0)]
                ^{:key value}
                [antd/select-option {:value value} label]))]
      "upload"
      (let [files (if (coll? value) value (if (seq value) [(str value)] []))
            is-img? (= (:type f) "upload-image")
            render-files (mapv (fn [u]
                                 {:uid u :name (last (str/split (str u) #"/"))
                                  :url (str u) :status "done"
                                  :thumb-url (str u)})
                               files)]
        [antd/upload {:fileList (clj->js render-files)
                      :accept (:accept props)
                      :disabled disabled?
                      :listType (if is-img? "picture-card" "text")
                      :customRequest (fn [opt]
                                       (let [fd (js/FormData.)]
                                         (.append fd "file" (.-file opt))
                                         (api/upload-file fd
                                                          (fn [res]
                                                            (when-let [url (:url (:data res))]
                                                              (change (vec (conj files url)))
                                                              (.onSuccess opt #js {})))
                                                          (fn [e] (.onError opt e)))))
                      :onRemove (fn [file]
                                  (let [u (:url (js->clj file :keywordize-keys true))]
                                    (change (vec (remove #(= u %) files)))))
                      :onChange (fn [_] nil)}])
      "dict-select"
      [antd/select {:style {:width "100%"} :value value :disabled disabled?
                    :allowClear true :placeholder (:placeholder props)
                    :onChange change}
       (render-options opts :select)]
      ;; input / date / time（字符串值模型，date/time 用文本框）
      [antd/input {:value (or value "") :disabled disabled?
                   :placeholder (:placeholder props)
                   :onChange (fn [e] (change (-> e .-target .-value)))}])))

(defn form-render
  "渲染动态表单。schema: {:conf {} :fields [...]}。
   options: {:disabled? bool :values {field value} :on-change (fn [{field value}])
             :field-permissions {field hidden|readonly|edit}}
   values/on-change 由父组件管理（可编辑模式）。"
  [{:keys [schema disabled? values on-change layout field-permissions]}]
  (let [fields (or (:fields schema) [])
        vals (or values {})
        perms (or field-permissions {})
        on-field-change (fn [f v]
                          (when on-change
                            (let [oc (get-in f [:props :on-change])
                                  base (assoc vals (:field f) v)]
                              (if (and oc (:set-field oc))
                                (on-change (assoc base (:set-field oc) (:set-value oc)))
                                (on-change base)))))]
    [antd/form {:layout (or layout "vertical")}
     [antd/row {:gutter 16}
      (doall
       (keep (fn [f]
               (let [field (:field f)
                     value (get vals field (:value f))
                     required? (some (fn [v] (:required v)) (or (:validate f) []))
                     label (:title f)
                     perm (or (get perms field) (get perms (keyword field)))
                     relation (get-in f [:props :relation])
                     relation-ok? (if (and relation (seq (:field relation)))
                                    (= (get vals (:field relation)) (:value relation))
                                    true)
                     span (or (get-in f [:props :col-span]) 24)]
                 (if (= "divider" (:type f))
                   [antd/col {:span 24} (render-divider f)]
                   (when (and relation-ok?
                              (not (or (= perm "hidden") (get-in f [:props :hidden]))))
                     (let [v (or (:validate f) [])
                           rules (cond-> []
                                   (some :required v)
                                   (conj {:required true :message (str "请填写" label)})
                                   (some :pattern v)
                                   (conj {:pattern (re-pattern (str (get (first (filter :pattern v)) :pattern)))
                                          :message (get (first (filter :pattern v)) :message (str "格式不正确"))})
                                   (some :min v)
                                   (conj {:min (get (first (filter :min v)) :min)
                                          :message (or (get (first (filter :min v)) :message)
                                                       (str "长度不能小于" (get (first (filter :min v)) :min)))})
                                   (some :max v)
                                   (conj {:max (get (first (filter :max v)) :max)
                                          :message (or (get (first (filter :max v)) :message)
                                                       (str "长度不能超过" (get (first (filter :max v)) :max)))}))]
                       ^{:key (or field (str "f-" (random-uuid)))}
                       [antd/col {:span span}
                        [antd/form-item {:label (if (str/blank? label) (:type f) label)
                                         :rules (clj->js rules)}
                         (render-field f (or disabled? (= perm "readonly")) value
                                          (fn [field v] (on-field-change f v)))]])))))
             fields))]]))