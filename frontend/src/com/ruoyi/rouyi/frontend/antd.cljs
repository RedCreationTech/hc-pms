(ns com.ruoyi.rouyi.frontend.antd
  "Ant Design 组件 Reagent 封装。"
  (:require
    [reagent.core :as r]
    ["antd" :refer [Button Card ConfigProvider DatePicker Drawer Form Input Layout Menu Modal Pagination Select Space Table Tag Upload message]]
    ["@ant-design/icons" :refer [LockOutlined UserOutlined DashboardOutlined TeamOutlined SettingOutlined SafetyOutlined FileTextOutlined]]))

(def button (r/adapt-react-class Button))
(def card (r/adapt-react-class Card))
(def date-picker (r/adapt-react-class DatePicker))
(def drawer (r/adapt-react-class Drawer))
(def form (r/adapt-react-class Form))
(def form-item (r/adapt-react-class (.-Item Form)))
(def input (r/adapt-react-class Input))
(def password (r/adapt-react-class (.-Password Input)))
(def layout (r/adapt-react-class Layout))
(def layout-header (r/adapt-react-class (.-Header Layout)))
(def layout-sider (r/adapt-react-class (.-Sider Layout)))
(def layout-content (r/adapt-react-class (.-Content Layout)))
(def menu (r/adapt-react-class Menu))
(def modal (r/adapt-react-class Modal))
(def upload (r/adapt-react-class Upload))
(def pagination (r/adapt-react-class Pagination))
(def select (r/adapt-react-class Select))
(def space (r/adapt-react-class Space))
(def table (r/adapt-react-class Table))
(def tag (r/adapt-react-class Tag))

(defn success! [text]
  (.success message text))

(defn error! [text]
  (.error message text))

(def user-icon (r/adapt-react-class UserOutlined))
(def lock-icon (r/adapt-react-class LockOutlined))
(def dashboard-icon (r/adapt-react-class DashboardOutlined))
(def team-icon (r/adapt-react-class TeamOutlined))
(def setting-icon (r/adapt-react-class SettingOutlined))
(def safety-icon (r/adapt-react-class SafetyOutlined))
(def file-text-icon (r/adapt-react-class FileTextOutlined))
