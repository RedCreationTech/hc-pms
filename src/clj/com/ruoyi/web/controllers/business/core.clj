(ns com.ruoyi.web.controllers.business.core
  "工程方案与资源管理业务控制器。"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.util.response :as response]))

(def upload-root "uploads/business")

(defn- q-fn
  "获取业务服务的 query-fn，确保调用顺序与系统其他控制器一致。"
  [business-service query-name params & _]
  ((:query-fn business-service) query-name params))

(defn- ok
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail [msg]
  (-> (response/response {:code 500 :msg msg})
      (response/content-type "application/json")))

(defn- parse-int* [value default]
  (try
    (cond
      (int? value) value
      (string? value) (Integer/parseInt value)
      (nil? value) default
      :else default)
    (catch Exception _ default)))

(defn- parse-id [request]
  (parse-int* (get-in request [:path-params :id]) nil))

(defn- current-user [request]
  (or (get-in request [:identity :user-name]) ""))

(defn- blank->nil [value]
  (cond
    (nil? value) nil
    (and (string? value) (str/blank? value)) nil
    :else value))

(defn- paging [params]
  (let [page (max 1 (parse-int* (or (get params "page") (get params :page)) 1))
        size (max 1 (parse-int* (or (get params "size") (get params :size)) 10))]
    {:limit size :offset (* (dec page) size)}))

(defn- q [params key]
  (blank->nil (or (get params (name key)) (get params key))))

(defn- common-query [params keys]
  (merge (paging params)
         (into {} (map (fn [k] [k (q params k)]) keys))))

(defn- body-with-defaults [body defaults]
  (merge defaults body))

(defn- last-id [business-service]
  (get (q-fn business-service :last-insert-rowid {}) (keyword "last_insert_rowid()")))

(defn- with-create-user [params request]
  (assoc params :create_by (current-user request)))

(defn- with-update-user [params request]
  (assoc params :update_by (current-user request)))

(defn list-engineerings [{:keys [business-service]} request]
  (let [params (common-query (:query-params request) [:engineering_name :engineering_code :status])
        rows (q-fn business-service :list-engineerings params)
        total (q-fn business-service :count-engineerings params {:result-set-fn first})]
    (ok {:rows rows :total (:total total)})))

(defn get-engineering [{:keys [business-service]} request]
  (let [row (q-fn business-service :get-engineering {:id (parse-id request)} {:result-set-fn first})]
    (if row (ok row) (fail "工程不存在"))))

(defn create-engineering [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults {:engineering_code "" :description "" :status "0" :remark ""})
                     (with-create-user request))]
      (q-fn business-service :create-engineering! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

(defn update-engineering [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults {:engineering_code "" :description "" :status "0" :remark ""})
                     (assoc :id (parse-id request))
                     (with-update-user request))]
      (q-fn business-service :update-engineering! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-engineering [{:keys [business-service]} request]
  (q-fn business-service :delete-engineering! {:id (parse-id request)})
  (ok "删除成功"))

(def project-defaults
  {:engineering_id nil :engineering_name "" :project_code "" :project_address ""
   :construction_unit "" :contractor_unit "" :supervision_unit "" :design_unit ""
   :survey_unit "" :project_manager "" :project_leader "" :contact_phone ""
   :contract_period "" :start_date nil :end_date nil :building_area "" :project_cost ""
   :structure_type "" :building_floors "" :project_overview "" :extra_json "{}" :remark ""})

(defn list-projects [{:keys [business-service]} request]
  (let [params (common-query (:query-params request) [:engineering_id :project_name :project_code :construction_unit])
        rows (q-fn business-service :list-projects params)
        total (q-fn business-service :count-projects params)]
    (ok {:rows rows :total (:total total)})))

(defn get-project [{:keys [business-service]} request]
  (let [id (parse-id request)
        row (q-fn business-service :get-project {:id id} {:result-set-fn first})
        teams (q-fn business-service :list-subcontract-teams {:project_id id})
        attachments (q-fn business-service :list-attachments {:biz_type "project" :biz_id id :section_key nil :file_purpose nil})]
    (if row (ok (assoc row :teams teams :attachments attachments)) (fail "项目不存在"))))

(defn create-project [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults project-defaults)
                     (with-create-user request))]
      (q-fn business-service :create-project! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

(defn update-project [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults project-defaults)
                     (assoc :id (parse-id request))
                     (with-update-user request))]
      (q-fn business-service :update-project! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-project [{:keys [business-service]} request]
  (q-fn business-service :delete-project! {:id (parse-id request)})
  (ok "删除成功"))

(defn list-subcontract-teams [{:keys [business-service]} request]
  (ok (q-fn business-service :list-subcontract-teams {:project_id (parse-id request)})))

(defn create-subcontract-team [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults {:leader_name "" :contact_phone "" :work_scope "" :extra_json "{}" :remark ""})
                     (assoc :project_id (parse-id request))
                     (with-create-user request))]
      (q-fn business-service :create-subcontract-team! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

(defn update-subcontract-team [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults {:leader_name "" :contact_phone "" :work_scope "" :extra_json "{}" :remark ""})
                     (assoc :id (parse-int* (get-in request [:path-params :team-id]) nil))
                     (with-update-user request))]
      (q-fn business-service :update-subcontract-team! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-subcontract-team [{:keys [business-service]} request]
  (q-fn business-service :delete-subcontract-team! {:id (parse-int* (get-in request [:path-params :team-id]) nil)})
  (ok "删除成功"))

(def solution-defaults
  {:engineering_id nil :project_id nil :engineering_name "" :project_name "" :status "draft" :progress 0 :remark ""})

(defn list-solutions [{:keys [business-service]} request]
  (let [params (common-query (:query-params request) [:solution_name :engineering_id :project_id :status])
        rows (q-fn business-service :list-solutions params)
        total (q-fn business-service :count-solutions params {:result-set-fn first})]
    (ok {:rows rows :total (:total total)})))

(defn get-solution [{:keys [business-service]} request]
  (let [row (q-fn business-service :get-solution {:id (parse-id request)} {:result-set-fn first})]
    (if row (ok row) (fail "方案不存在"))))

(defn create-solution [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults solution-defaults)
                     (with-create-user request))]
      (q-fn business-service :create-solution! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

(defn update-solution [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults solution-defaults)
                     (assoc :id (parse-id request))
                     (with-update-user request))]
      (q-fn business-service :update-solution! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-solution [{:keys [business-service]} request]
  (q-fn business-service :delete-solution! {:id (parse-id request)})
  (ok "删除成功"))

(def section-titles
  {"project-info" "项目信息"
   "people-org" "人员和组织"
   "cover" "封面"
   "design-overview" "设计概况"
   "layout-plan" "平面布置图"})

(defn get-solution-section [{:keys [business-service]} request]
  (let [params {:solution_id (parse-id request)
                :section_key (get-in request [:path-params :section-key])}
        row (q-fn business-service :get-solution-section params {:result-set-fn first})]
    (ok (or row (assoc params :section_title (get section-titles (:section_key params) (:section_key params)) :content_json "{}")))))

(defn save-solution-section [{:keys [business-service]} request]
  (try
    (let [section-key (get-in request [:path-params :section-key])
          body (:body-params request)
          params {:solution_id (parse-id request)
                  :section_key section-key
                  :section_title (or (:section_title body) (get section-titles section-key section-key))
                  :content_json (or (:content_json body) (:content body) "{}")
                  :sort_order (or (:sort_order body) 0)
                  :create_by (current-user request)
                  :update_by (current-user request)
                  :remark (or (:remark body) "")}]
      (q-fn business-service :upsert-solution-section! params)
      (ok "保存成功"))
    (catch Exception e (fail (.getMessage e)))))

(def resource-defaults
  {:code "" :category "" :publish_unit "" :publish_date nil :effective_date nil
   :file_name "" :file_type "" :vector_status "pending" :tags "" :related_project_name ""
   :structured_fields "" :summary "" :content "" :status "0" :sort_order 0 :extra_json "{}" :remark ""})

(defn list-resources [{:keys [business-service]} request]
  (let [params (assoc (common-query (:query-params request) [:name :code :category :status])
                    :resource_type (get-in request [:path-params :type]))
        rows (q-fn business-service :list-resources params)
        total (q-fn business-service :count-resources params)]
    (ok {:rows rows :total (:total total)})))

(defn get-resource [{:keys [business-service]} request]
  (let [params {:id (parse-id request) :resource_type (get-in request [:path-params :type])}
        row (q-fn business-service :get-resource params)
        attachments (q-fn business-service :list-attachments {:biz_type (:resource_type params) :biz_id (:id params) :section_key nil :file_purpose nil})]
    (if row (ok (assoc row :attachments attachments)) (fail "资源不存在"))))

(defn create-resource [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          params (-> (:body-params request)
                     (body-with-defaults resource-defaults)
                     (assoc :resource_type (get-in request [:path-params :type]))
                     (with-create-user request))]
      (query-fn :create-resource! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

(defn update-resource [{:keys [business-service]} request]
  (try
    (let [params (-> (:body-params request)
                     (body-with-defaults resource-defaults)
                     (assoc :id (parse-id request)
                            :resource_type (get-in request [:path-params :type]))
                     (with-update-user request))]
      ((:query-fn business-service) :update-resource! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-resource [{:keys [business-service]} request]
  ((:query-fn business-service) :delete-resource! {:id (parse-id request) :resource_type (get-in request [:path-params :type])})
  (ok "删除成功"))

(defn- ensure-dir! [path]
  (let [dir (io/file path)]
    (when-not (.exists dir) (.mkdirs dir))))

(defn- extension [filename]
  (let [idx (str/last-index-of filename ".")]
    (if idx (subs filename idx) "")))

(defn upload-attachment [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          file (get-in request [:params :file])
          body (:params request)
          temp-file (:tempfile file)
          original (:filename file)
          biz-type (or (:biz_type body) "common")
          biz-id (parse-int* (:biz_id body) nil)
          section-key (or (:section_key body) "")
          purpose (or (:file_purpose body) "attachment")]
      (if (and temp-file original)
        (let [dir (str upload-root "/" biz-type)
              stored (str (java.util.UUID/randomUUID) "-" original)
              target (io/file dir stored)
              ext (extension original)]
          (ensure-dir! dir)
          (io/copy temp-file target)
          (let [params {:biz_type biz-type
                        :biz_id biz-id
                        :section_key section-key
                        :file_purpose purpose
                        :original_name original
                        :stored_name stored
                        :storage_type "local"
                        :storage_path (.getPath target)
                        :file_url (str "/api/business/attachments/" stored "/download")
                        :mime_type (or (:content-type file) "application/octet-stream")
                        :extension ext
                        :file_size (.length target)
                        :sort_order (parse-int* (:sort_order body) 0)
                        :create_by (current-user request)
                        :remark (or (:remark body) "")}]
            (query-fn :create-attachment! params)
            (ok (assoc params :id (last-id business-service)))))
        (fail "上传失败")))
    (catch Exception e (fail (.getMessage e)))))

(defn list-attachments [{:keys [business-service]} request]
  (let [params (common-query (:query-params request) [:biz_type :biz_id :section_key :file_purpose])]
    (ok ((:query-fn business-service) :list-attachments params))))

(defn delete-attachment [{:keys [business-service]} request]
  ((:query-fn business-service) :delete-attachment! {:id (parse-id request)})
  (ok "删除成功"))

(defn download-attachment [{:keys [business-service]} request]
  (let [id-or-name (get-in request [:path-params :id])
        row (if (re-matches #"\d+" (str id-or-name))
              (q-fn business-service :get-attachment {:id (parse-int* id-or-name nil)})
              nil)
        file (when row (io/file (:storage_path row)))]
    (if (and file (.exists file))
      (-> (response/response file)
          (response/header "Content-Disposition" (str "attachment; filename=\"" (:original_name row) "\""))
          (response/content-type (or (:mime_type row) "application/octet-stream")))
      (fail "文件不存在"))))

(defn list-gallery-items [{:keys [business-service]} request]
  (ok ((:query-fn business-service) :list-gallery-items {:atlas_id (parse-id request)})))

(defn create-gallery-item [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          params (-> (:body-params request)
                     (body-with-defaults {:image_title "" :image_desc "" :is_cover "N" :sort_order 0 :remark ""})
                     (assoc :atlas_id (parse-id request))
                     (with-create-user request))]
      (when (= "Y" (:is_cover params))
        (query-fn :clear-gallery-cover! {:atlas_id (:atlas_id params)}))
      (query-fn :create-gallery-item! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

(defn update-gallery-item [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          params (-> (:body-params request)
                     (body-with-defaults {:image_title "" :image_desc "" :is_cover "N" :sort_order 0 :remark ""})
                     (assoc :atlas_id (parse-id request)
                            :id (parse-int* (get-in request [:path-params :item-id]) nil))
                     (with-update-user request))]
      (when (= "Y" (:is_cover params))
        (query-fn :clear-gallery-cover! {:atlas_id (:atlas_id params)}))
      (query-fn :update-gallery-item! params)
      (ok "更新成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn delete-gallery-item [{:keys [business-service]} request]
  ((:query-fn business-service) :delete-gallery-item! {:atlas_id (parse-id request)
                                                       :id (parse-int* (get-in request [:path-params :item-id]) nil)})
  (ok "删除成功"))
