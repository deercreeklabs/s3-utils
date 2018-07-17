(ns deercreeklabs.s3-utils
  (:require
   [clojure.core.async :as ca]
   [clojure.string :as string]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [deercreeklabs.s3-utils.utils :as u]
   [schema.core :as s]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]]))

(def Nil (s/eq nil))
(def S3Client (s/protocol u/IS3Client))
(def SuccessCallback (s/=> Nil s/Any))
(def FailureCallback (s/=> Nil #?(:clj Exception :cljs js/Error)))


(s/defn get-bytes :- Nil
  [s3-client :- S3Client
   bucket :- s/Str
   k :- s/Str
   success-cb :- SuccessCallback
   failure-cb :- FailureCallback]
  (u/get-bytes s3-client bucket k success-cb FailureCallback))

(s/defn <get-bytes :- au/Channel
  [s3-client :- S3Client
   bucket :- s/Str
   k :- s/Str]
  (u/<get-bytes s3-client bucket k))

(s/defn put-bytes :- Nil
  [s3-client :- S3Client
   bucket :- s/Str
   k :- s/Str
   bs :- ba/ByteArray
   success-cb :- SuccessCallback
   failure-cb :- FailureCallback]
  (u/put-bytes s3-client bucket k bs success-cb failure-cb))

(s/defn <put-bytes :- au/Channel
  [s3-client :- S3Client
   bucket :- s/Str
   k :- s/Str
   bs :- ba/ByteArray]
  (u/<put-bytes s3-client bucket k bs))

(s/defn stop :- Nil
  [s3-client :- S3Client]
  (u/stop s3-client))

(s/defn make-s3-client :- S3Client
  []
  (u/make-s3-client))
