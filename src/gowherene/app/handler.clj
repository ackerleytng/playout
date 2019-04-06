(ns gowherene.app.handler
  (:require [clojure.core.async :refer [go]]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults
                                              site-defaults
                                              api-defaults]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.response :as resp]
            [environ.core :refer [env]]
            [gowherene.reader.core :as reader]
            [gowherene.reader.client :as client]
            [gowherene.logging.requests :refer [log-request]]))

;; ------------------------
;; Handler code

(defn do-retrieve
  [url]
  (let [{:keys [status body]} (client/retrieve url)]
    (if (= status 200)
      {:error nil :data body}
      {:error (str "Couldn't retrieve url! (" status ")") :data nil})))

(defn handle
  [url]
  (if (or (nil? url) (= "" url))
    {:error "Missing url!" :data nil}
    (let [{:keys [error data] :as r} (do-retrieve url)]
      (if error r
          (try
            (let [results (reader/process data)]
              (if (zero? (count results))
                {:error "Couldn't find any addresses! :(" :data nil}
                {:error nil :data results}))
            (catch Exception e
              {:error (str "Error while reading requested page: (" (.getMessage e) ")")
               :data nil}))))))

;; ------------------------
;; Timing stuff

(defn do-with-timing
  "Takes a thunk, executes it under timing and returns
   [timing-in-nanoseconds, result]"
  [f]
  (let [start (System/nanoTime)
        out (f)]
    [(- (System/nanoTime) start) out]))

;; ------------------------
;; Ring stuff

(defn wrap-dir-index
  [handler]
  (fn [req]
    (handler (update req :uri
                     #(if (= "/" %) "/index.html" %)))))

(defroutes static-routes
  (route/resources "/"))

(defroutes api-routes
  (GET "/parse" [url]
       (let [[time results] (do-with-timing #(handle url))]
         (go (log-request url results time))
         (resp/response results)))
  (route/not-found "Not Found"))

(defroutes app-routes
  (wrap-dir-index (wrap-defaults static-routes site-defaults))
  (wrap-json-response (wrap-defaults api-routes api-defaults)))
