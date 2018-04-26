(ns deercreeklabs.s3-utils.utils
  (:require
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (com.amazonaws.event ProgressEvent
                        ProgressEventType
                        ProgressListener)
   (com.amazonaws.services.s3.model ObjectMetadata)
   (com.amazonaws.services.s3.transfer Download
                                       TransferManager
                                       TransferManagerBuilder
                                       Upload)
   (java.io File
            FileInputStream
            InputStream)))

(defmacro sym-map
  "Builds a map from symbols.
   Symbol names are turned into keywords and become the map's keys.
   Symbol values become the map's values.
  (let [a 1
        b 2]
    (sym-map a b))  =>  {:a 1 :b 2}"
  [& syms]
  (zipmap (map keyword syms) syms))

(defn make-success-callback [ret-ch]
  (fn [result]
    (if (nil? result)
      (ca/close! ret-ch)
      (ca/put! ret-ch result))))

(defn make-failure-callback [ret-ch]
  (fn [e]
    (ca/put! ret-ch e)))

(defn make-callbacks [ret-ch]
  [(make-success-callback ret-ch)
   (make-failure-callback ret-ch)])

(defprotocol IS3Client
  (s3-get [this bucket k success-cb failure-cb])
  (s3-put [this bucket k data success-cb failure-cb])
  (stop [this]))

(defrecord S3Client [transfer-mgr]
  IS3Client
  (s3-get [this bucket k success-cb failure-cb]
    (let [^File dl-file (doto (File/createTempFile "s3dl" nil)
                          (.deleteOnExit))
          ^Download download (.download ^TransferManager transfer-mgr
                                        ^String bucket
                                        ^String k
                                        ^File dl-file)
          listener (reify ProgressListener
                     (^void progressChanged [this ^ProgressEvent event]
                      (condp = (.getEventType event)
                        ProgressEventType/TRANSFER_COMPLETED_EVENT
                        (let [ba (byte-array (.length dl-file))
                              ^InputStream is (java.io.FileInputStream.
                                               dl-file)]
                          (.read is ba)
                          (.close is)
                          (.delete dl-file)
                          (success-cb ba))

                        ProgressEventType/TRANSFER_FAILED_EVENT
                        (do
                          (.delete dl-file)
                          (failure-cb (ex-info "S3 transfer failed."
                                               (sym-map event))))
                        nil)))]
      (.addProgressListener download ^ProgressListener listener)
      nil))

  (s3-put [this bucket k data success-cb failure-cb]
    (let [is (io/input-stream data)
          metadata (doto (ObjectMetadata.)
                     (.setContentLength (count data))
                     (.setContentType "application/octet-stream"))
          ^Upload upload (.upload ^TransferManager transfer-mgr
                                  bucket k is metadata)
          listener (reify ProgressListener
                     (^void progressChanged [this ^ProgressEvent event]
                      (condp = (.getEventType event)
                        ProgressEventType/TRANSFER_COMPLETED_EVENT
                        (success-cb true)

                        ProgressEventType/TRANSFER_FAILED_EVENT
                        (failure-cb (ex-info "S3 transfer failed."
                                             (sym-map event)))

                        nil)))]
      (.addProgressListener upload ^ProgressListener listener)
      nil))

  (stop [this]
    (.shutdownNow ^TransferManager transfer-mgr)))

(defn make-s3-client []
  (->S3Client (TransferManagerBuilder/defaultTransferManager)))

(defn configure-logging
  ([] (configure-logging :debug))
  ([level]
   (timbre/merge-config!
    {:level level
     :output-fn lu/short-log-output-fn
     :appenders
     {:println {:ns-blacklist []}}})))
