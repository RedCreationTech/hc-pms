(ns com.ruoyi.business.bpm-candidate-test
  "候选解析纯函数的直接单测 (2026-11-24).

   这些函数从 resolve-candidate-users 里抽出来, 与 Flowable 无关. 端到端测试
   只能证明'整条链路能跑通', 无法断言这些边界:
     1) user_id 传入数字还是字符串都应对上;
     2) 自选值混传 id 与用户名时都能归一;
     3) 查不到的用户必须被丢弃, 而不是原样透出 (否则任务会指给不存在的人);
     4) 结果去重;
     5) 部门层级走到顶层 / 超过上限时不死循环."
  (:require
    [clojure.test :refer [deftest is testing]]))


(def ^:private users
  [{:user_id 1 :user_name "alice" :dept_id 10}
   {:user_id 2 :user_name "bob" :dept_id 20}
   {:user_id 3 :user_name "carol" :dept_id 20}])


(def ^:private depts
  [{:dept_id 10 :parent_id 100 :leader_id 1}
   {:dept_id 20 :parent_id 10 :leader_id 2}
   {:dept_id 100 :parent_id nil :leader_id 3}])


(defn- call
  "调用领域层私有函数 (重构后拆出的纯函数)."
  [sym & args]
  (apply (requiring-resolve (symbol "com.ruoyi.domain.business.bpm" (name sym))) args))


(deftest user-names-of-test
  (testing "数字与字符串 id 都能对上"
    (is (= ["alice" "bob"] (call 'user-names-of users [1 2])))
    (is (= ["alice" "bob"] (call 'user-names-of users ["1" "2"]))))
  (testing "查不到的 id 被丢弃, 不透出"
    (is (= ["alice"] (call 'user-names-of users [1 999 nil]))))
  (testing "结果去重"
    (is (= ["alice"] (call 'user-names-of users [1 1 "1"]))))
  (testing "空输入"
    (is (= [] (call 'user-names-of users [])))))


(deftest selected-names-test
  (testing "传用户名原样保留"
    (is (= ["alice" "bob"] (call 'selected-names users ["alice" "bob"]))))
  (testing "传 user_id 换名"
    (is (= ["alice" "carol"] (call 'selected-names users [1 "3"]))))
  (testing "id 与用户名混传都能归一"
    (is (= ["alice" "bob"] (call 'selected-names users ["alice" 2]))))
  (testing "不存在的值被过滤, 不透出给任务"
    (is (= ["alice"] (call 'selected-names users ["alice" "nosuchuser" 999]))))
  (testing "全部无效时返回空"
    (is (= [] (call 'selected-names users ["x" "y"])))))


(deftest leader-of-dept-test
  (testing "取部门负责人用户名"
    (is (= ["alice"] (call 'leader-of-dept users depts 10))))
  (testing "部门 id 字符串形式也能对上"
    (is (= ["bob"] (call 'leader-of-dept users depts "20"))))
  (testing "部门不存在返回 nil"
    (is (nil? (call 'leader-of-dept users depts 999))))
  (testing "部门无负责人返回 nil"
    (is (nil? (call 'leader-of-dept users (conj depts {:dept_id 30 :leader_id nil}) 30)))))


(deftest start-user-dept-test
  (testing "按发起人用户名找部门"
    (is (= 10 (call 'start-user-dept-id users "alice"))))
  (testing "未知发起人返回 nil"
    (is (nil? (call 'start-user-dept-id users "nosuchuser")))))


(deftest dept-parent-id-test
  (testing "取上级部门"
    (is (= 100 (call 'dept-parent-id depts 10)))
    (is (= 10 (call 'dept-parent-id depts 20))))
  (testing "字符串 id 也能对上"
    (is (= 100 (call 'dept-parent-id depts "10"))))
  (testing "顶层部门返回 nil"
    (is (nil? (call 'dept-parent-id depts 100))))
  (testing "不存在的部门返回 nil"
    (is (nil? (call 'dept-parent-id depts 999)))))


(deftest multi-level-dept-chain-test
  (testing "从 20 往上爬, 最多 3 层, 到顶层自然停止且不死循环"
    ;; 20 -> 10 -> 100 -> nil, 由 resolve-candidate-users 的 loop 驱动;
    ;; 这里只验证链条本身是收敛的
    (let [chain (loop [did 20 n 0 acc []]
                  (if (or (nil? did) (>= n 3))
                    acc
                    (recur (call 'dept-parent-id depts did) (inc n) (conj acc did))))]
      (is (= [20 10 100] chain)))))