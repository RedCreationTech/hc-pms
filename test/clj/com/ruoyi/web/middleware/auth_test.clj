(ns com.ruoyi.web.middleware.auth-test
  "认证中间件与路由级权限中间件测试."
  (:require
    [clojure.test :refer [deftest is testing]]
    [com.ruoyi.infra.online :as online]
    [com.ruoyi.infra.security :as security]
    [com.ruoyi.web.middleware.auth :as auth]
    [com.ruoyi.web.middleware.authz :as authz]))


(deftest test-wrap-jwt-auth-with-valid-token
  (testing "有效且未撤销的令牌附加 identity 并更新心跳"
    (let [calls (atom [])]
      (with-redefs [security/extract-token (fn [_] "valid-token")
                    online/valid-claims (fn [_] {:user-id 1 :user-name "admin" :jti "j1"})
                    online/heartbeat! (fn [claims] (swap! calls conj [:heartbeat (:jti claims)]))]
        (let [handler (auth/wrap-jwt-auth (fn [req] {:identity (:identity req)}))
              response (handler {:headers {"authorization" "Bearer valid-token"}})]
          (is (= {:user-id 1 :user-name "admin" :jti "j1"} (:identity response)))
          (is (= [[:heartbeat "j1"]] @calls)))))))


(deftest test-wrap-jwt-auth-with-revoked-or-invalid-token
  (testing "已撤销或无效令牌不附加 identity, 也不更新心跳; 已有 identity 被清除"
    (let [calls (atom [])]
      (with-redefs [security/extract-token (fn [_] "revoked")
                    online/valid-claims (fn [_] nil)
                    online/heartbeat! (fn [c] (swap! calls conj c))]
        (let [handler (auth/wrap-jwt-auth (fn [req] {:identity (:identity req)}))
              response (handler {:identity {:user-id 9}})]
          (is (nil? (:identity response)))
          (is (empty? @calls)))))))


(deftest test-require-auth
  (testing "已认证请求放行, 未认证返回 401"
    (is (= {:ok true} ((auth/require-auth (fn [_] {:ok true})) {:identity {:user-id 1}})))
    (is (= 401 (:status ((auth/require-auth (fn [_] {:ok true})) {}))))))


(deftest test-auth-middleware
  (testing "组合中间件: 强制认证时未登录返回 401, 非强制时放行"
    (with-redefs [security/extract-token (fn [_] nil)]
      (is (= 401 (:status (((auth/auth-middleware {:required? true}) (fn [_] {:ok true})) {}))))
      (is (= {:ok true} (((auth/auth-middleware {}) (fn [_] {:ok true})) {}))))))


(defn- perms-handler
  "按声明的 perms 编译路由级权限中间件; 实时身份由调用方 with-redefs authz/load-actor 提供."
  [perms _actor]
  (let [mw (authz/perms-middleware (fn [& _] nil))
        wrapped ((:compile mw) {:perms perms} nil)]
    (wrapped (fn [req] {:status 200 :actor (:actor req)}))))


(deftest test-perms-middleware
  (let [viewer {:user_id 2 :admin? false :permissions #{"system:user:list"}}
        admin {:user_id 1 :admin? true :permissions #{"*:*:*"}}
        req {:identity {:user-id 2}}]
    (testing "拥有任一所需权限放行, 并附加实时身份"
      (with-redefs [authz/load-actor (fn [_ _] viewer)]
        (is (= 200 (:status ((perms-handler "system:user:list" viewer) req))))
        (is (= 200 (:status ((perms-handler ["system:user:add" "system:user:list"] viewer) req))))
        (is (= viewer (:actor ((perms-handler :login viewer) req))))))
    (testing "缺少权限 403, 未声明权限 403 (fail closed)"
      (with-redefs [authz/load-actor (fn [_ _] viewer)]
        (is (= 403 (:status ((perms-handler "system:user:add" viewer) req))))
        (is (= 403 (:status ((perms-handler nil viewer) req))))))
    (testing "超级管理员放行; 用户停用 (无实时身份) 401; 未登录 401"
      (with-redefs [authz/load-actor (fn [_ _] admin)]
        (is (= 200 (:status ((perms-handler "anything:any:thing" admin) {:identity {:user-id 1}})))))
      (with-redefs [authz/load-actor (fn [_ _] nil)]
        (is (= 401 (:status ((perms-handler :login nil) req)))))
      (is (= 401 (:status ((perms-handler :login viewer) {})))))
    (testing "权限可由请求决定 (按路径参数)"
      (with-redefs [authz/load-actor (fn [_ _] viewer)]
        (let [f (fn [r] (if (= "user" (get-in r [:path-params :m])) "system:user:list" "x:y:z"))]
          (is (= 200 (:status ((perms-handler f viewer) (assoc req :path-params {:m "user"})))))
          (is (= 403 (:status ((perms-handler f viewer) (assoc req :path-params {:m "other"}))))))))))


(deftest test-permitted?
  (is (authz/permitted? {:permissions #{"a"}} "a"))
  (is (authz/permitted? {:permissions #{"a"}} ["b" "a"]))
  (is (not (authz/permitted? {:permissions #{"a"}} "b")))
  (is (authz/permitted? {:permissions #{"*:*:*"}} "b"))
  (is (authz/permitted? {:admin? true :permissions #{}} "b")))
