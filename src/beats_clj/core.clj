(ns beats-clj.core
  (:require [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.walk :as walk]))

; Config Parameters
(def #^{:no-doc true} base-url "https://partner.api.beatsmusic.com")
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

(defmethod send-http :put [method url params headers]
  (http/put (str base-url url) {:timeout 2000
                                 :headers headers
                                 :query-params params}))

(defmethod send-http :delete [method url params headers]
  (http/delete (str base-url url) {:timeout 2000
                                 :headers headers
                                 :query-params params}))

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
  [method url params auth-token & {:keys [forget resp headers]
                                   :or {resp :json}}]
  (when (not *app-secret*) (throw (Exception. "Missing Beats app secret!")))
  (when (not *app-key*) (throw (Exception. "Missing Beats app key!")))
  (when (> (swap! rate-counter inc) max-per-second)
        (Thread/sleep 1000))
  (let [params (if (not (contains? params "client_id")) (assoc params :client_id *app-key*) params)
        headers (if (not headers) {} headers)
        headers (if auth-token (assoc headers "Authorization" (str "Bearer " auth-token)) headers)
        response (send-http method url (clean-params params) headers)]
    (if (not forget)
        (if (get-in @response [:headers :x-mashery-error-code])
            (throw (Exception. (get-in @response [:headers :x-mashery-error-code])))
            (try
              (case resp
                :json (let [body (->> (:body @response)
                                      json/parse-string
                                      walk/keywordize-keys)]
                        (if (= (:code body) "OK")
                            body
                            (throw (Exception. (if (:message body) (:message body)
                                                                   "Error or blank response from Beats Platform.")))))
                :raw @response
                :url {:url (get-in @response [:opts :url])})
              (catch Exception e {:error (.getMessage e)})))
        true)))

(defn search
  "Search for tracks or albums."
  [q & {:keys [search-type entity-type]
        :or {search-type :standard}}]
  (let [endpoint {:standard "/v1/api/search"
                  :federated "/v1/api/search/federated"
                  :predictive "/v1/api/search/predictive"}
        entity-type (first (filter #(= % entity-type) [:genre :artist :album, :track, :playlist, :user]))
        params {:q q
                :type (if entity-type (name entity-type) nil)}]
      (if (and (nil? entity-type) (= search-type :standard))
          (throw (Exception. "Invalid entity-type specified for standard search."))
          (api-request :get (get endpoint search-type) params false))))

(defn track-get
  "Gets information about a specific track."
  [track-id]
  (api-request :get (str "/v1/api/tracks/" track-id) {} false))

(defn playlist-add
  "Add a given tracks to a given playlist; Adds track in batches of 25; Returns nil (Requires auth token.)"
  [playlist-id track-ids & {:keys [auth mode async]
                            :or {mode :append async true}}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/playlists/" playlist-id "/tracks")
        method (get {:update :put :append :post} mode :post)
        track-ids (filter #(= (subs % 0 2) "tr") track-ids)
        batches (partition-all 25 track-ids)
        forget (if async true false)]
    (map #(let [query (->> (map (fn [x] (str "track_ids=" x)) %)
                           (clojure.string/join "&"))]
              (api-request method (str endpoint "?" query) {} auth :forget forget)) batches)))

(defn playlist-list
  "Show all playlists in the given account. (Requires auth token.)"
  [user-id & {:keys [auth offset limit order-by]
              :or {offset 0 limit 100 order-by "updated_at desc"}}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/users/" user-id "/playlists")
        params {:user_id user-id
                :offset offset
                :limit limit
                :order_by order-by}]
    (api-request :get endpoint params auth)))

(defn playlist-get
  "Returns the contents of a given playlist. (Requires auth token.)"
  [playlist-id & {:keys [auth]}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/playlists/" playlist-id)]
    (api-request :get endpoint {} auth)))

(defn playlist-create
  "Creates a new playlist. (Requires auth token.)"
  [playlist-name & {:keys [auth description access]}]
  (check-auth auth)
  (let [endpoint "/v1/api/playlists"
        params {:name playlist-name
                :description description
                :access (when access (name access))}]
    (api-request :post endpoint params auth)))

(defn playlist-update
  "Updates a given playlist. (Requires auth token.)"
  [playlist-id playlist-name & {:keys [auth description access]}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/playlists/" playlist-id)
        params {:name playlist-name
                :description description
                :access (when access (name access))}]
    (api-request :put endpoint params auth)))

(defn playlist-delete
  "Deletes a new playlist. (Requires auth token.)"
  [playlist-id & {:keys [auth]}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/playlists/" playlist-id)]
    (api-request :delete endpoint {} auth)))

(defn token-request
  "Returns an auth token for a given user."
  [code redirect-uri]
  (let [params {:client_secret *app-secret*
                :client_id *app-key*
                :redirect_uri redirect-uri
                :code code
                :grant_type "authorization_code"}]
    (api-request :post "/oauth2/token" params false)))

(defn me
  "Get the authenticated user's user_id. (Requires auth token.)"
  [& {:keys [auth]}]
  (check-auth auth)
  (api-request :get "/v1/api/me" {} auth))

(defn library-list
  "Returns a page of tracks / albums / artists from the auth'd user's library. (Requires auth token.)"
  [user-id & {:keys [auth type offset limit order-by]
              :or {type :tracks}}]
  (check-auth auth)
  (let [params {:offset offset
                :limit limit
                :order_by order-by}
        endpoint (str "/v1/api/users/" user-id "/mymusic/" (name type))]
    (api-request :get endpoint params auth)))

(defn library-modify
  "Add or remove one or more tracks to your library; Returns nil (Requires auth token.)"
  [user-id track-ids & {:keys [action auth async]
                        :or {action :add async true}}]
  (check-auth auth)
  (let [track-ids (if (not (coll? track-ids)) (vector track-ids) track-ids)
        endpoint (str "/v1/api/users/" user-id "/mymusic")
        method (if (= action :add) :post :delete)
        batches (partition-all 25 track-ids)
        forget (if async true false)]
    (map #(let [query (->> (map (fn [x] (str "ids=" x)) %)
                           (clojure.string/join "&"))]
            (api-request method (str endpoint "?" query) {} auth :forget forget)) batches)))

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
        endpoint (str "/v1/api/" (name type) "/" id "/images/default")]
    (if (or (nil? type) (nil? size))
        (throw (Exception. "Invalid size or type for beats/image"))
        (api-request :get endpoint {:size (name size)} false :resp :url))))

(defn artist-get
  "Get an data object for a given artist."
  [artist-id]
  (api-request :get (str "/v1/api/artists/" artist-id) {} false))

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
  (let [endpoint (str "/v1/api/artists/" artist-id "/" (when essential "essential_") "albums")
        params {:offset offset
                :limit limit
                :order_by order-by}
        filters (streamable-filters :now streamable :future future_streamable :never never_streamable)]
    (api-request :get (str endpoint "?" filters) params false)))

(defn artist-list-tracks
  "Get a list of tracks for a given artist."
  [artist-id & {:keys [offset limit order-by streamable future_streamable never_streamable]}]
  (let [endpoint (str "/v1/api/artists/" artist-id "/tracks")
        params {:offset offset
                :limit limit
                :order_by order-by}
        filters (streamable-filters :now streamable :future future_streamable :never never_streamable)]
    (api-request :get (str endpoint "?" filters) params false)))

(defn audio-get
  "Get an data object for a given artist. (Requires auth token.)"
  [track-id & {:keys [auth]}]
  (check-auth auth)
  (api-request :get (str "/v1/api/tracks/" track-id "/audio") {} auth))

(defn genres
  "Get a list of all genres."
  [& {:keys [limit offset]}]
  (let [params {:limit limit
                  :offset offset}]
      (api-request :get "/v1/api/genres" params false)))

(defn genre-get
  "Get an data object for a given artist. (Requires auth token.)"
  [genre-id & {:keys [auth]}]
  (api-request :get (str "/v1/api/genres/" genre-id) {} auth))

(defn genre-list
  "Get a list of items for a specific genre. (Requires auth token.)"
  [genre-id type & {:keys [auth limit offset]}]
  (let [type (first (filter #(= % type) [:editors_picks :featured :new_releases :bios :playlists]))
        params {:limit limit
                :offset offset}
        endoint (str "/v1/api/genres/" genre-id "/" (name type))]
    (if (nil? type)
        (throw (Exception. "Invalid type for beats/genre-list"))
        (api-request :get (str "/api/genres/" genre-id) params auth))))

(defn recommendations
  "Get the list of recommended content for a user at a given time of day. (Requires auth token.)"
  [user-id & {:keys [auth timezone timestamp offset limit]
                                  :or {timezone "-0800"}}]
  (let [endpoint (str "/v1/api/users/" user-id "/recs/just_for_you")
        params {:time_zone timezone
                :offset offset
                :limit limit}]
    (api-request :get endpoint params auth)))

(defn sentence-options
  "Retrieve IDs for sentence creation. (Requires auth token.)"
  [user-id & {:keys [auth timezone timestamp]
              :or {timezone "-0800"}}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/users/" user-id "/recs/the_sentence_options")
        params {:time_zone timezone
                :timestamp timestamp}]
    (api-request :get endpoint params auth)))

(defn sentence
  "Retrieve the tracks for a given sentence. (Requires auth token.)"
  [user-id place-id activity-id people-id genre-id & {:keys [auth timezone skipped]
                                                      :or {timezone "-0800"}}]
  (check-auth auth)
  (let [endpoint (str "/v1/api/users/" user-id "/recs/the_sentence")
        params {:time_zone timezone
                :skipped skipped
                :place place-id
                :activity activity-id
                :people people-id
                :genre genre-id}]
    (api-request :post endpoint params auth)))
