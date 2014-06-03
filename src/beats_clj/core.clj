(ns beats-clj.core
  (:require [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [cheshire.core :as json]))

(def #^{:no-doc true} base-url "https://partner.api.beatsmusic.com/v1")
(def #^{:dynamic true :no-doc true} *app-key* false)
(def #^{:dynamic true :no-doc true} *app-secret* false)

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
  (http/get (str base-url url) {:timeout 200
                                :headers headers
                                :query-params params}))

(defmethod send-http :post [method url params headers]
  (http/post (str base-url url) {:timeout 200
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
  [method url params headers & {:keys [forget]}]
  (let [params (if (not (contains? params "client_id")) (assoc params :client_id *app-key*) params)
        headers (if (not headers) {} headers)
        response (send-http method url (clean-params params) headers)]
    (if (not forget)
        (try
          (let [body (->> (:body @response)
                          json/parse-string
                          clojure.walk/keywordize-keys)]
            (if (= (:code body) "OK")
              body
              false))
          (catch Exception e false)))))

(defn search
  "Search for tracks or albums."
  [q & {:keys [search-type entity-type]
        :or {search-type :standard}}]
  (let [endpoint {:standard "/api/search"
                  :federated "/api/search/federated"
                  :predictive "/api/search/predictive"}
        params (into {} (filter #(not (nil? (val %))) {:q q :type entity-type}))]
    (try
      (api-request :get (get endpoint search-type) params false)
    (catch Exception e {}))))

(defn track-get
  "Gets information about a specific track."
  [track-id]
  (try
    (api-request :get (str "/api/tracks/" + track-id) {} false)
  (catch Exception e {})))

(defn playlist-add
  "Add a given tracks to a given playlist; Adds track in batches of 25; Returns nil (auth required)"
  [playlist-id track-ids & {:keys [auth append]
                            :or {append true}}]
  (check-auth auth)
  (let [endpoint (str "/api/playlists/" playlist-id "/tracks")
        method {:update :put
                :append :post}
        headers {"Authorization" (str "Bearer" auth)}
        track-ids (filter #(= (subs % 0 2) "tr") track-ids)
        batches (partition-all 25 track-ids)]
    (map #(try
            (let [params (->> (map (fn [x] (str "track_ids=" x)) %)
                              (clojure.string/join "&"))]
              (api-request method endpoint params headers :forget true))
            (catch Exception e {})))))

(defn playlist-list
  "Show all playlists in the given account. (auth required)"
  [user-id & {:keys [auth offset limit order-by]
              :or {offset 0 limit 100 order-by "updated_at desc"}}]
  (check-auth auth)
  (let [endpoint (str "/api/users/" user-id "/playlists")
        params {:user_id user-id
                :offset offset
                :limit limit
                :order_by order-by}
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn playlist-get
  "Returns the contents of a given playlist. (auth required)"
  [playlist-id & {:keys [auth]}]
  (check-auth auth)
  (let [endpoint (str "/api/playlists/" playlist-id)
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint {} headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn playlist-create
  "Creates a new playlist. (auth required)"
  [playlist-name & {:keys [auth description access]}]
  (check-auth auth)
  (let [endpoint "/api/playlists"
        params {:name playlist-name
                :description description
                :access access}
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :post endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn token-request
  "Returns an auth token for a given user."
  [code redirect-uri]
  (let [params {:client_secret *app-secret*
                :client_id *app-key*
                :redirect_uri redirect-uri
                :code code
                :grant_type "authorization_code"}]
    (try
      (api-request :post "/oauth/token" params false)
      (catch Exception e {:error (.getMessage e)}))))

(defn me
  "Get the authenticated user's user_id. (auth required)"
  [& {:keys [auth]}]
  (check-auth auth)
  (try
    (api-request :get "/api/me" {} {"Authorization" (str "Bearer" auth)})
    (catch Exception e {:error (.getMessage e)})))

(defn library-list
  "Returns a page of tracks / albums / artists from the auth'd user's library. (auth required)"
  [user-id & {:keys [auth type offset limit order-by]
              :or {type :tracks}}]
  (check-auth auth)
  (let [params {:offset offset
                :limit limit
                :order_by order-by}
        endpoint (str "/api/users/" user-id "/mymusic/" (name type))
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn library-modify
  "Add or remove one or more tracks to your library; Returns nil (auth required)"
  [user-id track-ids & {:keys [action auth]
                        :or {action :remove}}]
  (check-auth auth)
  (let [type (if (= (type track-ids) "java.lang.String") :single :batch)
        endpoint (str "/api/users" user-id "/mymusic")
        method (if (= action :add)
                   (get {:single :put :batch :post} type)
                   :delete)
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (if (= type :single)
          (let [endpoint (str endpoint "/" track-ids)]
            (api-request method endpoint {} headers))
          (let [batches (partition-all 25 track-ids)]
            (map #(let [params (->> (map (fn [x] (str "ids=" x)) %)
                                    (clojure.string/join "&"))]
              (api-request method endpoint params headers :forget true)))))
      (catch Exception e {:error (.getMessage e)}))))

(defn library-add
  "Alias of library-modify with action set to :add. (auth required)"
  [user-id track-ids & {:keys [auth]}]
  (library-modify user-id track-ids :auth auth :action :add))

(defn library-remove
  "Alias of library-modify with action set to :remove. (auth required)"
  [user-id track-ids & {:keys [auth]}]
  (library-modify user-id track-ids :auth auth :action :remove))

(defn image
  "Returns default image for albums, genres, artists, tracks, playlists and users."
  [type id & {:keys [size]
              :or {size :medium}}]
  (let [type (filter #(= % type) [:albums :genres :artists :tracks :playlists :users])
        size (filter #(= % type) [:thumb :small :medium :large])]
    (if (or (empty? type) (empty? size))
        (throw (Exception. "Invalid size or type for beats/image"))
        (try
          (api-request :get (str "/api/" (name type) "/" id "/images/default") {:size size} {})
          (catch Exception e {:error (.getMessage e)})))))

(defn artist-get
  "Get an data object for a given artist."
  [artist-id]
  (try
    (api-request :get (str "/api/artists/" artist-id) {} {})
    (catch Exception e {:error (.getMessage e)})))

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
                :order_by order-by
                :filters (streamable-filters :now streamable :future future_streamable :never never_streamable)}]
    (try
      (api-request :get endpoint params {})
      (catch Exception e {:error (.getMessage e)}))))

(defn artist-list-tracks
  "Get a list of tracks for a given artist."
  [artist-id & {:keys [ offset limit order-by streamable future_streamable never_streamable]}]
  (let [endpoint (str "/api/artists/" artist-id "/tracks")
        params {:offset offset
                :limit limit
                :order_by order-by
                :filters (streamable-filters :now streamable :future future_streamable :never never_streamable)}]
    (try
      (api-request :get endpoint params {})
      (catch Exception e {:error (.getMessage e)}))))

(defn audio-get
  "Get an data object for a given artist. (auth required)"
  [track-id & {:keys [auth]}]
  (check-auth auth)
  (try
    (api-request :get (str "/api/tracks/" track-id "/audio") {} {"Authorization" (str "Bearer" auth)})
    (catch Exception e {:error (.getMessage e)})))

(defn genres
  "Get a list of all genres."
  [& {:keys [limit offset]}]
  (try
    (let [params {:limit limit
                  :offset offset}]
      (api-request :get "/api/genres" params {}))
    (catch Exception e {:error (.getMessage e)})))

(defn genre-get
  "Get an data object for a given artist."
  [genre-id]
  (try
    (api-request :get (str "/api/genres/" genre-id) {} {})
    (catch Exception e {:error (.getMessage e)})))

(defn genre-list
  "Get a list of items for a specific genre."
  [genre-id type & {:keys [limit offset]}]
  (let [type (filter #(= % type) [:editors_picks :featured :new_releases :bios :playlists])
        params {:limit limit
                :offset offset}
        endoint (str "/api/genres/" genre-id "/" (name type))]
    (if (empty? type)
        (throw (Exception. "Invalid type for beats/genre-list"))
        (try
          (api-request :get (str "/api/genres/" genre-id) params {})
          (catch Exception e {:error (.getMessage e)})))))

(defn recommendations
  "Get the list of recommended content for a user at a given time of day. (auth required)"
  [user-id & {:keys [auth timezone timestamp offset limit]
                                  :or {timezone "-0800"}}]
  (let [endpoint (str "/api/users/" user-id "/recs/just_for_you")
        headers {"Authorization" (str "Bearer" auth)}
        params {:time_zone timezone
                :offset offset
                :limit limit}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn sentence-options
  "Retrieve IDs for sentence creation. (auth required)"
  [user-id & {:keys [auth timezone timestamp]
              :or {timezone "-0800"}}]
  (check-auth auth)
  (let [endpoint (str "/api/users/" user-id "/recs/the_sentence_options")
        params {:time_zone timezone
                :timestamp timestamp}
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn sentence
  "Retrieve the tracks for a given sentence. (auth required)"
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
    (try
      (api-request :post endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

; http-kit, multi-threading, rate limiting