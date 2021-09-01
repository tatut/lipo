(ns lipo.main
  (:require [org.httpkit.server :as server]
            [org.httpkit.client :as htclient]
            [compojure.core :refer [routes GET POST]]
            [compojure.route :as route]
            [crux.api :as crux]
            [ripley.html :as h]
            [ripley.live.context :as context]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [clojure.java.io :as io]
            [ring.middleware.params :as params]
            [ring.middleware.session :as session]
            [co.deps.ring-etag-middleware :as ring-etag-middleware]
            [ring.middleware.not-modified :as not-modified]
            [cheshire.core :as cheshire]
            [ripley.live.source :as source]
            [lipo.content-db :as content-db]
            [lipo.image-upload :as image-upload]
            [lipo.portlet :as p]
            [lipo.template :as template]
            [clojure.core.async :as async :refer [go <! timeout]]
            [lipo.admin :as admin]

            ;; Require portlet implementations
            lipo.portlet.page-tree
            lipo.portlet.view
            lipo.portlet.breadcrumbs
            lipo.portlet.search
            lipo.portlet.news
            lipo.portlet.menu))

(defonce server (atom nil))



(defn page-context [{:keys [request crux] :as ctx}]
  ;; FIXME: should this have a protocol or at least
  ;; well documented keys
  (let [[source set-msg!] (source/use-state (get-in ctx [:request :session :flash-message]))]
    (merge ctx
           {:here (:uri request)
            :db (crux/db crux)
            :flash-message-source source
            :set-flash-message! #(go
                                   (set-msg! %)
                                   (<! (timeout 3000))
                                   (set-msg! {}))})))

(defn- render-page [ctx]
  (let [{:keys [db here]} (page-context ctx)]
    (when-let [page-id (content-db/content-id db here)]
      (let [page (crux/entity db page-id)
            portlets (p/portlets-by-slot db page)]
        (h/render-response
         {:session (merge (get-in ctx [:request :session])
                          {:flash-message nil})}
         (fn []
           (template/main-template (page-context ctx) portlets)))))))


(defn- add-icon []
  ;; FIXME: placeholder icon, that needs attribution
  (h/html
   [:svg.mr-1 {:version "1.1" :xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 480 480"
          :style "display: inline-block;"
          :width 24 :height 24}
    [:path {:d "M424,184H296V56c0-30.928-25.072-56-56-56c-30.928,0-56,25.072-56,56v128H56c-30.928,0-56,25.072-56,56 c0,30.928,25.072,56,56,56h128v128c0,30.928,25.072,56,56,56c30.928,0,56-25.072,56-56V296h128c30.928,0,56-25.072,56-56 C480,209.072,454.928,184,424,184z M424,280H288c-4.418,0-8,3.582-8,8v136c0,22.091-17.909,40-40,40c-22.091,0-40-17.909-40-40 V288c0-4.418-3.582-8-8-8H56c-22.091,0-40-17.909-40-40s17.909-40,40-40h136c4.418,0,8-3.582,8-8V56c0-22.091,17.909-40,40-40 c22.091,0,40,17.909,40,40v136c0,4.418,3.582,8,8,8h136c22.091,0,40,17.909,40,40S446.091,280,424,280z"}]]))

(defn create-page-link [path title]
  (h/html
   [:a.rounded.bg-blue-400.m-1.p-1.text-white.p-2
    {:href (str path "?new=1")}
    (add-icon)
    title]))

#_(defn- render-portlet [ctx portlet]
  (let [title (p/title ctx portlet)]
    (h/html
     [:div.portlet.border-black.rounded.border-2.m-2
      [::h/when title
       [:div.bg-blue-200 title]]
      (p/render ctx portlet)])))

(defn app-routes [ctx config]
  (routes

   (image-upload/image-routes ctx config)
   (context/connection-handler "/__ripley-live")
   (GET "/_health" _req {:status 200 :body "ok"})
   (-> (route/resources "/")
       ring-etag-middleware/wrap-file-etag
       not-modified/wrap-not-modified)
   (admin/admin-routes ctx)

   ;; Last route, if no other route matches, this is a page path
   ;; either save it (when POST) or display it (when GET)
   (fn [{m :request-method :as req}]
     (let [ctx (assoc ctx :request req)]
       (when (= m :get)
         (render-page ctx))))

   ;; Fallback, log requests that were not handled
   (fn [req]
     (log/info "Unhandled request:" req)
     nil)))

(defn load-config []
  (-> "config.edn" slurp read-string))

(def crux (atom nil))

(defn init-crux [old-crux config]
  (if old-crux
    (do
      (log/info "CRUX already started, not restarting it.")
      old-crux)
    (let [{:keys [postgresql lmdb-dir lucene-dir]} config]
      (crux/start-node
       (if postgresql
         {:crux.jdbc/connection-pool
          {:dialect {:crux/module 'crux.jdbc.psql/->dialect}
           :pool-opts {}
           :db-spec postgresql}
          :crux/tx-log
          {:crux/module 'crux.jdbc/->tx-log
           :connection-pool :crux.jdbc/connection-pool
           :poll-sleep-duration (java.time.Duration/ofSeconds 1)}
          :crux/document-store
          {:crux/module 'crux.jdbc/->document-store
           :connection-pool :crux.jdbc/connection-pool}

          :crux/index-store
          {:kv-store {:crux/module 'crux.lmdb/->kv-store
                      :db-dir (io/file lmdb-dir)}}
          :crux.lucene/lucene-store {:db-dir lucene-dir}}

         (do
           (log/warn "################################\n"
                     "No :postgresql in configuration, using IN-MEMORY persistence only!\n"
                     "################################")
           {}))))))

(defn init-server [old-server {:keys [port bind-address]
                               :or {port 3000
                                    bind-address "127.0.0.1"}
                               :as config}]
  (when old-server

    (old-server))
  (let [server
        (server/run-server
         (-> (app-routes {:crux @crux} config)
             params/wrap-params
             session/wrap-session)
         {:port port
          :ip bind-address})]
    (log/info "http server start succeeded - listening on port" port)
    (log/info "http health check self test status:"
              (:status  @(htclient/get (str "http://localhost:" port "/_health"))))
    server))

(defn start
  ([]
   (start nil))
  ([env-config-map]
   (let [config (merge (load-config) env-config-map)
         _ (log/info "pg connection map:" (assoc (:postgresql config) :password "<redacted>"))]
     (swap! crux init-crux config)
     (swap! server init-server config))))



(defn read-db-secrets-from-env
  "Read DB secrets from JSON string injected as env var.
  Contains fields: host, port, dbname, username, password, dbClusterIdentifier and engine."
  [env-var]
  (let [{:keys [host port dbname username password]}
        (cheshire/decode env-var  keyword)]
    {:host host :port port
     :user username :password password
     :dbname dbname}))

(defn load-env-config []
  {:postgresql (read-db-secrets-from-env (System/getenv "LIPODB_SECRET"))

   ;; Read S3 bucket name injected as env var
   :attachments-bucket (System/getenv "ATTACHMENTS_NAME")})

(defn main [& _args]
  (log/info "LIPO is starting up.")
  (try
    (log/set-level! :info)
    (log/merge-config!
     {:appenders
      {:spit (appenders/spit-appender {:fname "lipo.log"})}})
    (start (load-env-config))
    (catch Throwable t
      (log/error t "LIPO FAILED TO START")
      (System/exit 1))))