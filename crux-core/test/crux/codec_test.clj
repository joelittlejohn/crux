(ns crux.codec-test
  (:require [clojure.test :as t]
            [crux.codec :as c]
            [crux.memory :as mem]
            [crux.fixtures :as fix]
            [clojure.test.check.clojure-test :as tcct]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [crux.api :as crux]
            [taoensso.nippy :as nippy])
  (:import crux.codec.Id
           [java.util Arrays Date]
           java.net.URL))

(t/use-fixtures :each fix/with-silent-test-check)

(t/deftest test-ordering-of-values
  (t/testing "longs"
    (let [values (shuffle [nil -33 -1 0 1 33])
          value+buffer (for [v values]
                         [v (c/->value-buffer v)])]
      (t/is (= (sort-by first value+buffer)
               (sort-by second mem/buffer-comparator value+buffer)))))

  (t/testing "doubles"
    (let [values (shuffle [nil -33.0 -1.0 0.0 1.0 33.0])
          value+buffer (for [v values]
                         [v (c/->value-buffer v)])]
      (t/is (= (sort-by first value+buffer)
               (sort-by second mem/buffer-comparator value+buffer)))))

  (t/testing "dates"
    (let [values (shuffle [nil #inst "1900" #inst "1970" #inst "2018"])
          value+buffer (for [v values]
                         [v (c/->value-buffer v)])]
      (t/is (= (sort-by first value+buffer)
               (sort-by second mem/buffer-comparator value+buffer)))))

   (t/testing "strings"
    (let [values (shuffle [nil "a" "ad" "c" "delta" "eq" "foo" "" "0" "året" "漢字" "यूनिकोड"])
          value+buffer (for [v values]
                         [v (c/->value-buffer v)])]
      (t/is (= (sort-by first value+buffer)
               (sort-by second mem/buffer-comparator value+buffer))))))

(t/deftest test-string-prefix
  (t/testing "string encoding size overhead"
    (t/is (= (+ c/value-type-id-size
                (alength (.getBytes "Hello" "UTF-8"))
                @#'c/string-terminate-mark-size)
             (mem/capacity (c/->value-buffer "Hello")))))

  (t/testing "a short encoded string is not a prefix of a longer string"
    (let [hello (c/->value-buffer "Hello")
          hello-world (c/->value-buffer "Hello World")]
      (t/is (false? (mem/buffers=? hello hello-world (mem/capacity hello))))))

  (t/testing "a short raw string is a prefix of a longer string"
    (let [hello (mem/as-buffer (.getBytes "Hello" "UTF-8"))
          hello-world (mem/as-buffer (.getBytes "Hello World" "UTF-8"))]
      (t/is (true? (mem/buffers=? hello hello-world (mem/capacity hello))))))

  (t/testing "cannot decode non-terminated string"
    (let [hello (c/->value-buffer "Hello")
          hello-prefix (mem/slice-buffer hello 0 (- (mem/capacity hello) @#'c/string-terminate-mark-size))]
      (t/is (thrown-with-msg? AssertionError #"String not terminated." (c/decode-value-buffer hello-prefix))))))

(t/deftest test-id-reader
  (t/testing "can read and convert to real id"
    (t/is (not= (c/new-id "http://google.com") #crux/id "http://google.com"))
    (t/is (= "234988566c9a0a9cf952cec82b143bf9c207ac16"
             (str #crux/id "http://google.com")))
    (t/is (instance? Id (c/new-id #crux/id "http://google.com"))))

  (t/testing "can create different types of ids"
    (t/is (= (c/new-id :foo) #crux/id ":foo"))
    (t/is (= (c/new-id #uuid "37c20bcd-eb5e-4ef7-b5dc-69fed7d87f28")
             #crux/id "37c20bcd-eb5e-4ef7-b5dc-69fed7d87f28"))
    (t/is (not= #crux/id "234988566c9a0a9cf952cec82b143bf9c207ac16"
                (c/new-id "234988566c9a0a9cf952cec82b143bf9c207ac16")))
    (t/is (not= (c/new-id "234988566c9a0a9cf952cec82b143bf9c207ac16")
                #crux/id "234988566c9a0a9cf952cec82b143bf9c207ac16")))

  (t/testing "can embed id in other forms"
    (t/is (not= {:find ['e]
                 :where [['e (c/new-id "http://xmlns.com/foaf/0.1/firstName") "Pablo"]]}
                '{:find [e]
                  :where [[e #crux/id "http://xmlns.com/foaf/0.1/firstName" "Pablo"]]})))

  (t/testing "URL and keyword are same id"
    (t/is (= (c/new-id :http://xmlns.com/foaf/0.1/firstName)
             #crux/id "http://xmlns.com/foaf/0.1/firstName"))
    (t/is (= (c/new-id (URL. "http://xmlns.com/foaf/0.1/firstName"))
             #crux/id ":http://xmlns.com/foaf/0.1/firstName"))
    (t/is (not= (c/new-id "http://xmlns.com/foaf/0.1/firstName")
                #crux/id ":http://xmlns.com/foaf/0.1/firstName"))))

(t/deftest test-base64-reader
  (t/is (Arrays/equals (byte-array [1 2 3])
                       ^bytes (c/read-edn-string-with-readers "#crux/base64 \"AQID\""))))

(tcct/defspec test-generative-primitive-value-decoder 1000
  (prop/for-all [v (gen/one-of [(gen/return nil)
                                gen/large-integer
                                gen/double
                                (gen/fmap #(Date. (long %)) gen/large-integer)
                                gen/string
                                gen/char
                                gen/boolean
                                gen/keyword
                                gen/uuid
                                gen/bytes])]
                (let [buffer (c/->value-buffer v)]
                  (if (c/can-decode-value-buffer? buffer)
                    (cond
                      (and (double? v) (Double/isNaN v))
                      (Double/isNaN (c/decode-value-buffer buffer))

                      (bytes? v)
                      (Arrays/equals ^bytes v ^bytes (c/decode-value-buffer buffer))

                      :else
                      (= v (c/decode-value-buffer buffer)))
                    (cond
                      (and (string? v)
                           (< @#'c/max-value-index-length (alength (.getBytes ^String v "UTF-8"))))
                      (= @#'c/clob-value-type-id
                         (.getByte (c/value-buffer-type-id buffer) 0))

                      (and (bytes? v)
                           (< @#'c/max-value-index-length (alength ^bytes (nippy/fast-freeze v))))
                      (= @#'c/object-value-type-id
                         (.getByte (c/value-buffer-type-id buffer) 0))

                      :else
                      false)))))

(t/deftest test-unordered-coll-hashing-1001
  (let [foo-a {{:foo 1} :foo1
               {:foo 2} :foo2}
        foo-b {{:foo 2} :foo2
               {:foo 1} :foo1}]
    (t/is (not= (seq foo-a) (seq foo-b))) ; ordering is different
    (t/is (thrown? ClassCastException (sort foo-a))) ; can't just sort it
    (t/is (= #crux/id "e64adaca39f92830a8e2d167aa3daabd721d40d4"
             (c/new-id {:crux.db/id :foo
                        :foo foo-a})
             (c/new-id {:crux.db/id :foo
                        :foo foo-b}))))

  (let [foo #{#{:foo} #{:bar}}]
    (t/is (thrown? ClassCastException (sort foo)))
    (t/is (= #crux/id "d5fb933a181a2aa89859369c51abcef7f363b31b"
             (c/new-id {:crux.db/id :foo
                        :foo foo}))))

  (let [foo #{42 "hello"}]
    (t/is (thrown? ClassCastException (sort foo)))
    (t/is (= #crux/id "33581f4655dc841081f310f50d8bc5e0fa602377"
             (c/new-id {:crux.db/id :foo
                        :foo foo}))))

  (t/testing "original coll hashing unaffected"
    (t/is (= #crux/id "e4b35746db4bd2cf3b024133eafd95f66c3638ed"
             (c/new-id {:crux.db/id :foo
                        :foo {:a 1, :b 2}})
             (c/new-id {:crux.db/id :foo
                        :foo {:b 2, :a 1}})))))

(t/deftest test-java-type-serialisation-1044
  (with-open [node (crux/start-node {})]
    (let [doc {:crux.db/id :foo
               :date (java.util.Date.)
               :uri (java.net.URI. "https://google.com")
               :url (java.net.URL. "https://google.com")
               :uuid (java.util.UUID/randomUUID)}]

      (t/is (thrown-with-msg? IllegalArgumentException
                              #"Unfreezable type"
                              (fix/submit+await-tx node [[:crux.tx/put doc]])))

      (with-redefs [nippy/*serializable-whitelist* (conj nippy/*serializable-whitelist* "java.net.URL")]
        (fix/submit+await-tx node [[:crux.tx/put doc]])
        (t/is (= doc (crux/entity (crux/db node) :foo)))))))
