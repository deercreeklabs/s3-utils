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
(def SuccessCallback (s/=> Nil s/Any))
(def FailureCallback (s/=> Nil #?(:clj Exception :cljs js/Error)))


(s/defn s3-get :- Nil
  [s3-client :- (s/protocol u/IS3Client)
   bucket :- s/Str
   k :- s/Str
   success-cb :- SuccessCallback
   failure-cb :- FailureCallback]
  (u/s3-get s3-client bucket k success-cb FailureCallback))

(s/defn <s3-get :- au/Channel
  [s3-client :- (s/protocol u/IS3Client)
   bucket :- s/Str
   k :- s/Str]
  (let [ret-ch (ca/chan)
        [success-cb failure-cb] (u/make-callbacks ret-ch)]
    (u/s3-get s3-client bucket k success-cb failure-cb)
    ret-ch))

(s/defn s3-put :- Nil
  [s3-client :- (s/protocol u/IS3Client)
   bucket :- s/Str
   k :- s/Str
   data :- ba/ByteArray
   success-cb :- SuccessCallback
   failure-cb :- FailureCallback]
  (u/s3-put s3-client bucket k data success-cb failure-cb))

(s/defn <s3-put :- au/Channel
  [s3-client :- (s/protocol u/IS3Client)
   bucket :- s/Str
   k :- s/Str
   data :- ba/ByteArray]
  (let [ret-ch (ca/chan)
        [success-cb failure-cb] (u/make-callbacks ret-ch)]
    (u/s3-put s3-client bucket k data success-cb failure-cb)
    ret-ch))

(s/defn stop :- Nil
  [s3-client :- (s/protocol u/IS3Client)]
  (u/stop s3-client))

(s/defn make-s3-client :- (s/protocol u/IS3Client)
  []
  (u/make-s3-client))
