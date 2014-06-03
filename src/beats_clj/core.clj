(ns beats-clj.core
  (:require [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [cheshire.core :as json]))

; Config Parameters
(def #^{:no-doc true} base-url "https://partner.api.beatsmusic.com/v1")
(def #^{:dynamic true :no-doc true} *app-key* false)
(def #^{:dynamic true :no-doc true} *app-secret* false)

; Rate Limiting
(def #^{:no-doc true} rate-counter (atom 0))
(def #^{:no-doc true} max-per-second 15)
(future (while true (do (Thread/sleep 1000) (reset! rate-counter 0))))

; Main
(defn set-app-key!
  "Set the application (client) key (ID) for the library."
  [app-key]
  (alter-var-root (var *app-key*) (fn [_] app-key)))

(defn set-app-secret!
  "Set the application secret for the library."
  [app-secret]
  (alter-var-root (var *app-secret*) (fn [_] app-secret)))

(defmulti ^:no-doc send-http (fn [method url params headers] method))

(defmethod send-http :get [method url params headers]
  (http/get (str base-url url) {:timeout 2000
                                :headers headers
                                :query-params params}))

(defmethod send-http :post [method url params headers]
  (http/post (str base-url url) {:timeout 2000
                                 :headers headers
                                 :form-params params}))

(defn- check-auth
  "Checks for auth token."
  [token]
  (if (empty? token)
      (throw (Exception. "Missing authentication token!"))
      true))

(defn- clean-params
  "Removes any nil params."
  [params]
  (->> params
       (filter #(not (nil? (val %))))
       (into {})))

(defn- api-request
  "Wrapper to HTTP client that makes the request to the API."
  [method url params headers & {:keys [forget resp]
                                :or {resp :json}}]
  (when (not *app-secret*) (throw (Exception. "Missing Beats app secret!")))
  (when (not *app-key*) (throw (Exception. "Missing Beats app key!")))
  (when (> (swap! rate-counter inc) max-per-second)
        (Thread/sleep 1000))
  (let [params (if (not (contains? params "client_id")) (assoc params :client_id *app-key*) params)
        headers (if (not headers) {} headers)
        response (send-http method url (clean-params params) headers)]
    (if (not forget)
        (try
          (case resp
            :json (let [body (->> (:body @response)
                            json/parse-string
                            clojure.walk/keywordize-keys)]
                    (if (= (:code body) "OK")
                        body
                        (throw (Exception. (:message body)))))
            :raw @response
            :url {:url (get-in @response [:opts :url])})
          (catch Exception e {:error (.getMessage e)})))))

(defn search
  "Search for tracks or albums."
  [q & {:keys [search-type entity-type]
        :or {search-type :standard}}]
  (let [endpoint {:standard "/api/search"
                  :federated "/api/search/federated"
                  :predictive "/api/search/predictive"}
        params {:q q
                :type entity-type}]
    (api-request :get (get endpoint search-type) params false)))

(defn track-get
  "Gets information about a specific track."
  [track-id]
  (api-request :get (str "/api/tracks/" track-id) {} false))

(defn playlist-add
  "Add a given tracks to a given playlist; Adds track in batches of 25; Returns nil (Requires auth token.)"
  [playlist-id track-ids & {:keys [auth append]
                            :or {append true}}]
  (check-auth auth)
  (let [endpoint (str "/api/playlists/" playlist-id "/tracks")
        method {:update :put
                :append :post}
        headers {"Authorization" (str "Bearer" auth)}
        track-ids (filter #(= (subs % 0 2) "tr") track-ids)
        batches (partition-all 25 track-ids)]
    (map #((let [params (->> (map (fn [x] (str "track_ids=" x)) %)
                              (clojure.string/join "&"))]
              (api-request method endpoint params headers :forget true))))))

(defn playlist-list
  "Show all playlists in the given account. (Requires auth token.)"
  [user-id & {:keys [auth offset limit order-by]
              :or {offset 0 limit 100 order-by "updated_at desc"}}]
  (check-auth auth)
  (let [endpoint (str "/api/users/" user-id "/playlists")
        params {:user_id user-id
                :offset offset
                :limit limit
                :order_by order-by}
        headers {"Authorization" (str "Bearer" auth)}]
    (api-request :get endpoint params headers)))

(defn playlist-get
  "Returns the contents of a given playlist. (Requires auth token.)"
  [playlist-id & {:keys [auth]}]
  (check-auth auth)
  (let [endpoint (str "/api/playlists/" playlist-id)
        headers {"Authorization" (str "Bearer" auth)}]
    (api-request :get endpoint {} headers)))

(defn playlist-create
  "Creates a new playlist. (Requires auth token.)"
  [playlist-name & {:keys [auth description access]}]
  (check-auth auth)
  (let [endpoint "/api/playlists"
        params {:name playlist-name
                :description description
                :access access}
        headers {"Authorization" (str "Bearer" auth)}]
    (api-request :post endpoint params headers)))

(defn token-request
  "Returns an auth token for a given user."
  [code redirect-uri]
  (let [params {:client_secret *app-secret*
                :client_id *app-key*
                :redirect_uri redirect-uri
                :code code
                :grant_type "authorization_code"}]
    (api-request :post "/oauth/token" params false)))

(defn me
  "Get the authenticated user's user_id. (Requires auth token.)"
  [& {:keys [auth]}]
  (check-auth auth)
  (api-request :get "/api/me" {} {"Authorization" (str "Bearer" auth)}))

(defn library-list
  "Returns a page of tracks / albums / artists from the auth'd user's library. (Requires auth token.)"
  [user-id & {:keys [auth type offset limit order-by]
              :or {type :tracks}}]
  (check-auth auth)
  (let [params {:offset offset
                :limit limit
                :order_by order-by}
        endpoint (str "/api/users/" user-id "/mymusic/" (name type))
        headers {"Authorization" (str "Bearer" auth)}]
    (api-request :get endpoint params headers)))

(defn library-modify
  "Add or remove one or more tracks to your library; Returns nil (Requires auth token.)"
  [user-id track-ids & {:keys [action auth]
                        :or {action :remove}}]
  (check-auth auth)
  (let [type (if (= (type track-ids) "java.lang.String") :single :batch)
        endpoint (str "/api/users" user-id "/mymusic")
        method (if (= action :add)
                   (get {:single :put :batch :post} type)
                   :delete)
        headers {"Authorization" (str "Bearer" auth)}]
    (if (= type :single)
        (let [endpoint (str endpoint "/" track-ids)]
          (api-request method endpoint {} headers))
        (let [batches (partition-all 25 track-ids)]
          (map #(let [params (->> (map (fn [x] (str "ids=" x)) %)
                                  (clojure.string/join "&"))]
            (api-request method endpoint params headers :forget true)))))))

(defn library-add
  "Alias of library-modify with action set to :add. (Requires auth token.)"
  [user-id track-ids & {:keys [auth]}]
  (library-modify user-id track-ids :auth auth :action :add))

(defn library-remove
  "Alias of library-modify with action set to :remove. (Requires auth token.)"
  [user-id track-ids & {:keys [auth]}]
  (library-modify user-id track-ids :auth auth :action :remove))

(defn image
  "Returns default image for albums, genres, artists, tracks, playlists and users."
  [type id & {:keys [size]
              :or {size :medium}}]
  (let [type (first (filter #(= % type) [:albums :genres :artists :tracks :playlists :users]))
        size (first (filter #(= % size) [:thumb :small :medium :large]))
        endpoint (str "/api/" (name type) "/" id "/images/default")]
    (if (or (nil? type) (nil? size))
        (throw (Exception. "Invalid size or type for beats/image"))
        (api-request :get endpoint {:size (name size)} {} :resp :url))))

(defn artist-get
  "Get an data object for a given artist."
  [artist-id]
  (api-request :get (str "/api/artists/" artist-id) {} {}))

(defn- streamable-filters [& {:keys [now future never]}]
  (let [streamable {:streamable (if now "true" "false")
                    :future_streamable (if future "true" "false")
                    :never_streamable (if never "true" "false")}]
    (->> streamable
         (map #(str "filters=" (name (key %)) ":" (val %)))
         (clojure.string/join "&"))))

(defn artist-list-albums
  "Get a list of albums for a given artist."
  [artist-id & {:keys [essential offset limit order-by streamable future_streamable never_streamable]
                :or [essential false]}]
  (let [endpoint (str "/api/artists/" artist-id "/" (when essential "essential_") "albums")
        params {:offset offset
                :limit limit
                :order_by order-by}
        filters (streamable-filters :now streamable :future future_streamable :never never_streamable)]
    (api-request :get (str endpoint "?" filters) params {})))

(defn artist-list-tracks
  "Get a list of tracks for a given artist."
  [artist-id & {:keys [offset limit order-by streamable future_streamable never_streamable]}]
  (let [endpoint (str "/api/artists/" artist-id "/tracks")
        params {:offset offset
                :limit limit
                :order_by order-by}
        filters (streamable-filters :now streamable :future future_streamable :never never_streamable)]
    (api-request :get (str endpoint "?" filters) params {})))

(defn audio-get
  "Get an data object for a given artist. (Requires auth token.)"
  [track-id & {:keys [auth]}]
  (check-auth auth)
  (api-request :get (str "/api/tracks/" track-id "/audio") {} {"Authorization" (str "Bearer" auth)}))

(defn genres
  "Get a list of all genres."
  [& {:keys [limit offset]}]
  (let [params {:limit limit
                  :offset offset}]
      (api-request :get "/api/genres" params {})))

(defn genre-get
  "Get an data object for a given artist. (Requires auth token.)"
  [genre-id & {:keys [auth]}]
  (api-request :get (str "/api/genres/" genre-id) {} {"Authorization" (str "Bearer" auth)}))

(defn genre-list
  "Get a list of items for a specific genre. (Requires auth token.)"
  [genre-id type & {:keys [auth limit offset]}]
  (let [type (first (filter #(= % type) [:editors_picks :featured :new_releases :bios :playlists]))
        params {:limit limit
                :offset offset}
        endoint (str "/api/genres/" genre-id "/" (name type))
        headers {"Authorization" (str "Bearer" auth)}]
    (if (nil? type)
        (throw (Exception. "Invalid type for beats/genre-list"))
        (api-request :get (str "/api/genres/" genre-id) params headers))))

(defn recommendations
  "Get the list of recommended content for a user at a given time of day. (Requires auth token.)"
  [user-id & {:keys [auth timezone timestamp offset limit]
                                  :or {timezone "-0800"}}]
  (let [endpoint (str "/api/users/" user-id "/recs/just_for_you")
        headers {"Authorization" (str "Bearer" auth)}
        params {:time_zone timezone
                :offset offset
                :limit limit}]
    (api-request :get endpoint params headers)))

(defn sentence-options
  "Retrieve IDs for sentence creation. (Requires auth token.)"
  [user-id & {:keys [auth timezone timestamp]
              :or {timezone "-0800"}}]
  (check-auth auth)
  (let [endpoint (str "/api/users/" user-id "/recs/the_sentence_options")
        params {:time_zone timezone
                :timestamp timestamp}
        headers {"Authorization" (str "Bearer" auth)}]
    (api-request :get endpoint params headers)))

(defn sentence
  "Retrieve the tracks for a given sentence. (Requires auth token.)"
  [user-id place-id activity-id people-id genre-id & {:keys [auth timezone skipped]}]
  (check-auth auth)
  (let [endpoint (str "/api/users/" user-id "/recs/the_sentence_options")
        params {:time_zone timezone
                :skipped skipped
                :place place-id
                :activity activity-id
                :people people-id
                :genre genre-id}
        headers {"Authorization" (str "Bearer" auth)}]
    (api-request :post endpoint params headers)))
