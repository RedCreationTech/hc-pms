(ns com.ruoyi.rouyi.web.controllers.captcha
  "验证码控制器 — 生成图片验证码。"
  (:require
    [ring.util.response :as response])
  (:import [java.awt Color Font RenderingHints]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.io ByteArrayOutputStream]
           [java.util Random]))

;; 验证码存储（实际项目应用 Redis）
(defonce captcha-store (atom {}))

(defn- generate-code
  "生成随机验证码。"
  [length]
  (let [chars "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        random (Random.)]
    (apply str (repeatedly length #(nth chars (.nextInt random (count chars)))))))

(defn- generate-color
  "生成随机颜色。"
  [min-val max-val]
  (let [random (Random.)
        r (+ min-val (.nextInt random (- max-val min-val)))
        g (+ min-val (.nextInt random (- max-val min-val)))
        b (+ min-val (.nextInt random (- max-val min-val)))]
    (Color. r g b)))

(defn- create-captcha-image
  "创建验证码图片。"
  [code width height]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        g (.createGraphics image)
        random (Random.)]
    ;; 设置背景
    (.setColor g Color/WHITE)
    (.fillRect g 0 0 width height)
    
    ;; 设置字体
    (.setFont g (Font. "Arial" Font/BOLD 36))
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    
    ;; 绘制验证码字符
    (dotimes [i (count code)]
      (.setColor g (generate-color 50 180))
      (.drawString g (str (nth code i)) (+ 15 (* i 40)) (+ 35 (.nextInt random 10))))
    
    ;; 绘制干扰线
    (dotimes [_ 6]
      (.setColor g (generate-color 100 200))
      (.drawLine g (.nextInt random width) (.nextInt random height)
                 (.nextInt random width) (.nextInt random height)))
    
    ;; 绘制干扰点
    (dotimes [_ 30]
      (.setColor g (generate-color 150 230))
      (.drawOval g (.nextInt random width) (.nextInt random height) 2 2))
    
    (.dispose g)
    image))

(defn captcha-image
  "生成验证码图片并返回。"
  [_ request]
  (let [code (generate-code 4)
        image (create-captcha-image code 150 50)
        baos (ByteArrayOutputStream.)]
    (ImageIO/write image "png" baos)
    (let [uuid (or (get-in request [:query-params "r"])
                   (str (java.util.UUID/randomUUID)))]
      ;; 存储验证码，5分钟有效
      (swap! captcha-store assoc uuid {:code code :expire (+ (System/currentTimeMillis) 300000)})
      ;; 清理过期验证码
      (let [now (System/currentTimeMillis)]
        (swap! captcha-store #(into {} (filter (fn [[_ v]] (< now (:expire v))) %))))
      ;; 返回图片和 UUID
      (-> (response/response (.toByteArray baos))
          (response/content-type "image/png")
          (response/header "Captcha-UUID" uuid)))))
