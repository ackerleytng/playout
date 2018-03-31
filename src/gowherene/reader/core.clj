(ns gowherene.reader.core
  (:require [hickory.core :refer [as-hickory parse]]
            [hickory.zip :refer [hickory-zip]]
            [clojure.zip :as zip]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hickory.select :as s]
            [clj-http.client :as raw-client]
            [environ.core :refer [env]]
            [medley.core :refer [take-upto distinct-by]]
            [slingshot.slingshot :refer [try+ throw+]]
            [gowherene.reader.tagger :as tagger]
            [gowherene.reader.client :as client]))

(def re-postal-code
  "Regex that matches Singapore postal codes.
     According to URA, the largest postal code prefix in Singapore is 83
     (74 is not a valid prefix, but it is included in this regex)"
  #"\b(?:[0-7][0-9]|8[0-3])\d{4}\b")

(def re-address
  "Regex to match for address labels in text"
  #"[Aa]ddress:?")

(def re-spaces
  "Regex to be used to replace all &nbsp;s as well as spaces"
  #"[\u00a0\s]+")

(defn get-all-tags
  "Given a hickory, get all the tags in this hickory"
  ([hickory] (get-all-tags (hickory-zip hickory) #{}))
  ([loc tags]
   (cond
     (zip/end? loc) tags
     :else (let [tag (:tag (zip/node loc))]
             (recur (zip/next loc) (conj tags tag))))))

(defn remove-tags
  "Given a hickory, return a hickory without the tags in the set to-remove"
  [to-remove hickory]
  (loop [loc (hickory-zip hickory)]
    (if (zip/end? loc)
      ;; zip/root returns just the node, not a full zipper
      (zip/root loc)
      (recur (zip/next
              (if (to-remove (:tag (zip/node loc)))
                (zip/remove loc)
                loc))))))

(def address-cap
  "We use (s/has-child (s/has-child (s/find-in-text re-address)))
     to match the `Address: ` label followed by the actual address.
     Since re-address could potentially match stray `address` words in text,
     we cap this search at address-cap.
  <blah>
    <blah0>
      <blah>Address: </blah>
    </blah0>
    <blah1 />
    <blah... We don't want too many of these here./>
    <blah... We don't want too many of these here./>
    <blah.address-cap+1 />
  </blah>"
  20)

(defn address-label-like
  [hzip-loc]
  (some #(and
          ;; Matches re-address
          (re-find re-address %)
          ;; Does not have too many words (not likely to be labels)
          (< (tagger/count-words %) 4))
        (->> (zip/node hzip-loc)
             :content
             (filter string?))))

;; TODO rename this function
(defn get-postal-code-locs
  "Given a hickory, find all the locs containing postal codes"
  [hickory]
  (let [;; Contains postal code
        locs-postal-code (s/select-locs
                          (s/find-in-text re-postal-code) hickory)
        ;; Find regions of content near labels like "Address"
        locs-address (s/select-locs
                      (s/or (s/has-child (s/has-child address-label-like))
                            (s/has-child address-label-like)) hickory)
        locs-address-filtered (filter #(> address-cap ((comp count :content zip/node) %))
                                      locs-address)]
    (clojure.set/union (set locs-postal-code) (set locs-address-filtered))))

(defn get-earlier-header
  "Given a loc, find the header just above or before this loc.
  Limit the search backwards to earlier-header-steps"
  [hloc-zip]
  (s/prev-pred hloc-zip
               (apply s/or (map s/tag [:h1 :h2 :h3 :h4]))))

(defn get-content
  "Given a node, return all content in a string, until the first <br>
     or the end of this tree of tags
   The aux function returns a pair (string should-stop) where string
     is the data to be accumulated and should-stop stops execution if necessary"
  [node]
  (first ((fn aux [n]
            (cond (= (:tag n) :br) (list "" true)
                  (= (:type n) :element)
                  (let [useful (take-upto second (map aux (:content n)))]
                    (list (str/join (map first useful))
                          (some second useful)))
                  :else (list n false))) node)))

(defn loc->addresses
  [loc]
  (let [addresses (->> loc
                       tagger/loc->buckets
                       tagger/buckets->addresses)]
    (if (seq addresses)
      ;; If addresses is not empty
      (let [max-address-value (second (apply max-key second addresses))]
        (->> addresses
             (filter #(= max-address-value (second %)))
             (map first)))
      addresses)))

(def uninteresting-tags #{:ins :script :noscript :img :iframe :head :link :footer :header})

(defn update-if-exists
  [map key f]
  (if (key map)
    (update map key f)
    map))

(defn simplify-datum
  "Use this to reduce the verbosity of datum (good for pprinting)"
  ([datum] (simplify-datum 0 datum))
  ([verbosity datum]
   (let [locs [:postal-code-loc :header-loc]]
     (cond
       (> verbosity 1) datum
       (> verbosity 0) (reduce #(update-if-exists %1 %2 zip/node) datum locs)
       :else (reduce #(update-if-exists %1 %2 tagger/count-locs-walked) datum locs)))))

(defn tag-with
  [tag info & datum]
  (assoc datum tag info))

(defn update-with-tag
  "Given an old tag in a map m,
     get the value for the old tag in m,
     apply f on it,
     associate the new value back into m with key new-tag."
  [new-tag old-tag f m]
  (let [old-info (old-tag m)
        new-info (f old-info)]
    (assoc m new-tag new-info)))

(defn update-with-tag-seq
  "Given an old tag in a map m,
     get the value for the old tag in m,
     apply f on it, (f returns a seq)
     clone m and
     associate the new value back into m's clones with key new-tag."
  [new-tag old-tag f m]
  (let [old-info (old-tag m)
        new-info (f old-info)]
    (map (partial assoc m new-tag) new-info)))

(defn loc->place
  [loc]
  (-> loc
      zip/node
      get-content
      (str/replace re-spaces " ")
      str/trim))

(defn gather-address-info
  "Takes a hickory and returns a data of all the places and addresses on the page"
  [hickory]
  (->> hickory
       (remove-tags uninteresting-tags)
       get-postal-code-locs
       (map (partial tag-with :postal-code-loc))
       (map (partial update-with-tag :header-loc :postal-code-loc get-earlier-header))
       ;; If we can't find the header, don't display it
       ;; (filter :header-loc)
       (map (partial update-with-tag :place :header-loc loc->place))
       ;; Uncomment the following two for debugging
       ;; (map (partial update-with-tag :buckets :postal-code-loc tagger/loc->buckets))
       ;; (map (partial update-with-tag :addresses :buckets tagger/buckets->addresses))
       (mapcat (partial update-with-tag-seq :address :postal-code-loc loc->addresses))
       ;; Some postal-code-locs are misidentified, hence addresses cannot be found
       (filter :address)))

(defn geocode-google
  ([address]
   ;; Default to 3 tries
   (geocode-google address 3))
  ([address tries]
   (let [response (try+ (-> (raw-client/get
                             "https://maps.googleapis.com/maps/api/geocode/json"
                             {:query-params {:address address
                                             :key (env :google-api-token)}})
                            :body
                            json/read-json)
                        (catch [:status 400] _
                          (println (str "Issue geocoding |" address "|"))))
         status (:status response)]
     (cond
       (nil? status)
       response

       (= "OK" status)
       (get-in response [:results 0 :geometry :location])

       (and (= "OVER_QUERY_LIMIT" status) (> tries 0))
       (recur address (dec tries))

       (and (= "OVER_QUERY_LIMIT" status) (zero? tries))
       (println (str "Over query limit while geocoding |" address "|"))

       :else (println (str status " while geocoding |" address "|"))))))

(defn geocode-onemap
  [postal-code]
  (let [response (-> (raw-client/get
                      "https://developers.onemap.sg/commonapi/search"
                      {:query-params {:searchVal postal-code
                                      :returnGeom "Y"
                                      :getAddrDetails "Y"}})
                     :body
                     json/read-json)]
    (when (pos? (:found response))
      (let [result (get-in response [:results 0])]
        {:lat (Float/parseFloat (:LATITUDE result))
         :lng (Float/parseFloat (:LONGITUDE result))}))))

(defn geocode
  [address]
  (when address
    (if-let [postal-code (re-find re-postal-code address)]
      (or (geocode-onemap postal-code)
          ;; Fallback to google
          (geocode-google address))
      (geocode-google address))))

(defn get-index [header]
  (and header
       (if-let [num (re-find #"(\d+)\." header)]
         (Integer/parseInt (get num 1))
         nil)))

(defn publish
  [data]
  (->> data
       (filter #(:latlng %))
       (map #(select-keys % [:place :address :latlng]))))

(defn- dedupe-data-retain-longer-names
  "Given data, this function removes the location with the shorter name
  if two or more locations have identical addresses"
  [data]
  (let [groups (group-by :address data)
        partitions (group-by (fn [[a g]] (> (count g) 1)) groups)
        uniques (map #(get-in % [1 0]) (partitions false))
        longer-names (->> (partitions true)
                          (map second)
                          (map #(sort-by (comp count :place) > %))
                          (map first))]
    (lazy-cat uniques longer-names)))

(defn data-add-geocoding
  "Adds geocoding to data
  Tries to minimize the number of geocoding requests by doing necessary deduplication first"
  [data]
  (->> data
       (distinct-by (fn [d] [(:place d) (:address d)]))
       dedupe-data-retain-longer-names
       (pmap (partial update-with-tag :latlng :address geocode))))

(defn process
  [hickory]
  (let [raw-result (-> hickory
                       gather-address-info
                       data-add-geocoding)
        result (publish raw-result)]
    (pprint (->> raw-result
                 (map (partial simplify-datum))
                 (sort-by (comp get-index :place))))
    result))

(defn do-retrieve
  [url]
  (let [{:keys [status body]} (client/retrieve url)]
    (if (= status 200)
      {:error nil :data body}
      {:error (str "Couldn't retrieve url! (" status ")") :data nil})))

(defn handle
  [url]
  (println "incoming" url)
  (if url
    (let [{:keys [error data] :as r} (do-retrieve url)]
      (if error r
          (try
            (let [results (-> data
                              parse
                              as-hickory
                              process)]
              (if (zero? (count results))
                {:error "Couldn't find any addresses! :(" :data nil}
                {:error nil :data results}))
            (catch Exception e
              {:error (str "Error while reading requested page: (" (.getMessage e) ")")
               :data nil}))))
    {:error "Missing url!" :data nil}))
