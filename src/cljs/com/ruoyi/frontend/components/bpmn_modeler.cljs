(ns com.ruoyi.frontend.components.bpmn-modeler
  "bpmn-js 流程建模器封装（Reagent hook 组件）。
   通过 index.html 引入 UMD 构建，使用全局 BpmnJS，避免 shadow-cljs 打包 bpmn-js。
   容器 div 用命令式创建并挂到 host 下，规避 React 对 ref 容器 reconciliation 造成 bpmn-js 内部错误。"
  (:require
   [reagent.hooks :as hooks]))

(defn- empty-bpmn
  "空白 BPMN 定义（含 start + 结束事件，并带 BPMNDI 图元，bpmn-js 才能渲染）。"
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
       "<bpmndi:BPMNDiagram id=\"BPMNDiagram_1\"><bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"Process_1\">"
       "<bpmndi:BPMNShape id=\"s_di\" bpmnElement=\"StartEvent_1\"><dc:Bounds x=\"180\" y=\"90\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>"
       "<bpmndi:BPMNShape id=\"e_di\" bpmnElement=\"EndEvent_1\"><dc:Bounds x=\"380\" y=\"90\" width=\"36\" height=\"36\"/></bpmndi:BPMNShape>"
       "</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>"
       "</bpmn:definitions>"))

(defn- bpmn-js
  "取全局 BpmnJS 构造器。"
  []
  (or (js* "globalThis.BpmnJS")
      (aget js/window "BpmnJS")))

(defn- import-with-fallback
  "导入 BPMN，失败（如缺 BPMNDI）时回退空白画布。返回 promise。"
  [^js modeler xml on-error]
  (let [p (.importXML modeler xml)]
    (.catch p
            (fn [err]
              (when on-error
                (on-error (str "导入BPMN失败，已载入空白画布: " (.-message err))))
              (.importXML modeler (empty-bpmn))))))

(defn bpmn-modeler
  "渲染 bpmn-js 建模器。参数: {:xml 现有BPMN :modeler-ref use-ref(存实例) :on-error fn}"
  [{:keys [xml modeler-ref on-error]}]
  (let [host-ref (hooks/use-ref nil)]
    (hooks/use-effect
     (fn []
       (let [host (.-current host-ref)
             BpmnJS (bpmn-js)]
         (if (and host BpmnJS (seq xml))
           (let [container (js/document.createElement "div")
                 _ (set! (.-style.height container) "620px")
                 _ (set! (.-style.width container) "100%")
                 _ (.appendChild host container)
                 m (BpmnJS. #js {:container container})]
             (set! (.-current modeler-ref) m)
             (import-with-fallback m xml on-error)
             (fn []
               (set! (.-current modeler-ref) nil)
               (.destroy m)
               (.removeChild host container)))
           (fn []))))
     [xml])
    [:div {:ref host-ref :style {:height "620px" :width "100%"
                                 :border "1px solid #dcdfe6" :borderRadius 4 :background "#fff"}}]))

(defn save-bpmn!
  "保存当前流程图，回调返回 XML 字符串。"
  [^js modeler on-saved on-error]
  (when modeler
    (let [p (.saveXML modeler #js {:format true})]
      (-> (.then p (fn [result] (on-saved (.-xml result))))
          (.catch (fn [err] (when on-error (on-error (str "保存BPMN失败: " (.-message err))))))))))
