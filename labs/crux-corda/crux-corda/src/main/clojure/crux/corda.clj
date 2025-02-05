(ns crux.corda
  (:require [crux.api :as crux]
            [crux.tx :as tx]
            [crux.tx.event :as txe]
            [crux.tx.subscribe :as tx-sub]
            [crux.db :as db]
            [crux.codec :as c]
            [crux.system :as sys]
            [juxt.clojars-mirrors.nextjdbc.v1v2v674.next.jdbc :as jdbc]
            [juxt.clojars-mirrors.nextjdbc.v1v2v674.next.jdbc.result-set :as jdbcr]
            [clojure.set :as set]
            [crux.io :as cio])
  (:import (crux.corda.state CruxState)
           (crux.api ICursor)
           kotlin.jvm.functions.Function1
           (net.corda.core.crypto SecureHash)
           (net.corda.core.node AppServiceHub)
           (net.corda.core.transactions SignedTransaction)
           (net.corda.core.contracts TransactionState StateAndRef)
           net.corda.core.node.services.vault.SessionScope
           org.hibernate.jdbc.ReturningWork))

(set! *warn-on-reflection* true)

(defprotocol SQLDialect
  (db-type [_])
  (setup-tx-schema! [_ jdbc-session]))

(comment
  (import '(net.corda.testing.node MockNetwork MockNetworkParameters TestCordapp)
          '(com.example.workflow ExampleFlow$Initiator)
          '(net.corda.core.crypto SecureHash))

  (do
    (defonce ^MockNetwork network
      (MockNetwork. (MockNetworkParameters. [(TestCordapp/findCordapp "crux.corda.service")
                                             (TestCordapp/findCordapp "com.example.contract")
                                             (TestCordapp/findCordapp "com.example.workflow")])))
    (defonce node-a
      (.createPartyNode network nil))

    (defonce node-b
      (.createPartyNode network nil))

    (def b-party
      (-> (.getLegalIdentitiesAndCerts (.getInfo node-b))
          first
          .getParty)))

  (let [fut (.startFlow node-a (ExampleFlow$Initiator. 2 b-party))]
    (.runNetwork network)
    @fut)

  (let [corda-tx-id (-> (jdbc/execute-one! (.getDataSource (.getDatabase (.getServices node-a)))
                                           ["SELECT * FROM crux_txs ORDER BY crux_tx_id DESC LIMIT 1"]
                                           {:builder-fn jdbcr/as-unqualified-lower-maps})
                        :corda_tx_id)
        service-hub (.getServices node-a)
        _corda-tx (.getTransaction (.getValidatedTransactions service-hub)
                                   (SecureHash/parse corda-tx-id))]

    ))

(defmulti tx-row->tx
  (fn [tx-row dialect]
    (db-type dialect))
  :default ::default)

(defn ->crux-tx [^SecureHash corda-tx-id {{{:keys [dialect ^AppServiceHub service-hub]} :tx-log} :node}]
  (some-> (jdbc/execute-one! (.jdbcSession service-hub)
                             ["SELECT * FROM crux_txs WHERE corda_tx_id = ?"
                              (str corda-tx-id)]
                             {:builder-fn jdbcr/as-unqualified-lower-maps})
          (tx-row->tx dialect)
          (select-keys [::tx/tx-id ::tx/tx-time])))

(defn- ->corda-tx [corda-tx-id ^AppServiceHub service-hub]
  (.getTransaction (.getValidatedTransactions service-hub)
                   (SecureHash/parse corda-tx-id)))

(defn- ->crux-docs [^TransactionState tx-state {:keys [document-mapper]}]
  (for [^CruxState crux-state (document-mapper (.getData tx-state))
        :when (instance? CruxState crux-state)]
    (merge {:crux.db/id (.getCruxId crux-state)}
           (->> (.getCruxDoc crux-state)
                (into {} (map (juxt (comp keyword key) val)))))))

(defn- transform-corda-tx [^SignedTransaction corda-tx {:keys [service-hub] :as opts}]
  (let [ledger-tx (.toLedgerTransaction corda-tx service-hub)
        consumed-ids (->> (.getInputs ledger-tx)
                          (map #(.getState ^StateAndRef %))
                          (mapcat #(->crux-docs % opts))
                          (into #{} (map :crux.db/id)))
        new-docs (->> (.getOutputs ledger-tx)
                      (mapcat #(->crux-docs % opts))
                      (into {} (map (juxt c/new-id identity))))]
    {::txe/tx-events (concat (for [deleted-id (set/difference consumed-ids (set (keys new-docs)))]
                               [::tx/delete deleted-id])

                             (for [[new-doc-id new-doc] new-docs]
                               [::tx/put (:crux.db/id new-doc) new-doc-id]))
     :docs new-docs}))

(defn- with-database-connection [^AppServiceHub service-hub, f]
  ;; eugh.
  (-> (.getDatabase service-hub)
      (.transaction (reify Function1
                      (invoke [_ session-scope]
                        (.doReturningWork (.getSession ^SessionScope session-scope)
                                          (reify ReturningWork
                                            (execute [_ conn]
                                              (f conn)))))))))

(defn- latest-submitted-tx [{:keys [^AppServiceHub service-hub dialect]}]
  (with-database-connection service-hub
    (fn [conn]
      (some-> (jdbc/execute-one! conn
                                 ["SELECT * FROM crux_txs ORDER BY crux_tx_id DESC LIMIT 1"]
                                 {:builder-fn jdbcr/as-unqualified-lower-maps})
              (tx-row->tx dialect)
              (select-keys [::tx/tx-id ::tx/tx-time])))))

(defn- ^ICursor open-tx-log [{:keys [dialect ^AppServiceHub service-hub] :as tx-log} after-tx-id]
  (let [last-tx-id (::tx/tx-id (latest-submitted-tx tx-log))]
    (letfn [(tx-log-query [after-tx-id]
              (if after-tx-id
                ["SELECT * FROM crux_txs WHERE crux_tx_id <= ? AND crux_tx_id > ? ORDER BY crux_tx_id LIMIT 100"
                 last-tx-id after-tx-id]
                ["SELECT * FROM crux_txs WHERE crux_tx_id <= ? ORDER BY crux_tx_id LIMIT 100"
                 last-tx-id]))

            (tx-log* [after-tx-id]
              (lazy-seq
               (when-let [txs (seq (with-database-connection service-hub
                                     (fn [conn]
                                       (->> (for [row (jdbc/execute! conn (tx-log-query after-tx-id)
                                                                     {:builder-fn jdbcr/as-unqualified-lower-maps})]
                                              (let [{:keys [corda-tx-id] :as tx} (tx-row->tx row dialect)
                                                    corda-tx (->corda-tx corda-tx-id service-hub)]
                                                (merge (select-keys tx [::tx/tx-id ::tx/tx-time])
                                                       (transform-corda-tx corda-tx tx-log))))
                                            vec))))]
                 (concat txs (tx-log* (::tx/tx-id (last txs)))))))]
      (cio/->cursor #() (tx-log* after-tx-id)))))

(defrecord CordaTxLog [dialect, ^AppServiceHub service-hub, document-store
                       document-mapper subscriber-handler]
  db/TxLog
  (submit-tx [_this _tx-events]
    (throw (UnsupportedOperationException.
            "CordaTxLog does not support submit-tx - submit transactions directly to Corda")))

  (open-tx-log ^crux.api.ICursor [this after-tx-id]
    (let [txs (open-tx-log this after-tx-id)]
      (cio/->cursor #(cio/try-close txs) (iterator-seq txs))))

  (latest-submitted-tx [this]
    (latest-submitted-tx this))

  (subscribe [this after-tx-id f]
    (tx-sub/handle-notifying-subscriber subscriber-handler this after-tx-id
                                        (fn [fut {:keys [docs] :as tx}]
                                          (db/submit-docs document-store docs)
                                          (f fut tx)))))

(defn notify-tx [^SecureHash corda-tx-id {{{:keys [subscriber-handler]} :tx-log} :node, :as node}]
  (tx-sub/notify-tx! subscriber-handler (->crux-tx corda-tx-id node)))

(defn ->document-mapper [_]
  (fn [doc]
    (when (instance? CruxState doc)
      [doc])))

(defn ->tx-log {::sys/deps {:dialect 'crux.corda.h2/->dialect
                            :document-store :crux/document-store
                            :service-hub ::service-hub
                            :document-mapper `->document-mapper}}
  [{:keys [dialect ^AppServiceHub service-hub] :as opts}]
  (setup-tx-schema! dialect (.jdbcSession service-hub))
  (map->CordaTxLog (assoc opts
                          :subscriber-handler (tx-sub/->notifying-subscriber-handler (latest-submitted-tx opts)))))
