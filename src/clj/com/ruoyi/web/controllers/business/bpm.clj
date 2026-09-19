(ns com.ruoyi.web.controllers.business.bpm
  "BPM 流程管理控制器。"
  (:require
   [clojure.string]
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

(defn- admin?
  "是否超级管理员：admin 用户或角色 1。"
  [request]
  (let [id (:identity request)]
    (or (= "admin" (:user-name id))
        (some #(= "1" (str %)) (:roles id)))))

(defn- body-or-query
  "合并 query-params 与 body-params（DELETE 请求体可能不被解析）。"
  [request]
  (merge (bu/kquery request) (:body-params request)))

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

;; ── Phase 3 治理能力：定义版本页 / 模型启停·清理·复制 ────────────────────
(defn definition-page
  "流程定义分页。query: page size modelKey"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/definition-page bpm-service (bu/kquery request)))))

(defn definition-xml
  "流程定义 BPMN XML。query: definitionId"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/definition-xml bpm-service (get (bu/kquery request) :definitionId)))))

(defn definition-restore
  "历史定义 BPMN 反写回模型。body: {:definitionId x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/definition-restore! bpm-service (get (:body-params request) :definitionId)))))

(defn model-set-state
  "挂起/激活该 key 最新定义。body: {:id x :state 1|2}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [id state]} (:body-params request)]
               (ok (bpm/model-set-state! bpm-service id state (current-user request))))))

(defn model-clean
  "清理该流程全部历史实例+部署。query/body: {:id x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [id]} (body-or-query request)]
               (ok (bpm/model-clean! bpm-service id)))))

(defn model-copy
  "复制模型（名称+副本，key+_copy）。query/body: {:id x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [id]} (body-or-query request)]
               (ok (bpm/model-copy! bpm-service id (current-user request))))))

;; ── P1：模型/分类拖拽排序 ─────────────────────────────────────────────
(defn sort-models
  "批量保存模型排序。body: {:ids [model_id ...]}（按新顺序排列）"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/model-sort! bpm-service (:ids (:body-params request))))))

(defn sort-categories
  "批量保存分类排序。body: {:ids [category_id ...]}（按新顺序排列）"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/category-sort! bpm-service (:ids (:body-params request))))))

(defn print-data
  "打印数据。query: id(biz_bpm_instance.instance_id)"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/instance-print-data bpm-service
                                          (some-> (get (bu/kquery request) :id) Integer/parseInt)))))

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
  (wrap-err #(let [user (current-user request)
                   rows (bpm/todo-list-with-buttons bpm-service user)]
               (ok {:rows rows
                    :total (count rows)}))))

(defn list-done
  [{:keys [bpm-service]} request]
  (wrap-err #(ok {:rows (bpm/done-list-with-model-flags bpm-service (current-user request))})))

(defn task-detail
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/task-detail bpm-service (get-in request [:path-params :id])))))

(defn approve-task
  "审批通过。path: :id (task-id), body: {:comment x :sign_pic_url 可选}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   comment (get-in request [:body-params :comment])
                   sign-pic-url (get-in request [:body-params :sign_pic_url])]
               (bpm/task-approve! bpm-service task-id (current-user request) comment sign-pic-url)
               (ok nil))))

(defn reject-task
  "审批驳回。body: {:comment x :return_node_id 可选(从 return-list 选择的退回节点) :sign_pic_url 可选}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   comment (get-in request [:body-params :comment])
                   return-node (get-in request [:body-params :return_node_id])
                   sign-pic-url (get-in request [:body-params :sign_pic_url])]
               (bpm/task-reject! bpm-service task-id (current-user request) comment return-node sign-pic-url)
               (ok nil))))

;; ── Phase 1 审批闭环：加签 / 减签 / 抄送 / 取消 / 撤回 / 可退回节点 ─────
(defn create-sign
  "加签。body: {:taskId x :userIds [] :type before|after :reason x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [taskId userIds type reason]} (:body-params request)]
               (bpm/task-create-sign! bpm-service taskId userIds type reason (current-user request))
               (ok nil))))

(defn delete-sign
  "减签。body: {:taskId x :userIds [] :reason x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [taskId userIds reason]} (body-or-query request)]
               (bpm/task-delete-sign! bpm-service taskId userIds reason (current-user request))
               (ok nil))))

(defn sign-list
  "加签子任务列表。query: taskId="
  [{:keys [bpm-service]} request]
  (wrap-err #(ok {:rows (bpm/task-sign-list bpm-service (get (bu/kquery request) :taskId))})))

(defn return-list
  "可退回节点列表。query: taskId="
  [{:keys [bpm-service]} request]
  (wrap-err #(ok {:rows (bpm/task-return-list bpm-service (get (bu/kquery request) :taskId))})))

(defn copy-task
  "手动抄送。body: {:processInstanceId x :userIds [] :reason x :activityId? :activityName?}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [processInstanceId userIds reason activityId activityName]} (:body-params request)]
               (bpm/task-copy! bpm-service processInstanceId userIds reason activityId activityName
                               (current-user request))
               (ok nil))))

(defn copy-page
  "我的抄送分页。query: page size"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/copy-page bpm-service (bu/kquery request) (current-user request)))))

(defn cancel-instance
  "取消流程实例（发起人或管理员）。body/query: {:id(实例process_instance_id) :reason}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [{:keys [id reason]} (body-or-query request)]
               (bpm/instance-cancel! bpm-service id reason (current-user request) (admin? request))
               (ok nil))))

(defn withdraw-task
  "审批人撤回自己刚审完的任务。body: {:taskId x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get (:body-params request) :taskId)]
               (bpm/task-withdraw! bpm-service task-id (current-user request))
               (ok nil))))

(defn withdraw-to-start
  "发起人撤回到起始节点。body: {:processInstanceId x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [pid (get (:body-params request) :processInstanceId)]
               (bpm/task-withdraw-to-start! bpm-service pid (current-user request) (admin? request))
               (ok nil))))

(defn claim-task
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm-core/claim! (:engine bpm-service) (get-in request [:path-params :id]) (current-user request))
                 (ok nil))))

(defn transfer-task
  "转办。body: {:to_user x}（必填，缺失抛 500 提示）"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   to-user (get-in request [:body-params :to_user])]
               (when (clojure.string/blank? (str (or to-user "")))
                 (throw (ex-info "转办人(to_user)不能为空" {:task-id task-id})))
               (bpm-core/transfer! (:engine bpm-service) task-id (current-user request) to-user)
               (ok nil))))

(defn delegate-task
  "委派。body: {:to_user x}（必填，缺失抛 500 提示）"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (get-in request [:path-params :id])
                   to-user (get-in request [:body-params :to_user])]
               (when (clojure.string/blank? (str (or to-user "")))
                 (throw (ex-info "委派人(to_user)不能为空" {:task-id task-id})))
               (bpm-core/delegate! (:engine bpm-service) task-id to-user)
               (ok nil))))

(defn resolve-task
  "委派办结：被委派人办完事项后任务回到 owner 待办。body: {:taskId x}"
  [{:keys [bpm-service]} request]
  (wrap-err #(let [task-id (or (get (:body-params request) :taskId)
                               (get-in request [:path-params :id]))]
               (bpm-core/resolve! (:engine bpm-service) task-id)
               (ok nil))))

;; ── 流程任务管理 / 流程实例运维 ─────────────────────────────────────
(defn list-all-tasks
  "全部运行中任务（管理员）。"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok {:rows (bpm-core/all-tasks (:engine bpm-service))})))

(defn suspend-instance
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm-core/suspend! (:engine bpm-service) (get-in request [:path-params :pid]))
                 (ok nil))))

(defn activate-instance
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm-core/activate! (:engine bpm-service) (get-in request [:path-params :pid]))
                 (ok nil))))

(defn terminate-instance
  [{:keys [bpm-service]} request]
  (wrap-err #(do (bpm-core/terminate! (:engine bpm-service) (get-in request [:path-params :pid])
                                      (get-in request [:body-params :reason]))
                 (ok nil))))

(defn model-tree
  "获取流程节点树（HTML/flex 编辑器工作模型）。"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/model-tree bpm-service (parse-id request)))))

(defn model-save-tree
  "保存流程节点树（转回 BPMN XML）。"
  [{:keys [bpm-service]} request]
  (wrap-err #(ok (bpm/model-save-tree! bpm-service (parse-id request)
                                        (:body-params request) (current-user request)))))
