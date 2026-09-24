(ns com.ruoyi.web.routes.pms-finance
  "挂载在/api/pms下的财务版本, 成本和独立工时业务路由."
  (:require [com.ruoyi.domain.pms.finance :as finance]
            [com.ruoyi.domain.pms.finance-allocation :as allocation]
            [com.ruoyi.domain.pms.finance-cost :as cost]
            [com.ruoyi.domain.pms.finance-time :as time]
            [com.ruoyi.web.controllers.pms-finance :as controller]))

(defn- query
  "构造安全的查询handler."
  [svc f]
  {:handler (partial controller/query svc f)})

(defn- command
  "构造已确定参数的命令handler."
  [svc f keys]
  {:handler (partial controller/command svc f keys)})

(defn finance-routes
  "返回可拼接的费用与工时接口."
  [svc]
  [["/projects/:id/finance" {:get (query svc finance/overview)}]
   ["/projects/:id/time-entries" {:get (query svc finance/times) :post (command svc time/submit! [])}]
   ["/projects/:id/time-entries/:entry_id/review" {:post (command svc time/review! [:entry_id])}]
   ["/projects/:id/time-entries/:entry_id/correct" {:post (command svc time/correct! [:entry_id])}]
   ["/projects/:id/cost-versions" {:post (command svc cost/create! [])}]
   ["/projects/:id/cost-versions/:cost_id/entries" {:post (command svc cost/add-entry! [:cost_id])}]
   ["/projects/:id/cost-versions/:cost_id/entries/:entry_id" {:delete (command svc cost/delete-entry! [:cost_id :entry_id])}]
   ["/projects/:id/cost-versions/:cost_id/submit" {:post (command svc cost/submit! [:cost_id])}]
   ["/projects/:id/cost-versions/:cost_id/review" {:post (command svc cost/review! [:cost_id])}]
   ["/projects/:id/cost-versions/:cost_id/revise" {:post (command svc cost/revise! [:cost_id])}]
   ["/projects/:id/cost-versions/:cost_id/cancel" {:post (command svc cost/cancel! [:cost_id])}]
   ["/projects/:id/cost-versions/:cost_id/allocate" {:post (command svc allocation/allocate! [:cost_id])}]])
