(ns com.ruoyi.rouyi.web.controllers.captcha
  "验证码控制器 — 生成和验证验证码。"
  (:require
   [ring.util.response :as response]
   [clojure.string :as str]))

(defonce captcha-store (atom {}))

(defn- gen-code []
  (let [chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"]
    (apply str (repeatedly 4 #(rand-nth (seq chars))))))

(defn captcha-image
  "生成验证码。返回简单文本验证码（后续可扩展为图片）。"
  [_ _]
  (let [uuid (str (java.util.UUID/randomUUID))
        code (gen-code)]
    (swap! captcha-store assoc uuid {:code code :expire (+ (System/currentTimeMillis) 120000)})
    (-> (response/response {:code 200
                            :msg "操作成功"
                            :img ""
                            :uuid uuid
                            :captchaEnabled true
                            :data {:code code}})  ;; 文本验证码（生产环境应去掉code）
        (response/content-type "application/json"))))
