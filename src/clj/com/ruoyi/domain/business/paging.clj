(ns com.ruoyi.domain.business.paging
  "办公模块共用的查询参数归一.

   7 个办公领域服务各自私有一份 page-params, 其中 4 份直接用 Integer/parseInt
   解析未经校验的查询串, 遇到 page=abc 会抛 NumberFormatException 变成 500.
   这里提供唯一实现: 非数字或缺失都退化为默认值, 不抛异常.

   只依赖 clojure.core, 是叶子命名空间, 任何领域层都可以引用而不引入环.")


(defn optional-id
  "把可选的数字参数转成整数; 缺失或非数字时返回 nil.
   入口中间件已把数字串解析成整数, 这里同时接受整数与字符串,
   非数字值返回 nil 而不是抛 NumberFormatException."
  [v]
  (cond
    (integer? v) v
    (nil? v) nil
    :else (try (Integer/parseInt (str v))
               (catch NumberFormatException _ nil))))


(defn params
  "归一页码与每页数量, 并算出 SQL 用的 offset.
   缺失或非法值退化为 page=1, size=10; 非正页码按 1 处理, 避免负 offset."
  [params]
  (let [page (optional-id (get params :page))
        size (optional-id (get params :size))
        page (if (and page (pos? page)) page 1)
        size (if (and size (pos? size)) size 10)]
    {:page page :size size :offset (* (dec page) size)}))