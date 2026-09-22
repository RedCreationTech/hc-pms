(ns com.ruoyi.web.controllers.pms-governance
  "治理工作台的安全HTTP边界与真实文本附件下载."
  (:require [com.ruoyi.domain.pms.governance :as governance]
            [com.ruoyi.web.controllers.pms-http :as http]
            [ring.util.response :as response])
  (:import [java.net URLEncoder]))

(defn workspace
  "读取已授权项目的治理只读模型."
  [svc request]
  (http/invoke svc request #(governance/workspace %1 %2 (http/project-id request))))

(defn command
  "解析业务命令并交由明确的类型化领域服务."
  [svc resource action request]
  (http/invoke svc request
    #(governance/command! %1 %2 (http/project-id request) resource action
                           (http/param request :record_id) (:body-params request))))

(defn preview
  "预检需求CSV并返回行号和错误详情."
  [svc request]
  (http/invoke svc request #(governance/preview %1 %2 (http/project-id request) (:body-params request))))

(defn content
  "读取统一JSON信封中的实际文档版本正文和摘要."
  [svc request]
  (http/invoke svc request
    #(governance/document-content %1 %2 (http/project-id request) (http/param request :record_id))))

(defn download
  "下载经项目授权验证的持久化文本文件版本."
  [svc request]
  (let [result (http/invoke svc request
                 #(governance/document-content %1 %2 (http/project-id request)
                                               (http/param request :record_id)))]
    (if-not (= 200 (:status result)) result
      (let [document (get-in result [:body :data])]
        (-> (response/response (:content document))
            (response/content-type (:content_type document))
            (response/header "X-Content-SHA256" (:sha256 document))
            (response/header "Content-Disposition"
                             (str "attachment; filename*=UTF-8''" (.replace (URLEncoder/encode (:filename document) "UTF-8") "+" "%20"))))))))
