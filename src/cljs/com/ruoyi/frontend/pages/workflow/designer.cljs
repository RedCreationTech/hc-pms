(ns com.ruoyi.frontend.pages.workflow.designer
  "BPMN 流程设计器页面 — 使用 bpmn-js CDN 实现可视化编辑。"
  (:require [reagent.core :as r]
            [reagent.hooks :as hooks]
            [re-frame.core :as rf]
            ["@ant-design/icons" :refer [SaveOutlined UploadOutlined PlusOutlined]]
            [com.ruoyi.frontend.antd :as antd]))

;; bpmn-js 通过 CDN 加载到 window.BpmnModeler

(def default-bpmn "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"
                  xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\"
                  xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"
                  xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\"
                  id=\"Definitions_1\"
                  targetNamespace=\"http://bpmn.io/schema/bpmn\">
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
      <bpmndi:BPMNShape id=\"StartEvent_1_di\" bpmnElement=\"StartEvent_1\">
        <dc:Bounds x=\"179\" y=\"99\" width=\"36\" height=\"36\" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id=\"UserTask_1_di\" bpmnElement=\"UserTask_1\">
        <dc:Bounds x=\"270\" y=\"77\" width=\"100\" height=\"80\" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id=\"EndEvent_1_di\" bpmnElement=\"EndEvent_1\">
        <dc:Bounds x=\"432\" y=\"99\" width=\"36\" height=\"36\" />
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

(defn- zoom-fit [^js modeler]
  (-> modeler (.get "canvas") (.zoom "fit-viewport")))

(defn bpmn-designer-page []
  (let [container-ref (r/atom nil)
        modeler-ref (r/atom nil)
        [name set-name!] (hooks/use-state "新流程")
        [saving? set-saving!] (hooks/use-state false)]
    (hooks/use-effect
     (fn []
       (when-let [el @container-ref]
         (when-let [BpmnModeler (.-BpmnModeler js/window)]
           (let [^js m (BpmnModeler. #js {:container el})]
             (reset! modeler-ref m)
             (-> m (.importXML default-bpmn)
                 (.then (fn [] (zoom-fit m)))
                 (.catch (fn [err] (js/console.error "BPMN error:" err)))))))
       #(when-let [^js m @modeler-ref]
          (.destroy m)
          (reset! modeler-ref nil)))
     [])
    [:div {:style {:height "100vh" :display "flex" :flexDirection "column"}}
     [:div {:style {:padding "8px 16px" :background "#fff" :borderBottom "1px solid #f0f0f0"
                    :display "flex" :justifyContent "space-between" :alignItems "center"}}
      [:div {:style {:display "flex" :alignItems "center" :gap 12}}
       [antd/input {:value name :placeholder "流程名称" :style {:width 200}
                    :onChange #(set-name! (.. % -target -value))}]
       [antd/button {:type "primary" :loading saving?
                     :icon (r/as-element [:> SaveOutlined])
                     :onClick (fn []
                                (when-let [^js m @modeler-ref]
                                  (set-saving! true)
                                  (-> (.saveXML m #js {:format true})
                                      (.then (fn [r]
                                               (rf/dispatch [:workflow/deploy {:name name :xml (.-xml r)}])
                                               (set-saving! false)))
                                      (.catch (fn [e] (js/console.error e) (set-saving! false))))))}
        "部署"]
       [antd/button {:icon (r/as-element [:> UploadOutlined])
                     :onClick (fn []
                                (let [inp (.createElement js/document "input")]
                                  (set! (.-type inp) "file")
                                  (set! (.-accept inp) ".bpmn,.xml")
                                  (set! (.-onchange inp)
                                        (fn [e]
                                          (when-let [f (-> e .-target .-files (aget 0))]
                                            (let [r (js/FileReader.)]
                                              (set! (.-onload r)
                                                    (fn [^js ev]
                                                      (when-let [^js m @modeler-ref]
                                                        (-> m (.importXML (-> ev .-target .-result))
                                                            (.then #(set-name! (.-name f)))))))
                                              (.readAsText r f)))))
                                  (.click inp)))}
        "打开文件"]]
      [antd/button {:type "dashed"
                    :icon (r/as-element [:> PlusOutlined])
                    :onClick (fn []
                               (when-let [^js m @modeler-ref]
                                 (-> m (.importXML default-bpmn)
                                     (.then (fn [] (zoom-fit m))))))}
       "新建"]]
     [:div {:ref #(reset! container-ref %)
            :style {:flex 1 :background "#fafafa"}}]]))
