(ns com.ruoyi.frontend.components.bpmn-modeler
  "bpmn-js 流程建模器封装（Reagent hook 组件）。
   通过 index.html 引入 UMD 构建，使用全局 BpmnJS，避免 shadow-cljs 打包 bpmn-js。"
  (:require
   [reagent.hooks :as hooks]))

(defn- empty-bpmn
  "空白 BPMN 定义（含一个 start 事件 + 结束事件）。"
  []
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
       "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
       " xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""
       " xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""
       " xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""
       " id=\"Definitions_1\" targetNamespace=\"http://bpmn.io/schema/bpmn\">"
       "<bpmn:process id=\"Process_1\" isExecutable=\"true\">"
       "<bpmn:startEvent id=\"StartEvent_1\"/>"
       "<bpmn:endEvent id=\"EndEvent_1\"/>"
       "</bpmn:process>"
       "</bpmn:definitions>"))

(defn- bpmn-js
  "取全局 BpmnJS 构造器。"
  []
  (or (js* "globalThis.BpmnJS")
      (aget js/window "BpmnJS")))

(defn bpmn-modeler
  "渲染 bpmn-js 建模器。参数: {:xml 现有BPMN :modeler-atom 存实例的atom :on-error fn}"
  [{:keys [xml modeler-atom on-error]}]
  (let [container-ref (hooks/use-ref nil)]
    (hooks/use-effect
     (fn []
       (let [el (.-current container-ref)
             BpmnJS (bpmn-js)]
         (when (and el BpmnJS)
           (let [^js m (BpmnJS. #js {:container el :keyboard #js {:bindTo js/window}})]
             (reset! modeler-atom m)
             (let [source (if (seq xml) xml (empty-bpmn))]
               (-> (.importXML m source)
                   (.catch (fn [err]
                             (when on-error (on-error (str "导入BPMN失败: " (.-message err))))))))
             (fn []
               (reset! modeler-atom nil)
               (.destroy m))))))
     [xml])
    [:div {:ref container-ref :style {:height "620px" :width "100%" :border "1px solid #dcdfe6"
                                       :borderRadius 4 :background "#fff"}}]))

(defn save-bpmn!
  "保存当前流程图，回调返回 XML 字符串。"
  [^js modeler on-saved on-error]
  (when modeler
    (-> (.saveXML modeler #js {:format true})
        (.then (fn [result] (on-saved (.-xml result))))
        (.catch (fn [err] (when on-error (on-error (str "保存BPMN失败: " (.-message err)))))))))
