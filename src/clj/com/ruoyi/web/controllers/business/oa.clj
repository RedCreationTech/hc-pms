(ns com.ruoyi.web.controllers.business.oa
  "OA 协同办公控制器（日程/会议）。"
  (:require
   [com.ruoyi.domain.business.oa :as oa]
   [ring.util.response :as response]))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail
  ([msg] (fail 500 msg))
  ([code msg]
   (-> (response/response {:code code :msg msg})
       (response/content-type "application/json"))))

(defn- wrap-err [f]
  (try (f) (catch Exception e (fail (.getMessage e)))))

(defn- parse-id [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))

(defn- current-user [request]
  (get-in request [:identity :user-name]))

;; ── 日程 ──
(defn list-calendars [{:keys [oa-service]} request]
  (wrap-err #(ok (oa/calendar-list oa-service (:query-params request)))))
(defn get-calendar [{:keys [oa-service]} request]
  (wrap-err #(if-let [c (oa/calendar-get oa-service (parse-id request))]
               (ok c) (fail 404 "日程不存在"))))
(defn create-calendar [{:keys [oa-service]} request]
  (wrap-err #(do (oa/calendar-create oa-service (:body-params request) (current-user request)) (ok nil))))
(defn update-calendar [{:keys [oa-service]} request]
  (wrap-err #(do (oa/calendar-update oa-service (assoc (:body-params request) :calendar_id (parse-id request)) (current-user request)) (ok nil))))
(defn delete-calendar [{:keys [oa-service]} request]
  (wrap-err #(do (oa/calendar-delete oa-service (parse-id request)) (ok nil))))

;; ── 会议 ──
(defn list-meetings [{:keys [oa-service]} request]
  (wrap-err #(ok (oa/meeting-list oa-service (:query-params request)))))
(defn get-meeting [{:keys [oa-service]} request]
  (wrap-err #(if-let [m (oa/meeting-get oa-service (parse-id request))]
               (ok m) (fail 404 "会议不存在"))))
(defn create-meeting [{:keys [oa-service]} request]
  (wrap-err #(do (oa/meeting-create oa-service (:body-params request) (current-user request)) (ok nil))))
(defn update-meeting [{:keys [oa-service]} request]
  (wrap-err #(do (oa/meeting-update oa-service (assoc (:body-params request) :meeting_id (parse-id request)) (current-user request)) (ok nil))))
(defn delete-meeting [{:keys [oa-service]} request]
  (wrap-err #(do (oa/meeting-delete oa-service (parse-id request)) (ok nil))))
