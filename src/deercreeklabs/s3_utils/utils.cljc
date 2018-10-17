(ns deercreeklabs.s3-utils.utils
  (:require
   [clojure.core.async :as ca]
   #?(:clj [clojure.java.io :as io])
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  #?(:cljs
     (:require-macros deercreeklabs.s3-utils.utils)
     :clj
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

(s/defn node? :- s/Bool
  []
  #?(:clj false
     :cljs (boolean (= "nodejs" cljs.core/*target*))))

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
  (get-bytes [this bucket k success-cb failure-cb])
  (<get-bytes [this bucket k])
  (put-bytes [this bucket k bs success-cb failure-cb])
  (<put-bytes [this bucket k bs])
  (stop [this]))


#?(:clj
   (defn get-bytes-clj
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
   (defn put-bytes-clj
     [transfer-mgr client-obj bucket k bs success-cb failure-cb]
     (let [is (io/input-stream bs)
           metadata (doto (ObjectMetadata.)
                      (.setContentLength (count bs))
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
   (defn get-bytes-node
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
   (defn put-bytes-node
     [s3-obj client-obj bucket k bs success-cb failure-cb]
     (let [buffer (js/Buffer.from (.-buffer bs))
           params #js {"Body" buffer
                       "Bucket" bucket
                       "Key" k}
           cb (fn [err result]
                (if err
                  (failure-cb err)
                  (success-cb true)))
           x (.-putObject s3-obj)]
       (.putObject s3-obj params cb))))

(defn throw-platform-exception []
  (throw (ex-info "Only JVM and nodejs platforms are currently supported"
                  {})))

(defrecord S3Client [s3-obj]
  IS3Client
  (get-bytes [this bucket k success-cb failure-cb]
    (try
      #?(:clj
         (get-bytes-clj s3-obj this bucket k success-cb failure-cb)
         :cljs
         (if (node?)
           (get-bytes-node s3-obj this bucket k success-cb failure-cb)
           (throw-platform-exception)))
      (catch #?(:clj Exception :cljs js/Error) e
        (failure-cb (ex-info (str "Exception in get-bytes: \n"
                                  (lu/ex-msg-and-stacktrace e))
                             {:exception e})))))

  (<get-bytes [this bucket k]
    (let [ret-ch (ca/chan)
          [success-cb failure-cb] (make-callbacks ret-ch)]
      (get-bytes this bucket k success-cb failure-cb)
      ret-ch))

  (put-bytes [this bucket k bs success-cb failure-cb]
    (try
      #?(:clj
         (put-bytes-clj s3-obj this bucket k bs success-cb failure-cb)
         :cljs
         (if (node?)
           (put-bytes-node s3-obj this bucket k bs success-cb failure-cb)
           (throw-platform-exception)))
      (catch #?(:clj Exception :cljs js/Error) e
        (failure-cb (ex-info (str "Exception in put-bytes: "
                                  (lu/ex-msg-and-stacktrace e))
                             {:exception e})))))

  (<put-bytes [this bucket k bs]
    (let [ret-ch (ca/chan)
          [success-cb failure-cb] (make-callbacks ret-ch)]
      (put-bytes this bucket k bs success-cb failure-cb)
      ret-ch))

  (stop  [this]
    #?(:clj
       (.shutdownNow ^TransferManager s3-obj))))


(s/defn s3-client :- (s/protocol IS3Client)
  []
  (let [s3-obj #?(:clj (TransferManagerBuilder/defaultTransferManager)
                  :cljs (let [s3 (js/require "aws-sdk/clients/s3")]
                          (s3.)))]
    (->S3Client s3-obj)))

(defn configure-logging
  ([] (configure-logging :debug))
  ([level]
   (timbre/merge-config!
    {:level level
     :output-fn lu/short-log-output-fn
     :appenders
     {:println {:ns-blacklist []}}})))
