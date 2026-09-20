(ns com.ruoyi.frontend.components.form-render
  "动态表单渲染器 —— 把 @form-create 风格 conf/fields 渲染为 antd 表单（预览/发起/详情复用）。
   field: {:type :field :title :value :props :validate}
   type: input/textarea/number/date/time/radio/checkbox/select/switch/rate/user/dept
   options: {:disabled? :values :on-change}（values 覆盖默认值，on-change 接收 {field value}）"
  (:require
    [clojure.string :as str]
    [com.ruoyi.frontend.antd :as antd]
    [com.ruoyi.frontend.api :as api]
    [reagent.core :as r]))


(def ^:private area-data
  "内置中国常用省市区数据（级联选择）。"
  [{:label "北京市" :value "110000" :children [{:label "北京市" :value "110100"}]}
   {:label "上海市" :value "310000" :children [{:label "上海市" :value "310100"}]}
   {:label "天津市" :value "120000" :children [{:label "天津市" :value "120100"}]}
   {:label "重庆市" :value "500000" :children [{:label "重庆市" :value "500100"}]}
   {:label "广东省" :value "440000" :children [{:label "广州市" :value "440100"}
                                            {:label "深圳市" :value "440300"}
                                            {:label "珠海市" :value "440400"}
                                            {:label "佛山市" :value "440600"}]}
   {:label "浙江省" :value "330000" :children [{:label "杭州市" :value "330100"}
                                            {:label "宁波市" :value "330200"}
                                            {:label "温州市" :value "330300"}]}
   {:label "江苏省" :value "320000" :children [{:label "南京市" :value "320100"}
                                            {:label "苏州市" :value "320500"}
                                            {:label "无锡市" :value "320200"}]}
   {:label "四川省" :value "510000" :children [{:label "成都市" :value "510100"}
                                            {:label "绵阳市" :value "510700"}]}
   {:label "湖北省" :value "420000" :children [{:label "武汉市" :value "420100"}
                                            {:label "宜昌市" :value "420500"}]}
   {:label "湖南省" :value "430000" :children [{:label "长沙市" :value "430100"}
                                            {:label "株洲市" :value "430200"}]}
   {:label "福建省" :value "350000" :children [{:label "福州市" :value "350100"}
                                            {:label "厦门市" :value "350200"}]}
   {:label "山东省" :value "370000" :children [{:label "济南市" :value "370100"}
                                            {:label "青岛市" :value "370200"}]}])


(defn- rich-text-editor
  "轻量富文本编辑器（contenteditable + execCommand，兼容 React 19）。"
  [{:keys [value on-change]}]
  (r/with-let [el (r/atom nil)
               exec (fn [cmd]
                      (when @el
                        (.focus @el)
                        (if (= cmd "createLink")
                          (let [url (js/prompt "输入链接地址" "https://")]
                            (when url (js/document.execCommand cmd false url)))
                          (if (= cmd "formatBlock")
                            (js/document.execCommand cmd false "p")
                            (js/document.execCommand cmd false nil)))
                        (when on-change (on-change (.-innerHTML @el)))))
               buttons [["bold" "B" "700"] ["italic" "I" "400" "italic"]
                        ["underline" "U" "400" "underline"]
                        ["insertUnorderedList" "• 列表" "400" ""]
                        ["insertOrderedList" "1. 列表" "400" ""]
                        ["createLink" "链接" "400" ""]
                        ["formatBlock" "段落" "400" ""]
                        ["removeFormat" "清除" "400" ""]]]
              [:div {:style {:border "1px solid #d9d9d9" :borderRadius 6 :overflow "hidden"}}
               [:div {:style {:padding "4px 6px" :background "#fafafa" :borderBottom "1px solid #d9d9d9"
                              :display "flex" :gap 2 :flexWrap "wrap"}}
                (doall
                  (for [[cmd title weight style] buttons]
                    ^{:key cmd}
                    [:span {:title title
                            :style {:display "inline-block" :padding "1px 8px" :cursor "pointer"
                                    :fontWeight (js/parseInt weight)
                                    :fontStyle (if (= style "italic") "italic" "normal")
                                    :textDecoration (if (= style "underline") "underline" "none")
                                    :borderRadius 4}
                            :on-mouse-down (fn [e] (.preventDefault e))
                            :on-click #(exec cmd)}
                     title]))]
               [:div {:ref #(reset! el %)
                      :contentEditable true
                      :dangerouslySetInnerHTML {:__html (or value "")}
                      :style {:minHeight 120 :padding 8 :fontSize 13 :outline "none"}
                      :onInput (fn [e] (when on-change (on-change (.-innerHTML (.-currentTarget e)))))}]]))


(def ^:private default-props
  {"input" {:placeholder "请输入"} "textarea" {:placeholder "请输入" :rows 3}
   "number" {:placeholder "请输入数字"} "date" {:placeholder "请选择日期"}
   "time" {:placeholder "请选择时间"} "select" {:placeholder "请选择"}
   "date-range" {:placeholder "开始日期"} "datetime" {:placeholder "如 2026-01-01 12:00"}
   "user" {:placeholder "请选择用户"} "dept" {:placeholder "请选择部门"}
   "slider" {:min 0 :max 100 :step 1} "cascader" {:placeholder "请选择"} "tree-select" {:placeholder "请选择"}})


(defn- field-title
  "字段标题：兼容 :title（设计器标准）与 :label（旧数据）。"
  [f]
  (or (:title f) (:label f) ""))


(defn- render-divider
  "分割线字段：antd Divider + 标题。"
  [f]
  [:div {:style {:margin "4px 0"}}
   [antd/divider {:orientation "left" :plain true :style {:fontSize 14 :fontWeight 600 :color "#303133"}}
    (field-title f)]])


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
      "editor"
      (if disabled?
        [:div {:style {:padding 8 :border "1px solid #d9d9d9" :borderRadius 6 :minHeight 120
                       :background "#f5f5f5" :fontSize 13}}
         (when value [:span {:dangerouslySetInnerHTML {:__html value}}])]
        [rich-text-editor {:value value :on-change change}])
      "area"
      [antd/cascader {:style {:width "100%"} :value value :disabled disabled?
                      :allowClear true :placeholder "请选择省/市"
                      :options (clj->js area-data) :onChange change}]
      "subform"
      (let [rows (if (coll? value) value (if (seq value) [value] []))
            sub-fields (or (get-in f [:props :sub-fields]) [])
            render-sub (fn [sf v on-c]
                         (case (:type sf)
                           "number" [antd/input-number {:style {:width "100%"} :size "small" :value v
                                                        :onChange on-c}]
                           "date" [antd/input {:size "small" :value (or v "") :placeholder "日期"
                                               :onChange (fn [e] (on-c (-> e .-target .-value)))}]
                           "select" [antd/select {:size "small" :style {:width "100%"} :value v
                                                  :onChange on-c}
                                     (render-options (or (:options sf) []) :select)]
                           [antd/input {:size "small" :value (or v "") :placeholder (:title sf)
                                        :onChange (fn [e] (on-c (-> e .-target .-value)))}]))]
        [:div
         (doall
           (for [[ri row] (map-indexed vector rows)]
             ^{:key ri}
             [:div {:style {:display "flex" :gap 6 :marginBottom 6 :alignItems "center"
                            :background "#fafafa" :padding "6px 8px" :borderRadius 6}}
              (doall
                (for [sf sub-fields]
                  ^{:key (:field sf)}
                  [:div {:style {:flex 1}}
                   [:div {:style {:fontSize 11 :color "#909399" :marginBottom 2}} (:title sf)]
                   (render-sub sf (get row (:field sf))
                               (fn [v] (change (assoc-in rows [ri (:field sf)] v))))]))
              [antd/button {:size "small" :type "text" :danger true
                            :on-click #(change (vec (concat (subvec rows 0 ri) (subvec rows (inc ri)))))}
               "✕"]]))
         (when (seq sub-fields)
           [antd/button {:size "small" :type "dashed" :block true
                         :on-click #(change (conj rows {}))}
            "+ 添加一行"])])
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
                      label (field-title f)
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
