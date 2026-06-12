(ns com.ruoyi.rouyi.web.routes.captcha
  "验证码路由。"
  (:require
    [com.ruoyi.rouyi.web.controllers.captcha :as captcha]))

(defn captcha-routes [_opts]
  ["/captcha"
   ["/image" {:get {:summary "获取验证码图片"
                    :description "生成验证码图片，返回图片数据和验证码ID"
                    :handler (partial captcha/captcha-image {})}}]])
