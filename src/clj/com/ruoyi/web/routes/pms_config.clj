(ns com.ruoyi.web.routes.pms-config
  "平台级模板/编码规则/经营目标/费用池/封期配置路由, 以及跨项目待办/检索/组合看板/经营目标达成的只读路由, 挂载在已认证的 /api/pms 内."
  (:require [com.ruoyi.domain.pms.config :as config]
            [com.ruoyi.domain.pms.finance-pool :as pool]
            [com.ruoyi.domain.pms.portfolio :as portfolio]
            [com.ruoyi.domain.pms.scan :as scan]
            [com.ruoyi.web.controllers.pms-http :as http]))

(defn- kind
  [request]
  (http/param request :kind))

(defn- listing
  [svc request]
  (http/invoke svc request #(config/listing %1 %2 (kind request))))

(defn- create
  [svc request]
  (http/invoke svc request #(config/create! %1 %2 (kind request) (:body-params request))))

(defn- import-catalog
  [svc request]
  (http/invoke svc request #(config/import-catalog! %1 %2 (kind request) (:body-params request))))

(defn- versioned
  "对指定配置版本执行修订/发布/退役命令."
  [f]
  (fn [svc request]
    (http/invoke svc request #(f %1 %2 (kind request) (http/param request :config_id) (:body-params request)))))

(defn- next-code
  [svc request]
  (let [params (:query-params request)]
    (http/invoke svc request #(config/next-code %1 %2 (get params "object_type") (not-empty (get params "project_id")) (not-empty (get params "type"))))))

(defn- search
  [svc request]
  (let [params (:query-params request)]
    (http/invoke svc request #(portfolio/search %1 %2 {:q (get params "q") :classification (get params "classification")}))))

(defn config-routes
  "返回可拼接的配置与跨项目只读路由."
  [svc]
  [["/coding-rules/next" {:get {:handler (partial next-code svc)}}]
   ["/todo" {:get {:handler (fn [request] (http/invoke svc request portfolio/todo))}}]
   ["/scan" {:post {:handler (fn [request] (http/invoke svc request (fn [svc actor]
                                                                       (scan/run-all! svc actor (or (get-in request [:body-params :date]) (str (java.time.LocalDate/now)))))))}}]
   ["/search" {:get {:handler (partial search svc)}}]
   ["/portfolio" {:get {:handler (fn [request] (http/invoke svc request portfolio/portfolio))}}]
   ["/targets/board" {:get {:handler (fn [request] (http/invoke svc request portfolio/targets))}}]
   ["/config/:kind" {:get {:handler (partial listing svc)} :post {:handler (partial create svc)}}]
   ["/config/:kind/import" {:post {:handler (partial import-catalog svc)}}]
   ["/config/:kind/:config_id/revisions" {:post {:handler (partial (versioned config/revise!) svc)}}]
   ["/config/:kind/:config_id/publish" {:post {:handler (partial (versioned config/publish!) svc)}}]
   ["/config/:kind/:config_id/retire" {:post {:handler (partial (versioned config/retire!) svc)}}]
   ["/config/:kind/:config_id/preview" {:get {:handler (fn [request] (http/invoke svc request #(pool/preview %1 %2 (http/param request :config_id))))}}]
   ["/config/:kind/:config_id/allocate" {:post {:handler (fn [request] (http/invoke svc request #(pool/allocate! %1 %2 (http/param request :config_id) (:body-params request))))}}]])
