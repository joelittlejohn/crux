(ns crux.lucene
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [crux.checkpoint :as cp]
            [crux.codec :as cc]
            [crux.db :as db]
            [crux.io :as cio]
            [crux.query :as q]
            [crux.system :as sys]
            [crux.tx :as tx]
            [crux.tx.conform :as txc]
            [crux.tx.event :as txe])
  (:import crux.query.VarBinding
           [java.io Closeable File]
           java.nio.file.Path
           java.time.Duration
           org.apache.lucene.analysis.Analyzer
           org.apache.lucene.analysis.standard.StandardAnalyzer
           [org.apache.lucene.document Document Field$Store StringField TextField]
           [org.apache.lucene.index IndexWriter IndexWriterConfig KeepOnlyLastCommitDeletionPolicy SnapshotDeletionPolicy Term]
           org.apache.lucene.queries.function.FunctionScoreQuery
           org.apache.lucene.queryparser.classic.QueryParser
           [org.apache.lucene.search BooleanClause$Occur BooleanQuery$Builder DoubleValuesSource IndexSearcher Query ScoreDoc SearcherManager TermQuery TopDocs]
           [org.apache.lucene.store ByteBuffersDirectory FSDirectory IOContext]))

(defrecord LuceneNode [directory analyzer index-writer searcher-manager indexer
                       cp-job ^Thread fsync-thread, ^Thread refresh-thread]
  Closeable
  (close [_]
    (doto fsync-thread (.interrupt) (.join))
    (when refresh-thread (doto refresh-thread (.interrupt) (.join)))
    (cio/try-close cp-job)
    (cio/try-close index-writer)
    (cio/try-close directory)))

(defn ^String ->hash-str [eid]
  (str (cc/new-id eid)))

(defrecord DocumentId [e a v])

(defn ^String keyword->k [k]
  (subs (str k) 1))

(def ^:const index-version 1)
(def ^:const field-crux-id "_crux_id")
(def ^:const field-crux-val "_crux_val")
(def ^:const field-crux-attr "_crux_attr")
(def ^:const field-crux-eid "_crux_eid")

(defn- validate-index-version [^IndexWriter writer]
  (let [found-index-version (some-> (into {} (.getLiveCommitData writer))
                                    (get "crux.lucene/index-version")
                                    (Long/parseLong))]
    (when-not (or (zero? (.numDocs (.getDocStats writer)))
                  (= index-version found-index-version))
      (throw (IllegalStateException. (format "Lucene index structure out of date - please remove the Lucene dir and re-index. (expected: %d, actual: %s)"
                                             index-version found-index-version))))))

(defn- ^IndexWriter ->index-writer [{:keys [directory analyzer index-deletion-policy]}]
  (doto (IndexWriter. directory,
                      (cond-> (IndexWriterConfig. analyzer)
                        index-deletion-policy (doto (.setIndexDeletionPolicy index-deletion-policy))))
    (validate-index-version)))

(defn- user-data->tx-id [user-data]
  (some-> user-data
          (get "crux.tx/tx-id")
          (Long/parseLong)))

(defn latest-completed-tx-id [^IndexWriter index-writer]
  (user-data->tx-id (into {} (.getLiveCommitData index-writer))))

(defn- search* [{:keys [^SearcherManager searcher-manager]}, ^Query q]
  (let [^IndexSearcher index-searcher (.acquire searcher-manager)]
    (try
      (let [q (FunctionScoreQuery. q (DoubleValuesSource/fromQuery q))
            score-docs (letfn [(docs-page [after]
                                 (lazy-seq
                                  (let [^TopDocs
                                        top-docs (if after
                                                   (.searchAfter index-searcher after q 100)
                                                   (.search index-searcher q 100))
                                        score-docs (.-scoreDocs top-docs)]
                                    (concat score-docs
                                            (when (= 100 (count score-docs))
                                              (docs-page (last score-docs)))))))]
                         (docs-page nil))]

        (when (seq score-docs)
          (log/debug (.explain index-searcher q (.-doc ^ScoreDoc (first score-docs)))))

        (cio/->cursor (fn []
                        (.release searcher-manager index-searcher))
                      (->> score-docs
                           (map (fn [^ScoreDoc d]
                                  (vector (.doc index-searcher (.-doc d))
                                          (.-score d)))))))
      (catch Throwable t
        (.release searcher-manager index-searcher)
        (throw t)))))

(defn- parse-query
  ([lucene-store query] (parse-query lucene-store query {}))

  ([{:keys [analyzer]} query {:keys [default-field], :or {default-field field-crux-val}}]
   (cond
     (instance? Query query) query
     (string? query) (.parse (QueryParser. default-field analyzer)
                             query))))

(defn ^crux.api.ICursor search
  "Lazily search a crux-lucene index in its raw form, without temporal filtering.

  query   Either an unparsed query string or an `org.apache.lucene.search.Query`
  opts    (optional map)
          - `:lucene-store-k` (Keyword):
              Run the search against specified module key (defaults to `:crux.lucene/lucene-store` when not specified)

  The analyzer defined in the module configuration will be used for unparsed query strings. Supply a Query to use a different analyzer.

  Returns a Cursor of result tuples. Each tuple contains the matched `org.apache.lucene.document.Document` and the score (Double).

  Once you've consumed as much of the sequence as you need to, you'll need to `.close` the sequence.

  A common way to do this is using `with-open`:

  (with-open [res (l/search node query-string)]
    (let [results (iterator-seq res)]
      ...))

  Once the sequence is closed, attempting to iterate it is undefined."
  ([node query]
   (search node query {}))

  ([node query {:keys [lucene-store-k],
                :or {lucene-store-k ::lucene-store}
                :as opts}]
   (let [lucene-store (-> @(:!system node)
                          (get lucene-store-k))]
     (search* lucene-store
              (parse-query lucene-store query opts)))))

(defn pred-constraint [query-builder results-resolver {:keys [arg-bindings idx-id return-type tuple-idxs-in-join-order] :as pred-ctx}]
  (fn pred-lucene-constraint [index-snapshot db idx-id->idx join-keys]
    (let [arg-bindings (map (fn [a]
                              (if (instance? VarBinding a)
                                (q/bound-result-for-var index-snapshot a join-keys)
                                a))
                            (rest arg-bindings))
          last-arg (last arg-bindings)
          [arg-bindings opts] (if (map? last-arg)
                                [(butlast arg-bindings) last-arg]
                                [arg-bindings nil])
          lucene-store (get pred-ctx (or (:lucene-store-k opts) ::lucene-store))
          query (query-builder (:analyzer lucene-store) arg-bindings)
          tuples (with-open [search-results ^crux.api.ICursor (search* lucene-store query)]
                   (->> search-results
                        iterator-seq
                        (results-resolver index-snapshot db)
                        (into [])))]
      (q/bind-binding return-type tuple-idxs-in-join-order (get idx-id->idx idx-id) tuples))))

(defn ^Query build-query
  "Standard build query fn, taking a single field/val lucene term string."
  [^Analyzer analyzer, [k v]]
  (when-not (string? v)
    (throw (IllegalArgumentException. "Lucene text search values must be String")))
  (parse-query {:analyzer analyzer}
               v
               {:default-field (keyword->k k)}))

(defn resolve-search-results-a-v
  "Given search results each containing a single A/V pair document,
  perform a temporal resolution against A/V to resolve the eid."
  [attr index-snapshot {:keys [entity-resolver-fn]} search-results]
  (->> search-results
       (map (fn [[^Document doc score]]
              [attr (.get ^Document doc field-crux-val) score]))
       distinct ; could distinct by virtue of the `ave` call instead if eid was reversible
       (mapcat (fn [[a v score]]
                 (for [eid (doall (db/ave index-snapshot a v nil entity-resolver-fn))]
                   [(db/decode-value index-snapshot eid) v score])))))

(defmethod q/pred-args-spec 'text-search [_]
  (s/cat :pred-fn  #{'text-search} :args (s/spec (s/cat :attr keyword? :v (some-fn string? q/logic-var?) :opts (s/? (some-fn map? q/logic-var?)))) :return (s/? :crux.query/binding)))

(defmethod q/pred-constraint 'text-search [_ pred-ctx]
  (let [resolver (partial resolve-search-results-a-v (second (:arg-bindings pred-ctx)))]
    (pred-constraint #'build-query resolver pred-ctx)))

(defn- resolve-search-results-a-v-wildcard
  "Given search results each containing a single A/V pair document,
  perform a temporal resolution against A/V to resolve the eid."
  [index-snapshot {:keys [entity-resolver-fn]} search-results]
  (->> search-results
       (map (fn [[^Document doc score]]
              [(keyword (.get ^Document doc field-crux-attr))
               (.get ^Document doc field-crux-val)
               score]))
       distinct
       (mapcat (fn [[a v score]]
                 (for [eid (doall (db/ave index-snapshot a v nil entity-resolver-fn))]
                   [(db/decode-value index-snapshot eid) v a score])))))

(defn ^Query build-query-wildcard
  "Wildcard query builder"
  [^Analyzer analyzer, [v]]
  (when-not (string? v)
    (throw (IllegalArgumentException. "Lucene text search values must be String")))
  (let [qp (QueryParser. field-crux-val analyzer)
        b (doto (BooleanQuery$Builder.)
            (.add (.parse qp v) BooleanClause$Occur/MUST))]
    (.build b)))

(defmethod q/pred-args-spec 'wildcard-text-search [_]
  (s/cat :pred-fn #{'wildcard-text-search} :args (s/spec (s/cat :v (some-fn string? q/logic-var?) :opts (s/? (some-fn map? q/logic-var?)))) :return (s/? :crux.query/binding)))

(defmethod q/pred-constraint 'wildcard-text-search [_ pred-ctx]
  (pred-constraint #'build-query-wildcard #'resolve-search-results-a-v-wildcard pred-ctx))

(defprotocol LuceneIndexer
  (index! [this index-writer docs])
  (evict! [this index-writer eids]))

(defrecord EavIndexer []
  LuceneIndexer

  (index! [_ index-writer docs]
    (doseq [{e :crux.db/id, :as crux-doc} (vals docs)
            [a v] (->> (dissoc crux-doc :crux.db/id)
                       (mapcat (fn [[a v]]
                                 (for [v (cc/vectorize-value v)
                                       :when (string? v)]
                                   [a v]))))
            :let [id-str (->hash-str (->DocumentId e a v))
                  doc (doto (Document.)
                        ;; To search for triples by e-a-v for deduping
                        (.add (StringField. field-crux-id, id-str, Field$Store/NO))
                        ;; The actual term, which will be tokenized
                        (.add (TextField. (keyword->k a), v, Field$Store/YES))
                        ;; Used for wildcard searches
                        (.add (TextField. field-crux-val, v, Field$Store/YES))
                        ;; Used for eviction
                        (.add (StringField. field-crux-eid, (->hash-str e), Field$Store/NO))
                        ;; Used for wildcard searches
                        (.add (StringField. field-crux-attr, (keyword->k a), Field$Store/YES)))]]
      (.updateDocument ^IndexWriter index-writer (Term. field-crux-id id-str) doc)))

  (evict! [_ index-writer eids]
    (let [qs (for [eid eids]
               (TermQuery. (Term. field-crux-eid (->hash-str eid))))]
      (.deleteDocuments ^IndexWriter index-writer ^"[Lorg.apache.lucene.search.Query;" (into-array Query qs)))))

(defn ->indexer [_]
  (EavIndexer.))

(defn ->analyzer [_]
  (StandardAnalyzer.))

(defn- transform-tx-ops [tx-ops]
  (->> tx-ops
       (map txc/conform-tx-op)
       (mapcat txc/flatten-tx-fn-ops)
       (reduce (fn xf [acc {:keys [op docs] :as tx-op}]
                 (if (= op :crux.tx/evict)
                   (update acc :evicted-eids conj (:eid tx-op))
                   (update acc :docs into docs)))
               {:docs #{}
                :evicted-eids #{}})))

(defn- checkpoint-src [^IndexWriter index-writer]
  (let [^SnapshotDeletionPolicy snapshotter (.getIndexDeletionPolicy (.getConfig index-writer))]
    (reify cp/CheckpointSource
      (save-checkpoint [_ dir]
        (.commit index-writer)

        (let [snapshot (.snapshot snapshotter)]
          (try
            (when-let [tx-id (user-data->tx-id (.getUserData snapshot))]
              (let [src-dir (.getDirectory snapshot)
                    io-ctx (IOContext.)]
                (with-open [out-dir (FSDirectory/open (.toPath ^File dir))]
                  (doseq [file-name (.getFileNames snapshot)]
                    (.copyFrom out-dir src-dir file-name file-name io-ctx))))
              {:tx {::tx/tx-id tx-id}})
            (finally
              (.release snapshotter snapshot))))))))

(defn fsync [{:keys [^IndexWriter index-writer]}]
  (log/debug "Committing Lucene IndexWriter...")
  (.commit index-writer)
  (log/debug "Committed Lucene IndexWriter."))

(defn- fsync-loop [deps ^Duration fsync-frequency]
  (log/debug "Starting Lucene fsync-loop...")
  (try
    (while true
      (try
        (Thread/sleep (.toMillis fsync-frequency))
        (fsync deps)

        (catch InterruptedException e
          (throw e))

        (catch Throwable t
          (log/warn t "error during Lucene IndexWriter commit"))))

    (catch InterruptedException _
      (log/debug "Stopped Lucene fsync-loop."))))

(defn refresh [{:keys [^SearcherManager searcher-manager]}]
  (.maybeRefresh searcher-manager))

(defn- refresh-loop [deps ^Duration refresh-frequency]
  (log/debug "Starting Lucene refresh-loop...")
  (try
    (while true
      (try
        (Thread/sleep (.toMillis refresh-frequency))
        (refresh deps)

        (catch InterruptedException e
          (throw e))

        (catch Throwable t
          (log/warn t "error during Lucene IndexWriter commit"))))

    (catch InterruptedException _
      (log/debug "Stopped Lucene fsync-loop."))))

(defn ->lucene-store
  {::sys/args {:db-dir {:doc "Lucene DB Dir"
                        :spec ::sys/path}
               :fsync-frequency {:required? true
                                 :spec ::sys/duration
                                 :default "PT5M"
                                 :doc "Approx. time between IO-intensive Lucene `.commit` operations."}
               :refresh-frequency {:required? true
                                   :spec ::sys/duration
                                   :default "PT0S"
                                   :doc "How often to perform a refresh operation. Negative will disable refresh, zero will refresh after every transaction, positive will refresh on the given interval - updates will not be visible in Lucene searches until the index is refreshed."}}
   ::sys/deps {:query-engine :crux/query-engine
               :indexer `->indexer
               :analyzer `->analyzer
               :secondary-indices :crux/secondary-indices
               :checkpointer (fn [_])}
   ::sys/before #{[:crux/tx-ingester]}}
  [{:keys [^Path db-dir analyzer indexer query-engine secondary-indices checkpointer
           fsync-frequency ^Duration refresh-frequency]
    :as opts}]
  (let [directory (if db-dir
                    (FSDirectory/open db-dir)
                    (ByteBuffersDirectory.))
        index-writer (->index-writer {:directory directory, :analyzer analyzer,
                                      :index-deletion-policy (SnapshotDeletionPolicy. (KeepOnlyLastCommitDeletionPolicy.))})
        searcher-manager (SearcherManager. index-writer true false nil)
        cp-job (when checkpointer
                 (cp/start checkpointer (checkpoint-src index-writer) {::cp/cp-format "lucene-8"}))
        lucene-store (LuceneNode. directory analyzer
                                  index-writer searcher-manager
                                  indexer
                                  cp-job
                                  (doto (.newThread (cio/thread-factory "crux-lucene-fsync")
                                                    #(fsync-loop {:index-writer index-writer} fsync-frequency))
                                    (.start))
                                  (when-not (or (.isNegative refresh-frequency) (.isZero refresh-frequency))
                                    (doto (.newThread (cio/thread-factory "crux-lucene-refresh")
                                                      #(refresh-loop {:searcher-manager searcher-manager} refresh-frequency))
                                      (.start))))]

    ;; Ensure lucene index exists for immediate queries:
    (.commit index-writer)

    (when-let [{:keys [::sys/module-key]} (meta opts)]
      (when (= 1 (count module-key))
        (q/assoc-pred-ctx! query-engine (first module-key) lucene-store)))

    (tx/register-index! secondary-indices
                        (latest-completed-tx-id index-writer)
                        {:with-tx-ops? true}
                        (fn [{:keys [::tx/tx-id :crux.api/tx-ops committing?]}]
                          (when committing?
                            (let [{:keys [docs evicted-eids]} (transform-tx-ops tx-ops)]
                              (when-let [evicting-eids (not-empty evicted-eids)]
                                (evict! indexer index-writer evicting-eids))
                              (index! indexer index-writer docs)))

                          (.setLiveCommitData index-writer {"crux.tx/tx-id" (str tx-id)
                                                            "crux.lucene/index-version" (str index-version)})
                          (when (.isZero refresh-frequency)
                            (.maybeRefreshBlocking searcher-manager))))

    lucene-store))
