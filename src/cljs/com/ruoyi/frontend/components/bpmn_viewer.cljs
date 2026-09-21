(ns com.ruoyi.frontend.components.bpmn-viewer
  "bpmn-js 只读流程视图 + 进度高亮（复用全局 BpmnJS）。"
  (:require
    [reagent.hooks :as hooks]))


(defn- bpmn-js
  []
  (or (js* "globalThis.BpmnJS")
      (aget js/window "BpmnJS")))


(defn bpmn-viewer
  "渲染流程图并高亮节点.
   参数: {:xml BPMN :active-ids 进行中节点 :completed-ids 已完成节点 :on-error fn}"
  [{:keys [xml active-ids completed-ids on-error]}]
  (let [container-ref (hooks/use-ref nil)]
    (hooks/use-effect
      (fn []
        (let [el (.-current container-ref)
              BpmnJS (bpmn-js)]
          (if (and el BpmnJS (seq xml))
            (let [viewer (BpmnJS. #js {:container el})]
              (-> (.importXML viewer xml)
                  (.then (fn []
                           (let [^js canvas (.get viewer "canvas")
                                 ^js registry (.get viewer "elementRegistry")
                                 add-marker (fn [id cls]
                                              ;; 只对图中存在的元素加高亮,避免 id 不存在时报 markers 错误
                                              (when (and id cls (.get registry id))
                                                (.addMarker canvas id cls)))]
                             (doseq [id completed-ids] (add-marker id "highlight-done"))
                             (doseq [id active-ids] (add-marker id "highlight-active"))
                             (.zoom canvas "fit-viewport"))))
                  (.catch (fn [err]
                            (when on-error (on-error (str "渲染流程图失败: " (.-message err)))))))
              (fn []
                (.destroy viewer)))
            (fn []))))
      [xml active-ids completed-ids])
    [:div {:ref container-ref :style {:height "560px" :width "100%" :border "1px solid #dcdfe6"
                                      :borderRadius 4 :background "#fff"}}]))
