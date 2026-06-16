(ns com.ruoyi.web.controllers.business.core
  "工程方案与资源管理业务控制器。"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ring.util.response :as response]
   [com.ruoyi.domain.business :as biz-domain]))

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
  {:engineering_id nil :project_id nil :engineering_name "" :project_name ""
   :solution_type "" :solution_level "" :current_version 1 :recommend_scene "{}"
   :status "draft" :progress 0 :remark ""})

(defn list-solutions [{:keys [business-service]} request]
  (let [params (common-query (:query-params request) [:solution_name :engineering_id :project_id :solution_type :solution_level :status])
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

;; ========== 方案生成 ==========

(defn- solution-type
  "取得方案类型，兼容旧重构版本中暂存到 remark 的数据。"
  [solution]
  (blank->nil (or (:solution_type solution) (:remark solution))))

(defn- solution-chapters
  "根据方案类型获取章节列表。"
  [solution]
  (biz-domain/get-chapters (solution-type solution)))

(defn generate-solution
  "触发方案生成。保持旧系统语义：draft 表示已触发生成但尚未完成。"
  [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          solution-id (parse-id request)
          solution (query-fn :get-solution {:id solution-id})
          user-name (current-user request)
          scheme-type (when solution (solution-type solution))]
      (cond
        (nil? solution)
        (fail "方案不存在")

        (str/blank? scheme-type)
        (fail "方案类型不能为空")

        (biz-domain/have-generating? query-fn user-name solution-id)
        (fail "已有方案正在生成中，当前平台不支持同时生成，请稍后")

        :else
        (let [chapters (solution-chapters solution)]
          (query-fn :delete-solution-statuses! {:solution_id solution-id})
          (query-fn :mark-solution-generating! {:id solution-id})
          (biz-domain/init-chapter-statuses! query-fn solution-id chapters)
          (ok {:solution_id solution-id
               :status "draft"
               :chapters (mapv (fn [ch] {:name (:name ch) :key (:key ch) :status -1}) chapters)}))))
    (catch Exception e (fail (.getMessage e)))))

(defn get-generate-status
  "查询方案生成进度。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        solution-id (parse-id request)
        solution (query-fn :get-solution {:id solution-id})
        statuses (query-fn :list-solution-statuses {:solution_id solution-id})]
    (if solution
      (ok {:solution_id solution-id
           :status (:status solution)
           :progress (:progress solution)
           :chapters statuses})
      (fail "方案不存在"))))

(defn check-generating
  "检查当前用户是否有正在生成的方案。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        user-name (current-user request)
        generating? (biz-domain/have-generating? query-fn user-name)]
    (ok {:generating generating?})))

(defn fail-expired-solutions
  "将超时的生成中方案标记为失败。"
  [{:keys [business-service]} _request]
  (let [query-fn (:query-fn business-service)]
    (biz-domain/timeout-fail-expired! query-fn (* 10 60 1000))
    (ok "检测完成")))

;; ========== 资源关联 ==========

(defn list-resource-relations
  "查询资源的关联关系。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        resource-id (parse-id request)
        resource-type (get-in request [:path-params :type])
        relations (query-fn :list-resource-relations {:resource_id resource-id :resource_type resource-type})]
    (ok relations)))

(defn save-resource-relations
  "保存资源的关联关系（先删后建）。"
  [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          resource-id (parse-id request)
          resource-type (get-in request [:path-params :type])
          body (:body-params request)
          relations (:relations body)]
      (query-fn :delete-resource-relations! {:resource_id resource-id :resource_type resource-type})
      (doseq [rel relations]
        (query-fn :create-resource-relation! {:resource_id resource-id
                                              :resource_type resource-type
                                              :route_type (:route_type rel)
                                              :route_value (:route_value rel)}))
      (ok "保存成功"))
    (catch Exception e (fail (.getMessage e)))))

(defn find-resources-by-route
  "根据方案类型或省份检索关联资源。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        params (:query-params request)
        resource-type (or (get params "resource_type") (get params :resource_type) "standard")
        route-type (or (get params "route_type") (get params :route_type) "scheme_type")
        route-value (or (get params "route_value") (get params :route_value))]
    (if route-value
      (ok (biz-domain/find-resources-by-route query-fn resource-type route-type route-value))
      (fail "缺少route_value参数"))))

(defn get-generation-resource
  "按旧系统生成接口参数检索资源。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        params (:body-params request)]
    (ok (biz-domain/generation-resources query-fn params))))

(defn get-resource-chapter
  "按章节检索向量知识库，未命中时降级到通用资源检索。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        params (:body-params request)
        chapter-name (or (:chapterName params) (:chapter_name params))
        chapter-rows (if (str/blank? chapter-name)
                       []
                       (query-fn :list-resources {:resource_type "vector-kb"
                                                  :name chapter-name
                                                  :code nil
                                                  :category nil
                                                  :status nil
                                                  :limit 50
                                                  :offset 0}))]
    (if (seq chapter-rows)
      (ok {:isChapter true :resource (mapv #(or (:content %) (:summary %) (:name %)) chapter-rows)})
      (ok {:isChapter false :resource (biz-domain/generation-resources query-fn params)}))))

;; ========== 知识库搜索 ==========

(defn search-knowledge
  "全文搜索知识库。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        params (:query-params request)
        keyword (or (get params "keyword") (get params :keyword) "")]
    (if (str/blank? keyword)
      (ok [])
      (ok (biz-domain/search-knowledge query-fn keyword)))))

;; ========== AI对话 ==========

(defn get-or-create-chat-session
  "获取或创建AI对话会话。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        solution-id (parse-id request)
        user-name (current-user request)
        session (query-fn :get-or-create-chat-session {:solution_id solution-id :user_id 0})]
    (if session
      (ok session)
      (do
        (query-fn :create-chat-session! {:solution_id solution-id
                                         :user_id 0
                                         :user_name user-name
                                         :title "方案问答"})
        (let [new-session (query-fn :get-or-create-chat-session {:solution_id solution-id :user_id 0})]
          (ok new-session))))))

(defn list-chat-messages
  "获取对话消息列表。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        session-id (parse-int* (get-in request [:path-params :session-id]) nil)
        messages (query-fn :list-chat-messages {:session_id session-id})]
    (ok messages)))

(defn save-chat-message
  "保存对话消息（用户提问或AI回复）。"
  [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          session-id (parse-int* (get-in request [:path-params :session-id]) nil)
          body (:body-params request)
          msg-count (query-fn :count-chat-messages {:session_id session-id})
          params {:session_id session-id
                  :role (:role body "user")
                  :content (:content body "")
                  :reasoning_content (:reasoning_content body "")
                  :msg_order (inc (:total msg-count))
                  :model_name (:model_name body "deepseek-r1")}]
      (query-fn :create-chat-message! params)
      (ok {:id (last-id business-service)}))
    (catch Exception e (fail (.getMessage e)))))

;; ========== 方案文件版本 ==========

(defn list-solution-files
  "查询方案的文件版本列表。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        solution-id (parse-id request)
        files (query-fn :list-solution-files {:solution_id solution-id})]
    (ok files)))

(defn create-solution-file
  "创建方案文件版本记录。"
  [{:keys [business-service]} request]
  (try
    (let [query-fn (:query-fn business-service)
          solution-id (parse-id request)
          body (:body-params request)
          latest (query-fn :get-latest-solution-version {:solution_id solution-id})
          version (inc (:max_version latest))
          params {:solution_id solution-id
                  :file_name (:file_name body "方案.docx")
                  :file_path (:file_path body "")
                  :file_type (:file_type body "docx")
                  :version version
                  :file_size (parse-int* (:file_size body) 0)
                  :create_by (current-user request)}]
      (query-fn :create-solution-file! params)
      (ok {:id (last-id business-service) :version version}))
    (catch Exception e (fail (.getMessage e)))))

(defn download-solution-file
  "下载方案文件。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        file-id (parse-int* (get-in request [:path-params :file-id]) nil)
        row (query-fn :get-solution-file {:id file-id})
        file (when row (io/file (:file_path row)))]
    (if (and file (.exists file))
      (-> (response/response file)
          (response/header "Content-Disposition" (str "attachment; filename=\"" (:file_name row) "\""))
          (response/content-type "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
      (fail "文件不存在"))))

;; ========== 方案导出 ==========

(defn export-solution-html
  "导出方案为HTML内容。"
  [{:keys [business-service]} request]
  (let [query-fn (:query-fn business-service)
        solution-id (parse-id request)
        solution (query-fn :get-solution {:id solution-id})]
    (if solution
      (let [sections ["project-info" "people-org" "cover" "design-overview" "layout-plan"]
            html-parts (mapv (fn [key]
                               (let [section (query-fn :get-solution-section {:solution_id solution-id :section_key key})]
                                 (when section
                                   (str "<h2>" (get section-titles key key) "</h2>\n"
                                        "<div>" (:content_json section) "</div>"))))
                             sections)]
        (ok {:html (str "<html><head><meta charset=\"utf-8\"><title>" (:solution_name solution) "</title></head><body>\n"
                        (str/join "\n" (remove nil? html-parts))
                        "\n</body></html>")
             :solution_name (:solution_name solution)}))
      (fail "方案不存在"))))
