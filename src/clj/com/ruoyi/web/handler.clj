(ns com.ruoyi.web.handler
  (:require
   [clojure.string :as str]
   [com.ruoyi.web.middleware.core :as middleware]
   [integrant.core :as ig]
   [ring.util.response :as response]
   [reitit.ring :as ring]
   [reitit.swagger-ui :as swagger-ui]))

(defn- spa-not-found-handler
  "SPA fallback: 非 API 路径一律返回 index.html，让前端路由处理。"
  [request]
  (if (and (string? (:uri request))
           (not (str/starts-with? (:uri request) "/api/")))
    (-> (response/resource-response "public/index.html")
        (response/content-type "text/html; charset=utf-8"))
    (-> {:status 404 :body "Not found"}
        (response/content-type "text/plain"))))

(defonce ^:private ring-handler-atom (atom nil))

(defn- wrap-no-cache-static
  "非 API 的 GET 响应统一加 Cache-Control: no-cache。
   静态资源响应没有显式缓存指令时浏览器会做启发式缓存，shadow-cljs watch
   重启后可能继续用旧的 app.js，触发 Stale Output 警告。no-cache 强制浏览器
   每次重新校验（未变化时返回 304，开销很小）。"
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (if (and (some? resp)
               (= :get (:request-method request))
               (not (str/starts-with? (or (:uri request) "") "/api/")))
        (response/header resp "Cache-Control" "no-cache")
        resp))))

(defn ring-handler
  "动态 Ring handler 入口。追踪功能可以通过更新 atom 来切换实际处理函数。"
  [request]
  (@ring-handler-atom request))

(defn set-ring-handler!
  "供调用追踪模块动态替换实际 handler。"
  [f]
  (reset! ring-handler-atom f))

(defn current-ring-handler
  "返回当前实际的 Ring handler（不是动态入口）。"
  []
  @ring-handler-atom)

(defmethod ig/init-key :handler/ring
  [_ {:keys [router api-path] :as opts}]
  (let [actual (ring/ring-handler
                (router)
                (ring/routes
                  ;; Handle trailing slash in routes - add it + redirect to it
                  ;; https://github.com/metosin/reitit/blob/master/doc/ring/slash_handler.md
                 (ring/redirect-trailing-slash-handler)
                 (ring/create-resource-handler {:path "/"})
                 (when (some? api-path)
                   (swagger-ui/create-swagger-ui-handler {:path api-path
                                                          :url  (str api-path "/swagger.json")}))
                  ;; SPA fallback: 所有非 API 404 返回 index.html
                 (ring/create-default-handler
                  {:not-found spa-not-found-handler
                   :method-not-allowed
                   (constantly (-> {:status 405, :body "Not allowed"}
                                   (response/content-type "text/plain")))
                   :not-acceptable
                   (constantly (-> {:status 406, :body "Not acceptable"}
                                   (response/content-type "text/plain")))}))
                {:middleware [(middleware/wrap-base opts)]})
        actual (wrap-no-cache-static actual)]
    (reset! ring-handler-atom actual)
    ring-handler))

(defmethod ig/init-key :router/routes
  [_ {:keys [routes]}]
  (mapv (fn [route]
          (if (fn? route)
            (route)
            route))
        routes))

(defmethod ig/init-key :router/core
  [_ {:keys [routes env] :as opts}]
  (if (= env :dev)
    #(ring/router ["" opts routes] {:reitit.router/sequential true :conflicts nil})
    (constantly (ring/router ["" opts routes] {:reitit.router/sequential true :conflicts nil}))))
