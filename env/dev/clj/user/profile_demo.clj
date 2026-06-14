(ns user.profile-demo
  "nREPL 可调的 async-profiler + criterium 性能对比演示。
   包含两个场景：
   1. GC 压力 — 大量临时对象 vs 基本类型数组
   2. 反射开销 — 无 type hint vs 有 type hint"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [criterium.core :as c]
   [clj-async-profiler.core :as prof]))

(set! *warn-on-reflection* true)

;; ─────────────────────────────────────────────────────────────────
;; 工具函数
;; ─────────────────────────────────────────────────────────────────

(defn- mean->ns
  "把 criterium 返回的 mean（单位秒）转换成纳秒。"
  [bench-result]
  (when-let [mean (:mean bench-result)]
    (* 1e9 (first mean))))

(defn- fmt-time
  "把纳秒格式化为友好字符串。"
  [ns]
  (cond
    (>= ns 1e9) (format "%.3f s" (/ ns 1e9))
    (>= ns 1e6) (format "%.3f ms" (/ ns 1e6))
    (>= ns 1e3) (format "%.3f µs" (/ ns 1e3))
    :else       (format "%.3f ns" ns)))

;; ─────────────────────────────────────────────────────────────────
;; 场景 1：GC 压力
;; ─────────────────────────────────────────────────────────────────

(defn heavy-gc
  "产生大量临时对象，触发频繁 GC。"
  [n]
  (dotimes [_ n]
    (vec (range 1000))))

(defn light-gc
  "使用 long-array，避免大量装箱对象。"
  [n]
  (dotimes [_ n]
    (long-array 1000)))

(defn run-gc-bench
  "对 GC 场景做 criterium 对比。"
  []
  (println "== GC 场景：heavy-gc ==")
  (let [bad (c/quick-benchmark (heavy-gc 200) {})]
    (c/report-result bad)
    (println "== GC 场景：light-gc ==")
    (let [good (c/quick-benchmark (light-gc 200) {})]
      (c/report-result good)
      {:bad bad :good good})))

;; ─────────────────────────────────────────────────────────────────
;; 场景 2：反射开销
;; ─────────────────────────────────────────────────────────────────

(defn make-collections
  "构造一组 java.util.ArrayList，用于反射测试。"
  [n]
  (vec (for [_ (range n)]
         (java.util.ArrayList. ^java.util.Collection (range 10)))))

(defn slow-reflection
  "没有 type hint，会触发反射。"
  [colls]
  (reduce #(+ %1 (.size %2)) 0 colls))

(defn fast-hinted
  "给参数和元素加上 type hint，消除反射。"
  [^java.util.Collection colls]
  (reduce #(+ %1 (.size ^java.util.Collection %2)) 0 colls))

(defn run-reflection-bench
  "对反射场景做 criterium 对比。"
  []
  (let [colls (make-collections 10000)]
    (println "== 反射场景：slow-reflection（会有 reflection warning）==")
    (let [bad (c/quick-benchmark (slow-reflection colls) {})]
      (c/report-result bad)
      (println "== 反射场景：fast-hinted ==")
      (let [good (c/quick-benchmark (fast-hinted colls) {})]
        (c/report-result good)
        {:bad bad :good good}))))

;; ─────────────────────────────────────────────────────────────────
;; async-profiler 火焰图
;; ─────────────────────────────────────────────────────────────────

(defonce ^:private last-gc-profiles (atom nil))
(defonce ^:private last-reflection-profiles (atom nil))

(defn profile-gc
  "对 GC 场景做 allocation profiling，返回生成的两个火焰图路径。"
  []
  (println "开始 profiling heavy-gc (alloc)...")
  (prof/start {:event :alloc :title "heavy-gc"})
  (heavy-gc 2000)
  (let [bad (prof/stop {:title "heavy-gc"})]
    (println "  火焰图:" bad)
    (println "开始 profiling light-gc (alloc)...")
    (prof/start {:event :alloc :title "light-gc"})
    (light-gc 2000)
    (let [good (prof/stop {:title "light-gc"})]
      (println "  火焰图:" good)
      (reset! last-gc-profiles [bad good])
      [bad good])))

(defn profile-reflection
  "对反射场景做 CPU profiling，返回生成的两个火焰图路径。"
  []
  (let [colls (make-collections 10000)]
    (println "开始 profiling slow-reflection (cpu)...")
    (prof/start {:event :cpu :title "slow-reflection"})
    (dotimes [_ 100] (slow-reflection colls))
    (let [bad (prof/stop {:title "slow-reflection"})]
      (println "  火焰图:" bad)
      (println "开始 profiling fast-hinted (cpu)...")
      (prof/start {:event :cpu :title "fast-hinted"})
      (dotimes [_ 100] (fast-hinted colls))
      (let [good (prof/stop {:title "fast-hinted"})]
        (println "  火焰图:" good)
        (reset! last-reflection-profiles [bad good])
        [bad good]))))

;; ─────────────────────────────────────────────────────────────────
;; 报告生成
;; ─────────────────────────────────────────────────────────────────

(defn- file-link
  [path label]
  (if path
    (format "<a href=\"file://%s\" target=\"_blank\">%s</a>" path label)
    "未生成"))

(defn- html-template
  [gc-bench reflection-bench]
  (let [gc-bad   (mean->ns (:bad gc-bench))
        gc-good  (mean->ns (:good gc-bench))
        ref-bad  (mean->ns (:bad reflection-bench))
        ref-good (mean->ns (:good reflection-bench))
        [gc-bad-path gc-good-path] @last-gc-profiles
        [ref-bad-path ref-good-path] @last-reflection-profiles]
    (str "<!DOCTYPE html>\n"
         "<html lang=\"zh-CN\">\n<head>\n"
         "  <meta charset=\"UTF-8\">\n"
         "  <title>性能对比报告</title>\n"
         "  <script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js\"></script>\n"
         "  <style>body{font-family:sans-serif;margin:24px;} h2{margin-top:32px;} .box{background:#f5f5f5;padding:12px;border-radius:8px;margin:12px 0;}</style>\n"
         "</head>\n<body>\n"
         "  <h1>Criterium + async-profiler 性能对比</h1>\n"
         "  <div class=\"box\">\n"
         "    <p><strong>GC 场景</strong>：heavy-gc = " (fmt-time gc-bad)
         "，light-gc = " (fmt-time gc-good)
         "，加速比 ≈ " (format "%.2fx" (/ gc-bad gc-good)) "</p>\n"
         "    <p><strong>反射场景</strong>：slow-reflection = " (fmt-time ref-bad)
         "，fast-hinted = " (fmt-time ref-good)
         "，加速比 ≈ " (format "%.2fx" (/ ref-bad ref-good)) "</p>\n"
         "  </div>\n"
         "  <h2>GC 场景：临时对象 vs 基本类型数组</h2>\n"
         "  <canvas id=\"gcChart\" width=\"500\" height=\"300\"></canvas>\n"
         "  <p>allocation flamegraph（bad）: " (file-link gc-bad-path "heavy-gc") "</p>\n"
         "  <p>allocation flamegraph（good）: " (file-link gc-good-path "light-gc") "</p>\n"
         "  <h2>反射场景：无 type hint vs 有 type hint</h2>\n"
         "  <canvas id=\"refChart\" width=\"500\" height=\"300\"></canvas>\n"
         "  <p>CPU flamegraph（bad）: " (file-link ref-bad-path "slow-reflection") "</p>\n"
         "  <p>CPU flamegraph（good）: " (file-link ref-good-path "fast-hinted") "</p>\n"
         "  <script>\n"
         "    const commonOptions = {\n"
         "      scales: { y: { type: 'logarithmic', title: { display: true, text: '平均耗时（ns，对数轴）' } } },\n"
         "      plugins: { legend: { display: false } }\n"
         "    };\n"
         "    new Chart(document.getElementById('gcChart'), {\n"
         "      type: 'bar',\n"
         "      data: { labels: ['heavy-gc', 'light-gc'],\n"
         "              datasets: [{ data: [" gc-bad ", " gc-good "],\n"
         "                           backgroundColor: ['#ff4d4f', '#52c41a'] }] },\n"
         "      options: commonOptions\n"
         "    });\n"
         "    new Chart(document.getElementById('refChart'), {\n"
         "      type: 'bar',\n"
         "      data: { labels: ['slow-reflection', 'fast-hinted'],\n"
         "              datasets: [{ data: [" ref-bad ", " ref-good "],\n"
         "                           backgroundColor: ['#ff4d4f', '#52c41a'] }] },\n"
         "      options: commonOptions\n"
         "    });\n"
         "  </script>\n"
         "</body>\n</html>")))

(defn generate-report
  "生成包含 chart 的 HTML 报告。默认写到 target/profile-report.html。"
  ([]
   (let [gc (run-gc-bench)
         ref (run-reflection-bench)]
     (generate-report "target/profile-report.html" gc ref)))
  ([path gc-bench reflection-bench]
   (let [f (io/file path)]
     (.mkdirs (.getParentFile f))
     (spit f (html-template gc-bench reflection-bench))
     (println "报告已生成:" (.getAbsolutePath f))
     path)))

(defn run-all
  "一键跑完 profiling + criterium + 生成报告。"
  []
  (println "== 1. GC allocation profiling ==")
  (profile-gc)
  (println "== 2. Reflection CPU profiling ==")
  (profile-reflection)
  (println "== 3. Criterium benchmark ==")
  (let [gc (run-gc-bench)
        ref (run-reflection-bench)]
    (generate-report "target/profile-report.html" gc ref)))
