(ns deercreeklabs.s3-test
  (:require
   [clojure.core.async :as ca]
   [clojure.test :refer [deftest is]]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [deercreeklabs.s3-utils :as s3]
   [deercreeklabs.s3-utils.utils :as u]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

(u/configure-logging)

;; Use this instead of fixtures, which are hard to make work w/ async testing.
(s/set-fn-validation! true)

(deftest test-round-trip
  (au/test-async
   10000
   (au/go
     (let [client (s3/make-s3-client)
           bucket "deercreeklabs-testing"
           k "test.txt"
           data (ba/utf8->byte-array "Hello world")
           ret (au/<? (s3/<s3-put client bucket k data))
           _ (is (= true ret))
           ret (au/<? (s3/<s3-get client bucket k))]
       (s3/stop client)
       (is (ba/equivalent-byte-arrays? data ret))))))
