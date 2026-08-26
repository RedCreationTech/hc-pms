(ns com.ruoyi.frontend.components.form-designer
  "轻量表单设计器（对齐 vben @form-create 的 conf/fields JSON 格式）：
   左：组件库（点击添加）/ 中：画布（字段卡片，可选中/排序/复制/删除）/
   右：选中字段属性配置（标题/字段名/占位符/必填/默认值/选项）。
   保存输出 {:conf {:form {:labelWidth 100}} :fields [...]}，供渲染器复用。"
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   ["@ant-design/icons" :refer [UpOutlined DownOutlined CopyOutlined DeleteOutlined PlusOutlined]]
   [com.ruoyi.frontend.antd :as antd]
   [com.ruoyi.frontend.components.form-render :as fr]))

(def ^:private component-types
  [{:type "input" :label "单行文本"} {:type "textarea" :label "多行文本"}
   {:type "number" :label "数字"} {:type "date" :label "日期"}
   {:type "date-range" :label "日期范围"} {:type "datetime" :label "日期时间"}
   {:type "time" :label "时间"} {:type "radio" :label "单选框"}
   {:type "checkbox" :label "多选框"} {:type "select" :label "下拉选择"}
   {:type "switch" :label "开关"} {:type "rate" :label "评分"}
   {:type "user" :label "用户选择"} {:type "dept" :label "部门选择"}
   {:type "slider" :label "滑块"} {:type "cascader" :label "级联选择"}
   {:type "tree-select" :label "部门树选择"} {:type "dict-select" :label "字典选择"}
   {:type "upload" :label "文件上传"} {:type "upload-image" :label "图片上传"}
   {:type "divider" :label "分割线"}])

(def ^:private options-types #{"radio" "checkbox" "select" "cascader" "dict-select"})

(defn- new-field
  "按类型生成默认字段。"
  [type idx]
  (let [default-title (get (into {} (map (juxt :type :label)) component-types) type type)]
    {:type type
     :field (str type "_" idx)
     :title default-title
     :value (if (= type "switch") false "")
     :props {}
     :options (when (options-types type) [{:label "选项一" :value "1"} {:label "选项二" :value "2"}])
     :validate []}))

(defn- field-value
  "字段当前值（含默认值）。"
  [f]
  (get f :value (if (= (:type f) "switch") false "")))

(defn- field-caret
  "画布字段卡片（支持拖拽排序）。"
  [f idx selected? on-select on-op drag-idx]
  [:div.bpm-fd-card {:class (when selected? "active")
                     :draggable true
                     :on-drag-start (fn [e] (.setData (.-dataTransfer e) "text/plain" (str "card:" idx))
                                     (reset! drag-idx idx))
                     :on-drag-over (fn [e] (.preventDefault e))
                     :on-drop (fn [e]
                                (.preventDefault e)
                                (.stopPropagation e)
                                (let [from @drag-idx]
                                  (when (and (some? from) (not= from idx))
                                    (on-op :move from idx)))
                                (reset! drag-idx nil))
                     :on-click #(on-select idx)}
   [:div.bpm-fd-card-head
    [:span.bpm-fd-card-title (:title f)]
    [:span.bpm-fd-card-type (get (into {} (map (juxt :type :label)) component-types) (:type f))]
    [:div.bpm-fd-card-ops
     [:span.bpm-fd-op {:title "上移" :on-click (fn [e] (.stopPropagation e) (on-op :up idx))} "↑"]
     [:span.bpm-fd-op {:title "下移" :on-click (fn [e] (.stopPropagation e) (on-op :down idx))} "↓"]
     [:span.bpm-fd-op {:title "复制" :on-click (fn [e] (.stopPropagation e) (on-op :copy idx))} "⧉"]
     [:span.bpm-fd-op.del {:title "删除" :on-click (fn [e] (.stopPropagation e) (on-op :del idx))} "✕"]]]
   [:div.bpm-fd-card-body
    (fr/render-field f true (field-value f) nil)]])

(defn- options-editor
  "选项列表编辑器（单选/多选/下拉）。"
  [fields idx]
  (let [opts (or (get-in @fields [idx :options]) [])]
    [:div
     (doall
      (for [[i o] (map-indexed vector opts)]
        ^{:key i}
        [:div {:style {:display "flex" :gap 6 :marginBottom 6}}
         [antd/input {:size "small" :style {:flex 1} :placeholder "显示名"
                      :value (:label o)
                      :onChange (fn [e] (swap! fields assoc-in [idx :options i :label] (-> e .-target .-value)))}]
         [antd/input {:size "small" :style {:flex 1} :placeholder "值"
                      :value (:value o)
                      :onChange (fn [e] (swap! fields assoc-in [idx :options i :value] (-> e .-target .-value)))}]
         [antd/button {:size "small" :type "text" :danger true
                       :on-click #(swap! fields update idx (fn [f] (update f :options (fn [os] (vec (concat (subvec os 0 i) (subvec os (inc i))))))))}
          "✕"]]
        ))
     [antd/button {:size "small" :block true
                   :on-click #(swap! fields assoc-in [idx :options]
                                     (conj (or (get-in @fields [idx :options]) [])
                                           {:label (str "选项" (inc (count (get-in @fields [idx :options])))) :value (str (inc (count (get-in @fields [idx :options]))))}))}
      "＋ 添加选项"]]))

(defn- props-panel
  "右侧属性配置。"
  [fields idx]
  (let [f (get @fields idx)]
    (if (nil? f)
      [:div {:style {:color "#999" :textAlign "center" :paddingTop 40 :fontSize 13}}
       "点击画布字段编辑属性"]
      [:div
       [:div.bpm-f-label "组件类型"] [:div {:style {:color "#666" :marginBottom 8}} (:type f)]
       [:div.bpm-f-label "标题"]
       [antd/input {:size "small" :value (:title f)
                    :onChange (fn [e] (swap! fields assoc-in [idx :title] (-> e .-target .-value)))}]
       [:div.bpm-f-label {:style {:marginTop 10}} "字段名(field)"]
       [antd/input {:size "small" :value (:field f)
                    :onChange (fn [e] (swap! fields assoc-in [idx :field] (-> e .-target .-value)))}]
       (when-not (options-types (:type f))
         [:div
          [:div.bpm-f-label {:style {:marginTop 10}} "占位符"]
          [antd/input {:size "small" :value (get-in f [:props :placeholder])
                       :onChange (fn [e] (swap! fields assoc-in [idx :props :placeholder] (-> e .-target .-value)))}]])
       [:div.bpm-f-label {:style {:marginTop 10}} "必填"]
       [antd/switch {:size "small" :checked (some :required (:validate f))
                     :onChange (fn [v] (swap! fields assoc-in [idx :validate]
                                              (if v [{:required true :message (str "请填写" (:title f))}] [])))}]
       (when (#{"input" "textarea"} (:type f))
         [:div {:style {:display "flex" :gap 8 :marginTop 10}}
          [:div {:style {:flex 1}} [:div.bpm-f-label "最小长度"]
           [antd/input-number {:size "small" :style {:width "100%"} :min 0
                               :value (get (first (filter :min (:validate f))) :min)
                               :onChange (fn [v]
                                           (let [others (remove :min (:validate f))]
                                             (swap! fields assoc-in [idx :validate]
                                                    (if (nil? v) (vec others)
                                                        (conj (vec others) {:min (or v 0)})))))}]]
          [:div {:style {:flex 1}} [:div.bpm-f-label "最大长度"]
           [antd/input-number {:size "small" :style {:width "100%"} :min 0
                               :value (get (first (filter :max (:validate f))) :max)
                               :onChange (fn [v]
                                           (let [others (remove :max (:validate f))]
                                             (swap! fields assoc-in [idx :validate]
                                                    (if (nil? v) (vec others)
                                                        (conj (vec others) {:max (or v 0)})))))}]]])
       [:div {:style {:marginTop 10}}
        [:div.bpm-f-label "校验规则"]
        [antd/select {:size "small" :style {:width "100%"} :allowClear true
                      :value (or (get (first (filter :pattern (:validate f))) :pattern) "")
                      :placeholder "选择校验规则(可自定义)"
                      :onChange (fn [v]
                                  (let [pattern (or v "")
                                        others (remove :pattern (:validate f))]
                                    (swap! fields assoc-in [idx :validate]
                                           (if (seq pattern)
                                             (conj (vec others)
                                                   {:pattern pattern
                                                    :message (get {"^1[3-9]\\d{9}$" "手机号格式不正确"
                                                                   "^[\\w.+-]+@[\\w-]+\\.[\\w.]+$" "邮箱格式不正确"
                                                                   "^\\d{6}$" "请输入6位数字"}
                                                                 pattern "格式不正确")})
                                             (vec others)))))}]
         [antd/select-option {:value ""} "无"]
         [antd/select-option {:value "^1[3-9]\\d{9}$"} "手机号"]
         [antd/select-option {:value "^[\\w.+-]+@[\\w-]+\\.[\\w.]+$"} "邮箱"]
         [antd/select-option {:value "^\\d{6}$"} "6位数字"]
         [antd/select-option {:value "custom"} "自定义正则..."]]
       [:div {:style {:display "flex" :gap 24 :marginTop 10}}
        [:div
         [:div.bpm-f-label "禁用"]
         [antd/switch {:size "small" :checked (get-in f [:props :disabled])
                       :onChange (fn [v] (swap! fields assoc-in [idx :props :disabled] v))}]]
        [:div
         [:div.bpm-f-label "隐藏"]
         [antd/switch {:size "small" :checked (get-in f [:props :hidden])
                       :onChange (fn [v] (swap! fields assoc-in [idx :props :hidden] v))}]]]
       [:div {:style {:marginTop 10}}
        [:div.bpm-f-label "占列宽(栅格)"]
        [antd/select {:size "small" :style {:width "100%"} :value (or (get-in f [:props :col-span]) 24)
                      :onChange #(swap! fields assoc-in [idx :props :col-span] (or % 24))}
         [antd/select-option {:value 24} "整行(24)"]
         [antd/select-option {:value 12} "半行(12)"]
         [antd/select-option {:value 8} "1/3行(8)"]
         [antd/select-option {:value 16} "2/3行(16)"]]]
       [:div {:style {:marginTop 10}}
        [:div.bpm-f-label "变更时设置字段(事件)"]
        [:div {:style {:display "flex" :gap 6}}
         [antd/select {:size "small" :style {:width "50%"} :allowClear true
                       :value (get-in f [:props :on-change :set-field])
                       :placeholder "选择目标字段"
                       :onChange (fn [v]
                                   (swap! fields assoc-in [idx :props :on-change :set-field] (or v "")))}
          (doall (for [other @fields
                       :when (not= (:field other) (:field f))
                       :when (not= (:type other) "divider")]
                   ^{:key (:field other)}
                   [antd/select-option {:value (:field other)} (:title other)]))]
         [antd/input {:size "small" :style {:width "50%"} :value (get-in f [:props :on-change :set-value])
                      :placeholder "设置值"
                      :onChange (fn [e] (swap! fields assoc-in [idx :props :on-change :set-value] (-> e .-target .-value)))}]]]
       [:div {:style {:marginTop 10}}
        [:div.bpm-f-label "显示条件(联动)"]
        [:div {:style {:display "flex" :gap 6}}
         [antd/select {:size "small" :style {:width "50%"} :allowClear true
                       :value (get-in f [:props :relation :field])
                       :placeholder "选择字段"
                       :onChange (fn [v]
                                   (swap! fields assoc-in [idx :props :relation :field] (or v "")))}
          (doall (for [other @fields
                       :when (not= (:field other) (:field f))
                       :when (not= (:type other) "divider")]
                   ^{:key (:field other)}
                   [antd/select-option {:value (:field other)} (:title other)]))]
         [antd/input {:size "small" :style {:width "50%"} :value (get-in f [:props :relation :value])
                      :placeholder "等于值(如 出差)"
                      :onChange (fn [e] (swap! fields assoc-in [idx :props :relation :value] (-> e .-target .-value)))}]]]
       [:div.bpm-f-label {:style {:marginTop 10}} "默认值"]
       [fr/render-field f false (field-value f)
        (fn [_ v] (swap! fields assoc-in [idx :value] v))]
       (when (= (:type f) "slider")
         [:div {:style {:display "flex" :gap 8 :marginTop 10}}
          [:div {:style {:flex 1}} [:div.bpm-f-label "最小值"]
           [antd/input-number {:size "small" :style {:width "100%"} :value (get-in f [:props :min] 0)
                               :onChange #(swap! fields assoc-in [idx :props :min] (or % 0))}]]
          [:div {:style {:flex 1}} [:div.bpm-f-label "最大值"]
           [antd/input-number {:size "small" :style {:width "100%"} :value (get-in f [:props :max] 100)
                               :onChange #(swap! fields assoc-in [idx :props :max] (or % 100))}]]])
       (when (= (:type f) "dict-select")
         [:div {:style {:marginTop 10}}
          [:div.bpm-f-label "字典类型"]
          [antd/input {:size "small" :value (get-in f [:props :dict-type]) :placeholder "如 sys_normal_disable"
                       :onChange (fn [e] (swap! fields assoc-in [idx :props :dict-type] (-> e .-target .-value)))}]])
       (when (options-types (:type f))
         [:div
          [:div.bpm-f-label {:style {:marginTop 12}} "选项设置"]
          (options-editor fields idx)])])))

(defn form-designer
  "表单设计器。props: {:schema {:form-name :conf :fields} :on-save (fn [schema])}。"
  [{:keys [schema on-save]}]
  (r/with-let [fields (r/atom [])
               selected (r/atom nil)
               form-name (r/atom "")
               drag-idx (r/atom nil)
               _ (when schema
                   (reset! fields (or (:fields schema) []))
                   (reset! form-name (or (:form-name schema) "")))
               select-field (fn [idx] (reset! selected idx))
               field-op (fn [op idx & [to-idx]]
                          (let [fs @fields]
                            (case op
                              :move (let [item (get fs idx)
                                          without (vec (concat (subvec fs 0 idx) (subvec fs (inc idx))))
                                          to (if (< idx to-idx) (dec to-idx) to-idx)]
                                      (swap! fields (fn [_] (vec (concat (subvec without 0 to) [item] (subvec without to)))))
                                      (reset! selected to))
                              :up (when (> idx 0)
                                    (swap! fields assoc idx (get fs (dec idx)) (dec idx) (get fs idx))
                                    (reset! selected (dec idx)))
                              :down (when (< idx (dec (count fs)))
                                      (swap! fields assoc idx (get fs (inc idx)) (inc idx) (get fs idx))
                                      (reset! selected (inc idx)))
                              :copy (let [new (assoc (get fs idx) :field (str (:field (get fs idx)) "_c"))]
                                      (swap! fields (fn [xs] (vec (concat (subvec xs 0 (inc idx)) [new] (subvec xs (inc idx)))))))
                              :del (swap! fields (fn [xs] (vec (concat (subvec xs 0 idx) (subvec xs (inc idx))))))
                              (reset! selected (when (seq fs) (min (or idx 0) (dec (count fs))))))))
               save! (fn [] (when on-save
                              (on-save {:form-name @form-name
                                        :conf {:form {:labelWidth 100}}
                                        :fields @fields})))]
    [:div.bpm-fd
     [:div.bpm-fd-header
      [:div {:style {:fontWeight 600}} "表单设计器"]
      [:div {:style {:display "flex" :gap 8 :alignItems "center"}}
       [antd/input {:style {:width 200} :size "small" :placeholder "表单名称"
                    :value @form-name :onChange (fn [e] (reset! form-name (-> e .-target .-value)))}]
       [antd/button {:size "small" :type "primary" :on-click save!} "保存表单"]]]
     [:div.bpm-fd-body
      ;; 左：组件库
      [:div.bpm-fd-lib
       [:div.bpm-fd-lib-title "组件库"]
       (doall
        (for [{:keys [type label]} component-types]
          ^{:key type}
          [:div.bpm-fd-lib-item
           {:draggable true
            :on-drag-start (fn [e] (.setData (.-dataTransfer e) "text/plain" type))
            :on-click #(do (swap! fields conj (new-field type (inc (count @fields))))
                           (reset! selected (dec (count @fields))))}
           label]))]
      ;; 中：画布
      [:div.bpm-fd-canvas {:on-drag-over (fn [e] (.preventDefault e))
                        :on-drop (fn [e]
                                   (.preventDefault e)
                                   (let [type (.getData (.-dataTransfer e) "text/plain")]
                                     (when (and (seq type) (not (str/starts-with? type "card:")))
                                       (swap! fields conj (new-field type (inc (count @fields))))
                                       (reset! selected (dec (count @fields))))))}
       (if (seq @fields)
         (doall
          (for [[idx f] (map-indexed vector @fields)]
            ^{:key (str (:field f) idx)}
            (field-caret f idx (= idx @selected) select-field field-op drag-idx)))
         [:div {:style {:color "#bbb" :textAlign "center" :paddingTop 60}}
          "从左侧组件库点击添加字段"])]
      ;; 右：属性配置
      [:div.bpm-fd-props
       (props-panel fields @selected)]]]))
