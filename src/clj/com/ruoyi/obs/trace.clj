(ns com.ruoyi.obs.trace
   "请求生命周期可见性。

   把一条动态用户请求拆成三段可检视的投影，读者是程序员与 AI：

     · 请求本身  (project-intent)  — 它是谁、想干什么、要满足什么契约
     · 请求过程  (project-timeline) — 它走了哪些调用/SQL、耗时、在哪卡住、有无异常
     · 请求结果  (project-result)  — 它返回什么、改了世界什么(副作用)、契约是否被违反

   并给出 (replay) 把一条已捕获的动态请求变成确定性回归用例，
   以及 (agent-context)/(render-context) 把三投影合一、喂给 AI。

   Clojure 的三个语言优势，正是让这套可见性“几乎零插桩、且并发安全”的地基：

     · 动态作用域(*trace* + binding)：外层中间件为每个请求开一个 binding，
       过程/副作用的采集无需向调用点穿参，交错并发的请求天然不串扰；
     · 高阶/动态代理：(wrap-query-fn) 包住 db.sql/query-fn，用现有 trace 的做法
       把“每条 SQL 的过程 + 受影响的行数 + 异常”，几乎不改调用点就收进来；
     · 数据即代码/可序列化：一次 trace 就是一棵可打印、可 diff、可重放的数据结构，
       (canonical) 把任意结果规约成稳定可比较的 EDN 近似，供 (replay) 比对。

   本命名空间自包含：只依赖 clojure.core 与 cheshire（项目主依赖），
   因此其测试不需启动 Integrant 系统即可跑通。"
   (:require
    [cheshire.core :as json]
    [clojure.data :as data]
    [clojure.string :as str]))

;; ── 动态上下文 ───────────────────────────────────────────────────────
;; *trace* 持当前请求的 Trace（一个 atom-of-Trace；Trace.spans 又是 atom-of-vector）。
;; 用 ^:dynamic + binding 实现“每请求一棵独立时间线”，并发的交错请求互不串扰。

(defonce ^:dynamic *trace* nil)

(defrecord Trace [id starts-at spans meta])
;; id        : 唯一 uuid(str)
;; starts-at : 起始毫秒
;; spans     : atom，累积向量
;; meta      : 意图/身份/契约/响应 等（由中间件回写 response）

(defn- now-ms [] (System/currentTimeMillis))

(defn new-trace
   "构造一条空白 Trace。meta 可含 :who :intent :perms :contract，contract 形如
    {:desc 字符串 :check (fn [{:response resp :spans spans}] 布尔)}。"
   [meta]
   (->Trace (str (java.util.UUID/randomUUID))
            (now-ms)
            (atom [])
            (merge {:contract nil} meta)))

(defn current-trace [] *trace*)

(defn start-trace!
   "在绑定了一条新 Trace 的 *trace* 内运行 f，并返回其结果。
    这是把“请求过程”采集包住的最小入口（外层中间件用它包住每个请求）。"
   [meta f & args]
   (binding [*trace* (atom (new-trace meta))]
     (apply f args)))

;; ── 过程采集 ──────────────────────────────────────────────────────────

(defn spans-atom [t]
   "暴露某 Trace 的 spans atom，便于测试在“闭 trace”上直接 append。"
   [t] (:spans t))

(defn push-to!
   "向一条 (已闭) Trace 追加一个 span（自动盖 :at 时间戳）。"
   [t span]
   (swap! (spans-atom t) conj (merge {:at (now-ms)} span)))

(defn push!
   "向当前 *trace* 追加一个 span；无活动 trace 时 no-op（安全）。"
   [span]
   (when *trace*
     (push-to! @*trace* span)))

(defn spans [t]
   "取出某 Trace 的全部 span（不可变快照）。"
   @(:spans t))

;; ── query-fn 动态代理：过程 + 副作用采集 ──────────────────────────────
;; 写操作识别：字符串按 SQL 动词；HugSQL keyword 以尾部 '!' 视为写。

(def ^:private re-dml #"(?is)^\s*(insert|update|delete|replace)\b")

(defn dml?
   "判定一次 query-fn 调用是否为写操作。"
   [stmt]
   (cond
     (string? stmt)  (boolean (re-find re-dml (str/trim stmt)))
     (keyword? stmt) (str/ends-with? (name stmt) "!")
     :else           false))

(defn- norm-sql [s]
   (str/replace (str/trim (str s)) #"\s+" " "))

(defn wrap-query-fn
   "返回包裹原 qf 的动态代理：当 *trace* 激活时，
    为每条 SQL 记录过程 span {op db-sql kind dml|dql|error stmt ms rows}，
    透传结果与异常；异常同时记入过程后 re-throw。无 trace 时行为完全透明。"
   [qf]
   (fn [& args]
     (let [stmt (first args)
           t0   (now-ms)]
       (try
         (let [r (apply qf args)]
           (when-let [t *trace*]
             (let [kind (if (dml? stmt) :dml :dql)]
               (push! {:op   :db-sql
                       :kind kind
                       :stmt (norm-sql stmt)
                       :ms   (- (now-ms) t0)
                       :rows (when (= :dml kind)
                               (try (count r) (catch Exception _ nil)))})))
           r)
         (catch Throwable e
           (when *trace*
             (push! {:op   :db-sql
                     :kind :error
                     :stmt (norm-sql stmt)
                     :ms   (- (now-ms) t0)
                     :error {:class (.getName (.getClass e))
                              :msg (ex-message e)}}))
           (throw e))))))
;; ── 异常与副作用采集 ──────────────────────────────────────────────────

(defn record-error!
    "把一次异常记入当前 trace 的异常链，并挑出 ex-data 里的诊断细节（:sql/:params 等）。"
    [e]
    (when-let [t *trace*]
      (push! {:op   :error
               :class (.getName (.getClass e))
               :msg  (ex-message e)
               :data (select-keys (ex-data e) [:sql :params :type :detail :uri :code])})))

(defn observe-exception-handler
    "把既有例外 handler(message status exception request)包一层：先 record-error! 再委托，
    透传结果。用于 patch com.ruoyi.web.middleware.exception/handler，
    让“失败的 SQL/参数 + 异常”也能落进当前 trace。"
    [handler]
    (fn [message status exception request]
      (record-error! exception)
      (handler message status exception request)))

(defn record-effect!
    "记录一条非 SQL 的副作用（如缓存写入、在线心跳）。"
    [op info]
    (push! {:op    :effect
             :name op
             :info info}))

;; ── 结果规约：把任意值变成稳定、可比较、可序列化的 EDN 近似 ──────────

(defn canonical
    "把任意值规约成稳定可比较的结构：
      · 集合转成有序/向量，使比较与顺序、集合无关；
      · 函数 -> :<fn>；Throwable -> {:exception :msg}；
      · 其余原样（数字/字符串/字/布尔/nil/向量/字串 等）。
    供 (replay) 做“结果是否一致”的判定。"
    [x]
    (cond
      (nil? x)        nil
      (fn? x)        :<fn>
      (instance? java.lang.Throwable x) {:exception (.getName (.getClass ^Exception x))
                                          :msg     (ex-message x)}
         (set? x)       (vec (sort (map canonical x)))
      (vector? x)      (mapv canonical x)
      (list? x)        (vec (map canonical x))
      (map? x)        (into {} (sort (map (fn [[k v]]
                                             [(if (keyword? k) k (str k)) (canonical v)])
                                           x)))
      (symbol? x)     (str x)
      :else           x))

;; ── 投影 ──────────────────────────────────────────────────────────────

(defn project-intent
    "第 1 段：这条请求是谁、想干什么、要满足什么契约。meta 由中间件/调用方提供。"
    [t]
    (let [m (:meta t)]
      {:id         (:id t)
       :when       (:starts-at t)
       :who        (:who m)
       :intent     (:intent m)
       :perms      (:perms m)
       :contract-desc (:desc (when (map? (:contract m)) (:contract m)))}))

(defn- error-spans [spans]
    (filter #(or (= :error (% :op)) (= :error (% :kind))) spans))

(defn project-timeline
   "第 2 段：这条请求的过程——时间线、最慢的一段、SQL 次数、异常、总耗时(墙钟)。"
   [t]
   (let [sps (spans t)
         at  (mapv :at sps)]
      {:count     (count sps)
       :steps     (sort-by :at sps)
       :slow-leaf (reduce (fn [a b]
                              (if (>= (int (or (:ms b) 0))
                                      (int (or (:ms a) 0)))
                               b a))
                          nil
                           (filter :ms sps))
       :db-calls  (count (filter #(= :db-sql (% :op)) sps))
       :errors    (error-spans sps)
       :wall-ms   (if (empty? at) 0 (- (apply max at) (apply min at)))}))


(defn project-result
    "第 3 段：这条请求的结果——是否成功、HTTP 状态、副作用、异常因果链、契约是否被违反。
    契约形如 {:desc s :check (fn [{:response resp :spans spans}] 布尔)}。"
    [t]
    (let [m       (:meta t)
          resp    (:response m)
          contract (:contract m)
          sp       (spans t)
          errors   (error-spans sp)
          effects   (filter #(or (= :dml (% :kind)) (= :effect (% :op))) sp)
          ok        (empty? errors)
          c-check   (:check contract)
          c-result  (when c-check
                       (try (c-check {:response resp :spans sp})
                             (catch Throwable _ nil)))]
      {:ok            ok
       :status        (get-in resp [:status])
       :effects       effects
       :error-chain   (reverse errors)
       :contract      (when contract {:desc (:desc contract) :ok c-result})}))


;; ── 重放：把一条动态请求变成确定性回归用例 ──────────────────────────
;; (replay-fn t) 重跑一次，与捕获时的 (:response) 做 canonical 比较。

(defn replay
     "把已捕获请求 t 重放：用 replay-fn 重跑，结果与 (:response) 规约比较。
      返回 {:status stable|changed|no-capture :old :new [:diff]}。
      用途：把运行时偶然抓到的坏请求，变成可重复的回归。"
    [t replay-fn]
    (let [old (:response (:meta t))
          new (replay-fn t)]
      (cond
        (nil? old)
        {:status :no-capture}
        :else
        (let [[a b c] (when new (data/diff (canonical old) (canonical new)))]
          {:status (if c :stable :changed)
           :old    old
           :new    new
           :diff   (when c [a b c])}))))

;; ── 给 AI 的一体化上下文 ────────────────────────────────────────────

(defn agent-context
     "把三投影合并成一个 map（给程序员 / AI 一次性读全一条请求）。"
    [t]
    (merge (project-intent t)
            (project-timeline t)
            (project-result t)))

(defn- json-safe [x]
     "把不可 JSON 序列化的值（函数 / 异常）替换成可读标记。"
    (cond
      (fn? x)
      :<fn>
      (map? x)
      (into {} (map (fn [[k v]] [(str k) (json-safe v)]) x))
      (vector? x)
      (mapv json-safe x)
      (set? x)
      (vec (map json-safe x))
      (instance? Throwable x)
      {:exception (.getName (.getClass ^Exception x))
       :msg       (ex-message x)}
      :else
      x))

(defn render-context
     "把 agent-context 渲染成 JSON 字符串（已剔除函数 / 异常等不可序列化项）。
      这是给 AI 的入口：一条请求的三段可见性，一个 JSON。"
    [t]
    (json/generate-string (json-safe (agent-context t))))

;; ── 最外层 Ring 中间件：把上述一切接到每条 HTTP 请求上 ──────────────

(defn wrap-trace
      "最外层 Ring 中间件：为每个请求开一条 trace，采集过程、回写结果、
       并给响应加 x-trace-id 头。
       可传 {:meta-fn (fn [req] meta) :on-trace (fn [trace])} 定制 meta / 在结尾拿到最终 trace。"
     ([handler]
       (wrap-trace handler {}))
     ([handler {:keys [meta-fn on-trace]}]
       (fn [request]
         (binding [*trace*
                   (atom
                     (new-trace
                       (or (when meta-fn (meta-fn request))
                           {:who     (:identity request)
                            :uri     (:uri request)
                            :method (:request-method request)})))]
           (let [resp (handler request)]
             (swap! *trace* update :meta assoc :response resp)
             (if-let [ctx @*trace*]
               (do
                 (when on-trace (on-trace ctx))
                 (if (map? resp)
                   (assoc-in resp [:headers "x-trace-id"] (str (:id ctx)))
                  resp))
              resp))))))
