(ns jepsen.dgraph.core
  (:gen-class)
  (:require [jepsen [cli :as cli]
                    [checker :as checker]
                    [nemesis :as nemesis]
                    [generator :as gen]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [jepsen.dgraph [support :as s]
                           [bank :as bank]
                           [upsert :as upsert]]))

(def workloads
  "A map of workload names to functions that can take opts and construct
  workloads."
  {:bank    bank/workload
   :upsert  upsert/workload})

(defn dgraph-test
  "Builds up a dgraph test map from CLI options."
  [opts]
  (let [workload ((get workloads (:workload opts)) opts)]
    (merge tests/noop-test
           opts
           workload
           {:name       (str "dgraph " (:version opts) " "
                             (name (:workload opts)))
            :os         debian/os
            :db         (s/db)
            :generator  (->> (:generator workload)
                             (gen/stagger 0)
                             (gen/nemesis
                               (->> (gen/start-stop 10 10)
                                    (gen/time-limit 40)))
                             (gen/time-limit (:time-limit opts)))
            :nemesis    (nemesis/partition-random-halves)
            :checker    (checker/compose
                          {:perf (checker/perf)
                           :timeline (timeline/html)
                           :workload (:checker workload)})})))

(defn parse-long [x] (Long/parseLong x))

(def cli-opts
  "Additional command line options"
  [["-w" "--workload NAME" "Test workload to run"
    :parse-fn keyword
    :missing (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]
   ["-v" "--version VERSION" "What version number of dgraph should we test?"
    :default "1.0.3"]
   [nil "--package-url URL" "Ignore version; install this tarball instead"
    :validate [(partial re-find #"\A(file)|(https?)://")
               "Should be an HTTP url"]]
   ["-f" "--force-download" "Ignore the package cache; download again."
    :default false]
   [nil "--replicas COUNT" "How many replicas of data should dgraph store?"
    :default 3
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]])

(defn -main
  "Handles command line arguments; running tests or the web server."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn   dgraph-test
                                         :opt-spec  cli-opts})
                   (cli/serve-cmd))
            args))
