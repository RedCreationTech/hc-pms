(ns com.ruoyi.domain.business.oa
  "OA 协同办公领域服务(日程/会议)."
  (:require
    [integrant.core :as ig]))


(defmethod ig/init-key :app.business/oa-service
  [_ {:keys [query-fn db]}]
  {:query-fn query-fn :db db})


(defn- optional-id
  "把可选的数字参数转成整数; 缺失或非数字时返回 nil.
   查询串由调用方自由传入, 非数字值不应让列表请求抛 NumberFormatException."
  [v]
  (when v
    (try (Integer/parseInt (str v))
         (catch NumberFormatException _ nil))))


(defn- page-params
  [params]
  (let [page (or (optional-id (get params :page)) 1)
        size (or (optional-id (get params :size)) 10)]
    {:page page :size size :offset (* (dec page) size)}))


;; ── 日程 ──────────────────────────────────────────────────────────────
(defn calendar-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:user_id (optional-id (get params :user_id))
           :page_size size :offset offset}]
    {:rows (query-fn :oa/calendar-list p)
     :total (:total (query-fn :oa/calendar-count p))}))


(defn calendar-get
  [{:keys [query-fn]} id]
  (query-fn :oa/find-calendar-by-id {:calendar_id id}))


(defn calendar-create
  [{:keys [query-fn]} params user]
  (query-fn :oa/insert-calendar
            {:title (or (:title params) "")
             :content (or (:content params) "")
             :start_time (:start_time params) :end_time (:end_time params)
             :all_day (or (:all_day params) "0") :color (or (:color params) "")
             :user_id (or (:user_id params) 0) :create_by (or user "")}))


(defn calendar-update
  [{:keys [query-fn]} params user]
  (query-fn :oa/update-calendar
            {:calendar_id (:calendar_id params) :title (or (:title params) "")
             :content (or (:content params) "") :start_time (:start_time params)
             :end_time (:end_time params) :all_day (or (:all_day params) "0")
             :color (or (:color params) "") :user_id (or (:user_id params) 0)
             :update_by (or user "")}))


(defn calendar-delete
  [{:keys [query-fn]} id]
  (query-fn :oa/delete-calendar {:calendar_id id}))


;; ── 会议 ──────────────────────────────────────────────────────────────
(defn meeting-list
  [{:keys [query-fn]} params]
  (let [{:keys [offset size]} (page-params params)
        p {:subject (get params :subject) :page_size size :offset offset}]
    {:rows (query-fn :oa/meeting-list p)
     :total (:total (query-fn :oa/meeting-count p))}))


(defn meeting-get
  [{:keys [query-fn]} id]
  (query-fn :oa/find-meeting-by-id {:meeting_id id}))


(defn meeting-create
  [{:keys [query-fn]} params user]
  (query-fn :oa/insert-meeting
            {:subject (or (:subject params) "")
             :location (or (:location params) "")
             :start_time (:start_time params) :end_time (:end_time params)
             :participants (or (:participants params) "")
             :content (or (:content params) "")
             :status (or (:status params) "1") :create_by (or user "")}))


(defn meeting-update
  [{:keys [query-fn]} params user]
  (query-fn :oa/update-meeting
            {:meeting_id (:meeting_id params) :subject (or (:subject params) "")
             :location (or (:location params) "") :start_time (:start_time params)
             :end_time (:end_time params) :participants (or (:participants params) "")
             :content (or (:content params) "") :status (or (:status params) "1")
             :update_by (or user "")}))


(defn meeting-delete
  [{:keys [query-fn]} id]
  (query-fn :oa/delete-meeting {:meeting_id id}))
