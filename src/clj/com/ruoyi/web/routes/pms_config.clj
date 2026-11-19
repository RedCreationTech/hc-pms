(ns com.ruoyi.web.routes.pms-config
  "平台级模板/编码规则/经营目标/费用池/封期配置路由, 以及跨项目待办/检索/组合看板/经营目标达成/报表指标的只读路由, 挂载在已认证的 /api/pms 内."
  (:require
    [clojure.data.csv :as csv]
    [com.ruoyi.domain.pms.approval-chain :as chain]
    [com.ruoyi.domain.pms.config :as config]
    [com.ruoyi.domain.pms.external-catalog :as external]
    [com.ruoyi.domain.pms.finance-pool :as pool]
    [com.ruoyi.domain.pms.portfolio :as portfolio]
    [com.ruoyi.domain.pms.report :as report]
    [com.ruoyi.domain.pms.scan :as scan]
    [com.ruoyi.domain.pms.service :as pms]
    [com.ruoyi.web.controllers.pms-http :as http]
    [ring.util.response :as response]))


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


(defn- report-csv
  "G16 报表导出: 与页面同一求值结果, 列定义也取同一份 columns, 保证导出范围与界面一致.
    领域错误仍返回 JSON 信封 (错误不得伪装成空的 CSV), 其余以带 BOM 的 UTF-8 CSV 下载."
  [svc request]
  (try
    (let [data (report/reports svc (or (:actor request) (pms/actor svc (:identity request))))
          output (java.io.StringWriter.)
          header (mapv :title (:columns data))]
      (csv/write-csv output (cons header (mapv report/csv-cells (:rows data))) :separator \, :quote (char 34))
      (-> (response/response (str "\uFEFF" output))
          (response/status 200)
          (response/header "Content-Type" "text/csv; charset=utf-8")
          (response/header "Content-Disposition" "attachment; filename=pms-report.csv")))
    (catch clojure.lang.ExceptionInfo e
      (http/reply (:status (ex-data e)) (.getMessage e) nil))
    (catch Exception _
      (http/reply 500 "服务暂时不可用,请稍后重试" nil))))


(defn config-routes
  "返回可拼接的配置与跨项目只读路由."
  [svc]
  [["/external-interfaces" {:get {:handler (fn [request] (http/invoke svc request external/catalog))}}]
   ["/coding-rules/next" {:get {:handler (partial next-code svc)}}]
   ["/todo" {:get {:handler (fn [request] (http/invoke svc request portfolio/todo))}}]
   ;; 可配置审批链: 当前生效策略, 审批详情, 逐级决定, 项目内审批进度
   ["/approval-policies" {:get {:handler (fn [request] (http/invoke svc request chain/active-policies))}}]
   ["/approvals/:flow_id" {:get {:handler (fn [request] (http/invoke svc request #(chain/flow %1 %2 (http/param request :flow_id))))}}]
   ["/approvals/:flow_id/decide" {:post {:handler (fn [request] (http/invoke svc request #(chain/decide! %1 %2 (http/param request :flow_id) (:body-params request))))}}]
   ["/projects/:id/approvals" {:get {:handler (fn [request]
                                                (let [params (:query-params request)]
                                                  (http/invoke svc request #(chain/project-flows %1 %2 (http/project-id request)
                                                                                                 {:biz_type (not-empty (get params "biz_type"))
                                                                                                  :biz_id (not-empty (get params "biz_id"))}))))}}]
   ["/scan" {:post {:handler (fn [request]
                               (http/invoke svc request (fn [svc actor]
                                                          (scan/run-all! svc actor (or (get-in request [:body-params :date]) (str (java.time.LocalDate/now)))))))}}]
   ["/search" {:get {:handler (partial search svc)}}]
   ["/portfolio" {:get {:handler (fn [request] (http/invoke svc request portfolio/portfolio))}}]
   ["/targets/board" {:get {:handler (fn [request] (http/invoke svc request portfolio/targets))}}]
   ;; G16 报表: 已发布指标定义求值, 导出与页面共用同一求值与列定义
   ["/reports" {:get {:handler (fn [request] (http/invoke svc request report/reports))
                      :perms ["pms:dashboard:query" "pms:project:list"]}}]
   ["/reports/export" {:get {:handler (partial report-csv svc)
                             :perms ["pms:dashboard:query" "pms:project:list"]}}]
   ["/config/:kind" {:get {:handler (partial listing svc)} :post {:handler (partial create svc)}}]
   ["/config/:kind/import" {:post {:handler (partial import-catalog svc)}}]
   ["/config/:kind/:config_id/revisions" {:post {:handler (partial (versioned config/revise!) svc)}}]
   ["/config/:kind/:config_id/publish" {:post {:handler (partial (versioned config/publish!) svc)}}]
   ["/config/:kind/:config_id/retire" {:post {:handler (partial (versioned config/retire!) svc)}}]
   ["/config/:kind/:config_id/preview" {:get {:handler (fn [request] (http/invoke svc request #(pool/preview %1 %2 (http/param request :config_id))))}}]
   ["/config/:kind/:config_id/allocate" {:post {:handler (fn [request] (http/invoke svc request #(pool/allocate! %1 %2 (http/param request :config_id) (:body-params request))))}}]])
