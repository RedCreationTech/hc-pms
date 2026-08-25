(ns com.ruoyi.web.controllers.business.bpm
  "BPM 流程管理控制器。"
  (:require
   [com.ruoyi.bpm.core :as bpm-core]
   [com.ruoyi.domain.business.bpm :as bpm]
   [ring.util.response :as response]
   [com.ruoyi.web.controllers.business.util :as bu]))

(defn- ok
  "构造成功响应。"
  ([data] (ok 200 "操作成功" data))
  ([code msg data]
   (-> (response/response {:code code :msg msg :data data})
       (response/content-type "application/json"))))

(defn- fail
  "构造失败响应。"
  ([msg] (fail 500 msg))
  ([code msg]
   (-> (response/response {:code code :msg msg})
       (response/content-type "application/json"))))

(defn- wrap-err
  "把服务异常转成 500 响应。"
  [f]
  (try
    (f)
    (catch Exception e
      (fail (.getMessage e)))))

(defn- parse-id
  [request]
  (some-> (get-in request [:path-params :id]) Integer/parseInt))

(defn- current-user
  [request]
  (get-in request [:identity :user-name]))

;; ── 流程分类 ──────────────────────────────────────────────────────────
(defn list-categories
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/category-list bpm-service (bu/kquery request)))))

(defn get-category
  [{:keys [bpm-service]} request]
  (wrap-err #(if-let [c (bpm/category-get bpm-service (parse-id request))]
               (ok c) (fail 404 "分类不存在"))))

(defn create-category
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/category-create bpm-service (:body-params request) (current-user request))
                 (ok nil))))

(defn update-category
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/category-update bpm-service (assoc (:body-params request) :category_id (parse-id request)) (current-user request))
                 (ok nil))))

(defn delete-category
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/category-delete bpm-service (parse-id request)) (ok nil))))

;; ── 流程模型 ──────────────────────────────────────────────────────────
(defn list-models
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/model-list bpm-service (bu/kquery request)))))

(defn get-model
  [{:keys [bpm-service]} request]
  (wrap-err #(if-let [m (bpm/model-get bpm-service (parse-id request))]
               (ok m) (fail 404 "模型不存在"))))

(defn create-model
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/model-create bpm-service (:body-params request) (current-user request))
                 (ok nil))))

(defn update-model
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/model-update bpm-service (assoc (:body-params request) :model_id (parse-id request)) (current-user request))
                 (ok nil))))

(defn delete-model
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/model-delete bpm-service (parse-id request)) (ok nil))))

(defn deploy-model
  "部署流程模型到 Flowable。"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/model-deploy! bpm-service (parse-id request)))))

;; ── 动态表单 ──────────────────────────────────────────────────────────
(defn list-forms
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/form-list bpm-service (bu/kquery request)))))

(defn get-form
  [{:keys [bpm-service]} request]
  (wrap-err #(if-let [f (bpm/form-get bpm-service (parse-id request))]
               (ok f) (fail 404 "表单不存在"))))

(defn create-form
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/form-create bpm-service (:body-params request) (current-user request))
                 (ok nil))))

(defn update-form
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/form-update bpm-service (assoc (:body-params request) :form_id (parse-id request)) (current-user request))
                 (ok nil))))

(defn delete-form
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm/form-delete bpm-service (parse-id request)) (ok nil))))

;; ── 流程实例（发起 + 我的）────────────────────────────────────────────
(defn start-instance
  "发起流程实例。body: {:model_id x :business_key y :form_data {...}}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [model_id business_key form_data]} (:body-params request)]
               (ok (bpm/instance-start! bpm-service model_id business_key form_data (current-user request))))))

(defn list-instances
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/instance-list bpm-service (bu/kquery request)))))

(defn instance-history
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/instance-history bpm-service (get-in request [:path-params :pid])))))

(defn instance-diagram
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/instance-diagram bpm-service (get-in request [:path-params :pid])))))

(defn office-stats
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/office-stats bpm-service (current-user request)))))

;; ── 任务（待办/已办/审批）────────────────────────────────────────────
(defn list-todo
  [{:keys [bpm-service]} request]
  (wrap-err #(let [engine (:engine bpm-service)
                   user (current-user request)]
               (ok {:rows (bpm-core/todo-list engine user)
                    :total (bpm-core/todo-count engine user)}))))

(defn list-done
  [{:keys [bpm-service]} request]
  (wrap-err #(ok {:rows (bpm-core/done-list (:engine bpm-service) (current-user request))})))

(defn approve-task
  "审批通过。path: :id (task-id), body: {:comment x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   comment (get-in request [:body-params :comment])]
               (bpm-core/approve! (:engine bpm-service) task-id (current-user request) comment)
               (ok nil))))

(defn reject-task
  "审批驳回。"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   comment (get-in request [:body-params :comment])]
               (bpm-core/reject! (:engine bpm-service) task-id (current-user request) comment)
               (ok nil))))

(defn claim-task
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm-core/claim! (:engine bpm-service) (get-in request [:path-params :id]) (current-user request))
                 (ok nil))))

(defn transfer-task
  "转办。body: {:to_user x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   to-user (get-in request [:body-params :to_user])]
               (bpm-core/transfer! (:engine bpm-service) task-id (current-user request) to-user)
               (ok nil))))
