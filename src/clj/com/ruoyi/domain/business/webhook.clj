(ns com.ruoyi.domain.business.webhook
  "模型级 Webhook 与节点监听器:按模型配置对模型级 4 个钩子
   (process_start/process_end/task_start/task_end)和节点监听器 3 个事件
   (Create/Assign/Complete)发 HTTP POST,并支持响应回写流程变量.
   全部失败只记日志,绝不影响流程."
  (:require
    [cheshire.core :as json]
    [clojure.tools.logging :as log]
    [clj-http.client :as http]
    [com.ruoyi.bpm.core :as bpm]
    [com.ruoyi.domain.business.json-field :as jf]))


(def ^:private http-opts
  {:content-type :json
   :socket-timeout 5000
   :conn-timeout 5000})


(defn- instance-vars
  "流程实例变量 → 字符串 key 的 Clojure map(运行中取,结束后取历史).
   供 ${字段} 占位符解析(表单字段在发起时已展开为流程变量)."
  [engine pid]
  (let [rt (.getRuntimeService ^org.flowable.engine.ProcessEngine engine)
        running (try (into {} (.getVariables rt pid)) (catch Exception _ {}))]
    (if (seq running)
      running
      (try
        (into {}
              (map (fn [^org.flowable.variable.api.history.HistoricVariableInstance hvi]
                     [(.getVariableName hvi) (.getValue hvi)]))
              (.list (.processInstanceId
                       (.createHistoricVariableInstanceQuery (.getHistoryService
                                                               ^org.flowable.engine.ProcessEngine engine))
                       pid)))
        (catch Exception _ {})))))


(defn- find-biz-instance
  "查询业务实例(带短重试):task_start 钩子在 Flowable 命令内触发,
   可能略早于业务行 insert 提交,重试最多 10 次 ×100ms."
  [query-fn pid]
  (loop [n 10]
    (let [inst (try (query-fn :bpm/find-instance-by-pid
                              {:process_instance_id pid})
                    (catch Exception _ nil))]
      (cond
        inst inst
        (zero? n) nil
        :else (do (Thread/sleep 100)
                  (recur (dec n)))))))


(defn- writeback-response!
  "P1 响应回写:解析 JSON 响应体,按 response-mappings(JSON 路径 → 流程变量名)
   提取并写流程变量;任何失败只记日志,绝不影响流程."
  [engine pid hook resp]
  (let [mappings (seq (or (:response-mappings hook) (:responseMappings hook)))]
    (when (and mappings (some #(seq (str (:key %))) mappings))
      (try
        (let [data (json/parse-string (str (:body resp)) true)
              rt (.getRuntimeService ^org.flowable.engine.ProcessEngine engine)]
          (doseq [{:keys [key value]} mappings
                  :when (and (seq (str key)) (seq (str value)))
                  :let [v (jf/get-path data (str key))]
                  :when (some? v)]
            (.setVariable rt (str pid) (str value) v)
            (log/info "[bpm-webhook] 响应回写" key "→" value "=" v)))
        (catch Exception e
          (log/warn "[bpm-webhook] 响应回写失败:" (.getMessage e)))))))


(defn- resolve-pairs
  "把 [{:key :value}] 配置解析成占位符替换后的 map(空 key 跳过)."
  [pairs vars]
  (into {}
        (keep (fn [{:keys [key value]}]
                (when (seq (str key))
                  [key (bpm/resolve-placeholders value vars)])))
        pairs))


(defn- hook-request
  "拼装一次 HTTP POST:headers / form-params 均做占位符替换;无 url 返回 nil."
  [hook vars]
  (when (and (map? hook) (seq (str (:url hook))))
    {:url (str (:url hook))
     :headers (resolve-pairs (:headers hook) vars)
     :form-params (into {}
                        (map (fn [[k v]] [(keyword (str k)) (str v)]))
                        (resolve-pairs (:bodyParams hook) vars))}))


(defn fire-webhooks!
  "按模型级 webhooks 配置触发 HTTP POST(4 钩子:process_start/process_end/task_start/task_end).
   headers[]/bodyParams[] 的值支持固定值或 ${字段} 占位(流程变量 + 事件信息).
   P1:POST 成功后按 response-mappings(JSON 路径 → 流程变量名)解析 JSON 响应并回写流程变量.
   失败只记日志,绝不影响流程.实例无业务记录(绕过业务层直接起实例)时不触发."
  [event {:keys [engine query-fn]} {:keys [task-id process-instance-id task-name]}]
  (when (and query-fn (seq (str process-instance-id)))
    (when-let [inst (find-biz-instance query-fn process-instance-id)]
      (let [model (try (query-fn :bpm/find-model-by-id {:model_id (:model_id inst)})
                       (catch Exception _ nil))
            hooks (jf/parse-field (:webhooks model))
            hook (or (get hooks (keyword event)) (get hooks event))
            vars (merge (instance-vars engine process-instance-id)
                        {"processInstanceId" (str process-instance-id)
                         "taskId" (str (or task-id ""))
                         "event" event
                         "taskName" (str (or task-name ""))})]
        (when-let [{:keys [url headers form-params]} (hook-request hook vars)]
          (try
            (let [resp (http/post url (assoc http-opts
                                             :headers headers
                                             :form-params form-params))]
              (log/info "[bpm-webhook]" event "→" url)
              (writeback-response! engine process-instance-id hook resp))
            (catch Exception e
              (log/error "[bpm-webhook]" event "POST 失败:" url (.getMessage e)))))))))


(defn fire-node-listener!
  "节点监听器(nodeConfig.listeners 的 Create/Assign/Complete 三事件):
   触发配置的 HTTP POST,params[] 的值支持固定值或 ${字段}(流程变量 + taskId/实例等).
   P1:同样支持 response-mappings 响应回写.失败只记日志,绝不影响流程.

已知限制(与 fire-webhooks! 相同):响应在引擎命令内同步写流程变量,同一实例上若有
   并发的写操作会命中乐观锁冲突,此时只记日志不回写."
  [{:keys [engine]} event-name ^org.flowable.task.service.delegate.DelegateTask task]
  (let [node-config (bpm/node-config-of engine task)
        listeners (:listeners node-config)
        cfg (or (get listeners (keyword event-name)) (get listeners event-name))
        cfg (if (map? cfg) cfg {})]
    (when (and (:enable cfg) (seq (str (:url cfg))))
      (let [pid (.getProcessInstanceId task)
            vars (merge (instance-vars engine pid)
                        {"taskId" (.getId task)
                         "processInstanceId" (str pid)
                         "taskName" (str (.getName task))
                         "event" (str event-name)})
            url (str (:url cfg))
            body (into {}
                       (map (fn [[k v]] [(keyword (str k)) (str v)]))
                       (resolve-pairs (:params cfg) vars))]
        (try
          (let [resp (http/post url (assoc http-opts :form-params body))]
            (log/info "[bpm-node-listener]" event-name "→" url "任务" (.getId task))
            (writeback-response! engine pid cfg resp))
          (catch Exception e
            (log/error "[bpm-node-listener]" event-name "POST 失败:"
                       url (.getMessage e))))))))