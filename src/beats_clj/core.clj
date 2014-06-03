(ns beats-clj.core
  (:require [clojure.java.io :as io]
            [clj-http.client :as http]
            [cheshire.core :as json]))

(def base-url "https://partner.api.beatsmusic.com/v1")
(def #^{:dynamic true} *app-key* false)
(def #^{:dynamic true} *app-secret* false)

(defn set-app-key! [app-key]
  (alter-var-root (var *app-key*) (fn [_] app-key)))

(defn set-app-secret! [app-secret]
  (alter-var-root (var *app-secret*) (fn [_] app-secret)))

(defmulti send-http (fn [method url params headers] method))

(defmethod send-http :get [method url params headers]
  (http/get (str base-url url) {:headers headers
                                :throw-exceptions false
                                :query-params params}))

(defmethod send-http :post [method url params headers]
  (http/post (str base-url url) {:headers headers
                                 :throw-exceptions false
                                 :form-params params}))

(defn- check-auth [token]
"Checks for auth token."
  (if (empty? token)
      (throw (Exception. "Missing authentication token!"))
      true))

(defn- clean-params [params]
"Removes any nil params."
  (->> params
       (filter #(not (nil? (val %))))
       (into {})))

(defn- api-request [method url params headers]
"Wrapper to HTTP client that makes the request to the API."
  (let [params (if (not (contains? params "client_id")) (assoc params :client_id *app-key*) params)
        headers (if (not headers) {} headers)
        response (send-http method url (clean-params params) headers)]
    (try
      (let [body (->> (:body response)
                      json/parse-string
                      clojure.walk/keywordize-keys)]
        (if (= (:code body) "OK")
          body
          false))
      (catch Exception e false))))

(defn search [q & {:keys [search-type entity-type]
                   :or {search-type :standard}}]
"Search for tracks or albums."
  (let [endpoint {:standard "/api/search"
                  :federated "/api/search/federated"
                  :predictive "/api/search/predictive"}
        params (into {} (filter #(not (nil? (val %))) {:q q :type entity-type}))]
    (try
      (api-request :get (get endpoint search-type) params false)
    (catch Exception e {}))))

(defn track-get [track-id]
"Gets information about a specific track."
  (try
    (api-request :get (str "/api/tracks/" + track-id) {} false)
  (catch Exception e {})))

(defn playlist-add [playlist-id track-ids & {:keys [auth append]
                                                :or {append true}}]
"Add a given tracks to a given playlist; Adds track in batches of 25. (auth required)"
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
              (api-request method endpoint params headers))
            (catch Exception e {})))))

(defn playlist-list [user-id & {:keys [auth offset limit order-by]
                                :or {offset 0 limit 100 order-by "updated_at desc"}}]
"Show all playlists in the given account. (auth required)"
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

(defn playlist-get [playlist-id & {:keys [auth]}]
"Returns the contents of a given playlist. (auth required)"
  (check-auth auth)
  (let [endpoint (str "/api/playlists/" playlist-id)
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint {} headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn playlist-create [playlist-name & {:keys [auth description access]}]
"Creates a new playlist. (auth required)"
  (check-auth auth)
  (let [endpoint "/api/playlists"
        params {:name playlist-name
                :description description
                :access access}
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :post endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn token-request [code redirect-uri]
"Returns an auth token for a given user."
  (let [params {:client_secret *app-secret*
                :client_id *app-key*
                :redirect_uri redirect-uri
                :code code
                :grant_type "authorization_code"}]
    (try
      (api-request :post "/oauth/token" params false)
      (catch Exception e {:error (.getMessage e)}))))

(defn me [& {:keys [auth]}]
"Get the authenticated user's user_id. (auth required)"
  (check-auth auth)
  (try
    (api-request :get "/api/me" {} {"Authorization" (str "Bearer" auth)})
    (catch Exception e {:error (.getMessage e)})))

(defn library-list [user-id & {:keys [auth type offset limit order-by]
                               :or {type :tracks}}]
"Returns a page of tracks / albums / artists from the auth'd user's library. (auth required)"
  (check-auth auth)
  (let [params {:offset offset
                :limit limit
                :order_by order-by}
        endpoint (str "/api/users/" user-id "/mymusic/" (name type))
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn library-modify [user-id track-ids & {:keys [action auth]
                                           :or {action :remove}}]
"Add or remove one or more tracks to your library. (auth required)"
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
              (api-request method endpoint params headers)))))
      (catch Exception e {:error (.getMessage e)}))))

(defn library-add [user-id track-ids & {:keys [auth]}]
"Alias of library-modify with action set to :add. (auth required)"
  (library-modify user-id track-ids :auth auth :action :add))

(defn library-remove [user-id track-ids & {:keys [auth]}]
"Alias of library-modify with action set to :remove. (auth required)"
  (library-modify user-id track-ids :auth auth :action :remove))

(defn image [type id & {:keys [size]
                        :or {size :medium}}]
"Returns default image for albums, genres, artists, tracks, playlists and users."
  (let [type (filter #(= % type) [:albums :genres :artists :tracks :playlists :users])
        size (filter #(= % type) [:thumb :small :medium :large])]
    (if (or (empty? type) (empty? size))
        (throw (Exception. "Invalid size or type for beats/image"))
        (try
          (api-request :get (str "/api/" (name type) "/" id "/images/default") {:size size} {})
          (catch Exception e {:error (.getMessage e)})))))

(defn artist-get [artist-id]
"Get an data object for a given artist."
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

(defn artist-list-albums [artist-id & {:keys [essential offset limit order-by streamable future_streamable never_streamable]
                                      :or [essential false]}]
"Get a list of albums for a given artist."
  (let [endpoint (str "/api/artists/" artist-id "/" (when essential "essential_") "albums")
        params {:offset offset
                :limit limit
                :order_by order-by
                :filters (streamable-filters :now streamable :future future_streamable :never never_streamable)}]
    (try
      (api-request :get endpoint params {})
      (catch Exception e {:error (.getMessage e)}))))

(defn artist-list-tracks [artist-id & {:keys [ offset limit order-by streamable future_streamable never_streamable]}]
"Get a list of tracks for a given artist."
  (let [endpoint (str "/api/artists/" artist-id "/tracks")
        params {:offset offset
                :limit limit
                :order_by order-by
                :filters (streamable-filters :now streamable :future future_streamable :never never_streamable)}]
    (try
      (api-request :get endpoint params {})
      (catch Exception e {:error (.getMessage e)}))))

(defn audio-get [track-id & {:keys [auth]}]
"Get an data object for a given artist. (auth required)"
  (check-auth auth)
  (try
    (api-request :get (str "/api/tracks/" track-id "/audio") {} {"Authorization" (str "Bearer" auth)})
    (catch Exception e {:error (.getMessage e)})))

(defn genres [& {:keys [limit offset]}]
"Get a list of all genres."
  (try
    (let [params {:limit limit
                  :offset offset}]
      (api-request :get "/api/genres" params {}))
    (catch Exception e {:error (.getMessage e)})))

(defn genre-get [genre-id]
"Get an data object for a given artist."
  (try
    (api-request :get (str "/api/genres/" genre-id) {} {})
    (catch Exception e {:error (.getMessage e)})))

(defn genre-list [genre-id type & {:keys [limit offset]}]
"Get a list of items for a specific genre."
  (let [type (filter #(= % type) [:editors_picks :featured :new_releases :bios :playlists])
        params {:limit limit
                :offset offset}
        endoint (str "/api/genres/" genre-id "/" (name type))]
    (if (empty? type)
        (throw (Exception. "Invalid type for beats/genre-list"))
        (try
          (api-request :get (str "/api/genres/" genre-id) params {})
          (catch Exception e {:error (.getMessage e)})))))

(defn recommendations [user-id & {:keys [auth timezone timestamp offset limit]
                                  :or {timezone "-0800"}}]
"Get the list of recommended content for a user at a given time of day. (auth required)"
  (let [endpoint (str "/api/users/" user-id "/recs/just_for_you")
        headers {"Authorization" (str "Bearer" auth)}
        params {:time_zone timezone
                :offset offset
                :limit limit}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn sentence-options [user-id & {:keys [auth timezone timestamp]
                                   :or {timezone "-0800"}}]
"Retrieve IDs for sentence creation. (auth required)"
  (check-auth auth)
  (let [endpoint (str "/api/users/" user-id "/recs/the_sentence_options")
        params {:time_zone timezone
                :timestamp timestamp}
        headers {"Authorization" (str "Bearer" auth)}]
    (try
      (api-request :get endpoint params headers)
      (catch Exception e {:error (.getMessage e)}))))

(defn sentence [user-id place-id activity-id people-id genre-id & {:keys [auth timezone skipped]}]
"Retrieve the tracks for a given sentence. (auth required)"
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