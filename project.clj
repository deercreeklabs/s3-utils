(def compiler-defaults
  {:npm-deps {:aws-sdk "2.269.1"
              :pako "1.0.6"
              :source-map-support "0.5.6"}
   :install-deps true
   :parallel-build true
   :static-fns true
   ;; :pseudo-names true
   ;; :pretty-print true
   ;; :infer-externs true
   :externs ["s3_utils_externs.js"]})

(defn make-build-conf [id target-kw build-type-kw opt-level main]
  (let [build-type-str (name build-type-kw)
        target-str (if target-kw
                     (name target-kw)
                     "")
        node? (= :node target-kw)
        source-paths (case build-type-kw
                       :build ["src"]
                       :test ["src" "test"]
                       :perf ["src" "test"])
        build-name (str target-str "_" build-type-str "_" (name opt-level))
        output-name (case build-type-kw
                      :build "main.js"
                      :test "test_main.js"
                      :perf "perf_main.js")
        output-dir (str "target/" build-type-str "/" build-name)
        output-to (str output-dir "/" output-name)
        source-map (if (= :none opt-level)
                     true
                     (str output-dir "/map.js.map"))
        compiler (cond-> compiler-defaults
                   true (assoc :optimizations opt-level
                               :output-to output-to
                               :output-dir output-dir
                               :source-map source-map)
                   main (assoc :main main)
                   node? (assoc :target :nodejs))
        node-test? (and node? (or (= :test build-type-kw)
                                  (= :perf build-type-kw)))]
    (cond-> {:id id
             :source-paths source-paths
             :compiler compiler}
      node-test? (assoc :notify-command ["node" output-to]))))

(defproject deercreeklabs/s3-utils "0.3.4-SNAPSHOT"
  :description "Utilities for working with S3"
  :url "https://github.com/deercreeklabs/s3-utils"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :lein-release {:scm :git
                 :deploy-via :clojars}

  :pedantic? :abort

  :profiles
  {:dev
   {:global-vars {*warn-on-reflection* true}
    :source-paths ["dev" "src"]
    :repl-options {:init-ns user}
    :plugins
    [[lein-ancient "0.6.15"
      :exclusions [org.apache.httpcomponents/httpclient
                   com.amazonaws/aws-java-sdk-s3 commons-logging commons-codec]]
     [lein-cljsbuild "1.1.7" :exclusions [org.clojure/clojure]]
     [lein-cloverage "1.0.13" :exclusions [fipp org.clojure/clojure
                                           org.clojure/tools.reader]]
     [lein-doo "0.1.11"
      :exclusions [org.clojure/clojure org.clojure/clojurescript]]
     ;; Because of confusion with a defunct project also called
     ;; lein-release, we exclude lein-release from lein-ancient.
     [lein-release "1.0.9" :upgrade false
      :exclusions [org.clojure/clojure]]
     [s3-wagon-private "1.3.2" :exclusions [commons-logging]]]
    :dependencies
    [[doo "0.1.11"]
     [org.clojure/tools.namespace "0.2.11"]]}}

  :dependencies
  [[com.amazonaws/aws-java-sdk-s3 "1.11.475"
    :exclusions [com.fasterxml.jackson.core/jackson-core
                 com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
                 joda-time]]
   [com.taoensso/timbre "4.10.0" :exclusions [org.clojure/tools.reader]]
   [commons-logging/commons-logging "1.2"]
   [deercreeklabs/async-utils "0.1.14"]
   [deercreeklabs/baracus "0.1.14"]
   [deercreeklabs/log-utils "0.2.4"]
   [org.clojure/core.async "0.4.490"]
   [org.clojure/clojure "1.10.0"]
   [org.clojure/clojurescript "1.10.439"]
   [prismatic/schema "1.1.9"]]

  :test-selectors {:default (complement :slow)
                   :the-one :the-one
                   :slow :slow
                   :all (constantly true)}

  :cljsbuild
  {:builds
   [~(make-build-conf "node-test-none" :node :test :none
                      "deercreeklabs.node-test-runner")
    ~(make-build-conf "node-test-adv" :node :test :advanced
                      "deercreeklabs.node-test-runner")]}

  :aliases
  {"auto-test-cljs" ["do"
                     "clean,"
                     "cljsbuild" "auto" "node-test-none"]
   "auto-test-cljs-adv" ["do"
                         "clean,"
                         "cljsbuild" "auto" "node-test-adv"] })
