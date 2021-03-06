(ns frontend.components.url-list
  (:require [re-frame.core :as re-frame]
            [frontend.subs :as subs]
            [frontend.events :as events]))

(defn url-row [url]
  [:div.panel-block {:style {:font-size "0.9em"}}
   [:span.panel-icon
    {:on-click #(re-frame/dispatch [::events/remove-url url])}
    [:i.fas.fa-times-circle]]
   [:a {:style {:overflow "hidden"} :href url} url]])

(defn url-list []
  (let [url-list @(re-frame/subscribe [::subs/url-list])]
    (when (> (count url-list) 0)
      [:div#url-list.panel.space-out-top
       [:p.panel-heading {:style {:font-size "inherit"}} "Recommendations"]
       (for [url url-list]
         ^{:key url} [url-row url])])))
