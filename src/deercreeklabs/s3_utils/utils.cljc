(ns deercreeklabs.s3-utils.utils
  (:require
   [clojure.core.async :as ca]
   #?(:clj [clojure.java.io :as io])
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:clj
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
               InputStream))))

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

#?(:clj
   (defn s3-get-clj
     [transfer-mgr client-obj bucket k success-cb failure-cb]
     (let [^File dl-file (doto (File/createTempFile "s3dl" nil)
                           (.deleteOnExit))
           ^Download download (.download ^TransferManager transfer-mgr
                                         ^String bucket
                                         ^String k
                                         ^File dl-file)
           listener (reify ProgressListener
                      (^void progressChanged [client-obj ^ProgressEvent event]
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
       nil)))

#?(:clj
   (defn s3-put-clj
     [transfer-mgr client-obj bucket k data success-cb failure-cb]
     (let [is (io/input-stream data)
           metadata (doto (ObjectMetadata.)
                      (.setContentLength (count data))
                      (.setContentType "application/octet-stream"))
           ^Upload upload (.upload ^TransferManager transfer-mgr
                                   bucket k is metadata)
           listener (reify ProgressListener
                      (^void progressChanged [client-obj ^ProgressEvent event]
                       (condp = (.getEventType event)
                         ProgressEventType/TRANSFER_COMPLETED_EVENT
                         (success-cb true)

                         ProgressEventType/TRANSFER_FAILED_EVENT
                         (failure-cb (ex-info "S3 transfer failed."
                                              (sym-map event)))

                         nil)))]
       (.addProgressListener upload ^ProgressListener listener)
       nil)))

#?(:cljs
   (defn s3-get-cljs
     [s3-obj client-obj bucket k success-cb failure-cb]
     (let [params #js {"Bucket" bucket
                       "Key" k}
           cb (fn [err result]
                (if err
                  (failure-cb err)
                  (let [body (.-Body result)]
                    (success-cb (js/Int8Array. body)))))]
       (.getObject s3-obj params cb))))

#?(:cljs
   (defn s3-put-cljs
     [s3-obj client-obj bucket k data success-cb failure-cb]
     (let [buffer (js/Buffer.from (.-buffer data))
           params #js {"Body" buffer
                       "Bucket" bucket
                       "Key" k}
           cb (fn [err result]
                (if err
                  (failure-cb err)
                  (success-cb true)))
           x (.-putObject s3-obj)]
       (.putObject s3-obj params cb))))


(defrecord S3Client [s3-obj]
  IS3Client
  (s3-get [this bucket k success-cb failure-cb]
    (try
      #?(:clj
         (s3-get-clj s3-obj this bucket k success-cb failure-cb)
         :cljs
         (s3-get-cljs s3-obj this bucket k success-cb failure-cb))
      (catch #?(:clj Exception :cljs js/Error) e
        (failure-cb (ex-info (str "Exception in s3-get: \n"
                                  (lu/get-exception-msg-and-stacktrace e))
                             {:exception e})))))

  (s3-put [this bucket k data success-cb failure-cb]
    (try
      #?(:clj
         (s3-put-clj s3-obj this bucket k data success-cb failure-cb)
         :cljs
         (s3-put-cljs s3-obj this bucket k data success-cb failure-cb))
      (catch #?(:clj Exception :cljs js/Error) e
        (failure-cb (ex-info (str "Exception in s3-put: "
                                  (lu/get-exception-msg-and-stacktrace e))
                             {:exception e})))))

  (stop [this]
    #?(:clj
       (.shutdownNow ^TransferManager s3-obj))))


(defn make-s3-client []
  (->S3Client #?(:clj (TransferManagerBuilder/defaultTransferManager)
                 :cljs (let [s3 (js/require "aws-sdk/clients/s3")]
                         (s3.)))))

(defn configure-logging
  ([] (configure-logging :debug))
  ([level]
   (timbre/merge-config!
    {:level level
     :output-fn lu/short-log-output-fn
     :appenders
     {:println {:ns-blacklist []}}})))
