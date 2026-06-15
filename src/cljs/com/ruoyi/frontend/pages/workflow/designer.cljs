(ns com.ruoyi.frontend.pages.workflow.designer
  "BPMN 流程设计器页面 — 使用 bpmn-js 实现可视化编辑。"
  (:require [reagent.core :as r]
            [reagent.hooks :as hooks]
            [re-frame.core :as rf]
            ["@ant-design/icons" :refer [SaveOutlined UploadOutlined PlusOutlined]]
            ["bpmn-js/lib/Modeler" :as BpmnModeler]
            [com.ruoyi.frontend.antd :as antd]))

;; ─── bpmn-js 实例管理 ──────────────────────────────────────────────

(defonce modeler-instance (atom nil))

(def default-bpmn "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"
                  xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\"
                  xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"
                  xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\"
                  id=\"Definitions_1\"
                  targetNamespace=\"http://bpmn.io/schema/bpmn\"
                  exporter=\"RuoYi Clojure\"
                  exporterVersion=\"1.0\">
  <bpmn:process id=\"Process_1\" isExecutable=\"true\">
    <bpmn:startEvent id=\"StartEvent_1\" name=\"开始\">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id=\"UserTask_1\" name=\"审批节点\">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id=\"EndEvent_1\" name=\"结束\">
      <bpmn:incoming>Flow_2</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id=\"Flow_1\" sourceRef=\"StartEvent_1\" targetRef=\"UserTask_1\" />
    <bpmn:sequenceFlow id=\"Flow_2\" sourceRef=\"UserTask_1\" targetRef=\"EndEvent_1\" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">
    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"Process_1\">
      <bpmndi:BPMNShape id=\"_BPMNShape_StartEvent_2\" bpmnElement=\"StartEvent_1\">
        <dc:Bounds x=\"179\" y=\"99\" width=\"36\" height=\"36\" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x=\"185\" y=\"142\" width=\"24\" height=\"14\" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id=\"UserTask_1_di\" bpmnElement=\"UserTask_1\">
        <dc:Bounds x=\"270\" y=\"77\" width=\"100\" height=\"80\" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id=\"EndEvent_1_di\" bpmnElement=\"EndEvent_1\">
        <dc:Bounds x=\"432\" y=\"99\" width=\"36\" height=\"36\" />
        <bpmndi:BPMNLabel>
          <dc:Bounds x=\"438\" y=\"142\" width=\"24\" height=\"14\" />
        </bpmndi:BPMNLabel>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id=\"Flow_1_di\" bpmnElement=\"Flow_1\">
        <di:waypoint x=\"215\" y=\"117\" />
        <di:waypoint x=\"270\" y=\"117\" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id=\"Flow_2_di\" bpmnElement=\"Flow_2\">
        <di:waypoint x=\"370\" y=\"117\" />
        <di:waypoint x=\"432\" y=\"117\" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>")

;; ─── 主组件 ──────────────────────────────────────────────────────

(defn bpmn-designer-page []
  (let [container-ref (r/atom nil)
        modeler-ref (r/atom nil)
        [name set-name!] (hooks/use-state "新流程")
        [saving? set-saving!] (hooks/use-state false)]
    
    (hooks/use-effect
     (fn []
       (when @container-ref
         (let [m (BpmnModeler. #js {:container @container-ref})]
           (reset! modeler-ref m)
           (-> m
               (.importXML default-bpmn)
               (.then (fn []
                        (let [canvas (.get m "canvas")]
                          (.zoom canvas "fit-viewport"))))
               (.catch (fn [err]
                         (js/console.error "BPMN import error:" err))))))
       #(when @modeler-ref
          (.destroy @modeler-ref)
          (reset! modeler-ref nil)))
     [])
    
    [:div {:style {:height "100vh" :display "flex" :flexDirection "column"}}
     ;; 顶部工具栏
     [:div {:style {:padding "8px 16px" :background "#fff" :borderBottom "1px solid #f0f0f0"
                    :display "flex" :justifyContent "space-between" :alignItems "center"}}
      [:div {:style {:display "flex" :alignItems "center" :gap 12}}
       [antd/input {:value name
                    :onChange #(set-name! (.. % -target -value))
                    :placeholder "流程名称"
                    :style {:width 200}}]
       [antd/button {:type "primary"
                     :icon (r/as-element [:> SaveOutlined])
                     :loading saving?
                     :onClick (fn []
                                (when @modeler-ref
                                  (set-saving! true)
                                  (-> (.saveXML @modeler-ref #js {:format true})
                                      (.then (fn [result]
                                               (let [xml (.-xml result)]
                                                 (rf/dispatch [:workflow/deploy {:name name :xml xml}])
                                                 (set-saving! false))))
                                      (.catch (fn [err]
                                                (js/console.error err)
                                                (set-saving! false))))))}
        "部署"]
       [antd/button {:icon (r/as-element [:> UploadOutlined])
                     :onClick (fn []
                                (let [input (.createElement js/document "input")]
                                  (set! (.-type input) "file")
                                  (set! (.-accept input) ".bpmn,.xml")
                                  (set! (.-onchange input)
                                        (fn [e]
                                          (let [file (-> e .-target .-files (aget 0))
                                                reader (js/FileReader.)]
                                            (set! (.-onload reader)
                                                  (fn [ev]
                                                    (let [xml (-> ev .-target .-result)]
                                                      (when @modeler-ref
                                                        (-> @modeler-ref
                                                            (.importXML xml)
                                                            (.then (fn []
                                                                     (set-name! (.-name file)))))))))
                                            (.readAsText reader file))))
                                  (.click input)))}
        "打开文件"]]
      [antd/button {:type "dashed"
                    :icon (r/as-element [:> PlusOutlined])
                    :onClick (fn []
                               (when @modeler-ref
                                 (-> @modeler-ref
                                     (.importXML default-bpmn)
                                     (.then (fn []
                                              (let [canvas (.get @modeler-ref "canvas")]
                                                (.zoom canvas "fit-viewport")))))))}
       "新建"]]
     
     ;; BPMN 画布
     [:div {:ref #(reset! container-ref %)
            :style {:flex 1 :background "#fafafa"}}]]))
