(ns jepsen.eloqkv.core
  "Top-level test runner, integration point for various workloads and nemeses."
  (:require
   [clojure [string :as str]]
   [clojure.tools.logging :refer [info]]
   [jepsen [cli :as cli]
    [checker :as checker]
    [control :as c]
    [generator :as gen]
    [tests :as tests]
    [util :as util :refer [parse-long]]]
   [jepsen.os.ubuntu :as ubuntu]
   [jepsen.eloqkv [append :as append]
    [counter :as counter]
    [set :as set]
    [db     :as db]
    [nemesis :as nemesis]]))

(def workloads
  "A map of workload names to functions that can take opts and construct
  workloads."
  {:set set/workload
   :counter counter/workload
   :append  append/workload})

(def standard-workloads
  "The workload names we run for test-all by default."
  (keys workloads))

(def nemeses
  "Types of faults a nemesis can create."
  #{:pause :kill :partition :clock :member :island :mystery})

(def standard-nemeses
  "Combinations of nemeses for tests"
  [[]
   [:pause]
   [:kill]
   [:partition]
   [:island]
   [:mystery]
   [:pause :kill :partition :clock :member]])

(def special-nemeses
  "A map of special nemesis names to collections of faults"
  {:none      []
   :standard  [:pause :kill :partition :clock :member]
   :all       [:pause :kill :partition :clock :member :island :mystery]})

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and returns a collection of keyword
  faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

(defn parse-internal-nodes-spec
  "Takes a comma-separated string and returns a collection of nodes name."
  [spec]
  (->> (str/split spec #",")))

(defn crash-checker
  "Reports on unexpected process crashes in the logfiles. This is... a terrible
  hack and will probably break in later versions of Jepsen; it relies on the
  fact that the DB is still running. It's also going to break retrospective
  analyses, but... better than nothing, and I'm short on time to cut a whole
  new Jepsen release for this."
  []
  (reify checker/Checker
    (check [this test history opts]
      (if-let [crashes (db/logged-crashes test)]
        {:valid?  false
         :crashes crashes}
        {:valid? true}))))

(defn redis-test
  "Builds up a Redis test from CLI options."
  [opts]
  (let [workload ((workloads (:workload opts)) opts)
        db        (db/eloqkv  (:nodes opts))
        nemesis   (nemesis/package
                   {:db      db
                    :nodes   (:nodes opts)
                    :faults  (set (:nemesis opts))
                    :partition {:targets [:one
                                          :primaries
                                          :majority
                                          :majorities-ring]}
                    :pause     {:targets [:one
                                          :primaries
                                          :majority
                                          :all]}
                    :kill      {:targets [:one
                                          ;; :primaries
                                          ;; :majority
                                          ;; :all
                                          ]}
                    :interval  (:nemesis-interval opts)})
        _ (info (pr-str nemesis))]
    (let [test_opt (merge tests/noop-test
                          opts
                          workload
                          {:checker    (checker/compose
                                        {:perf        (checker/perf
                                                       {:nemeses (:perf nemesis)})
                                         :clock       (checker/clock-plot)
                                         :crash       (crash-checker)
                                         :stats       (checker/stats)
                                         :exceptions  (checker/unhandled-exceptions)
                                         :workload    (:checker workload)})
                           :db         db
                           :generator  (->> (:generator workload)
                                            (gen/stagger (/ (:rate opts)))
                                            (gen/nemesis (:generator nemesis))
                                            (gen/time-limit (:time-limit opts)))
                           :name       (str "eloqkv "
                                            (name (:workload opts)) " "
                                            (str/join "," (map name (:nemesis opts))))
                           :concurrency 6
                           :nemesis    (:nemesis nemesis)
                           :os         ubuntu/os})]
      (info "test_opt:" test_opt)
      test_opt)))

(def cli-opts
  "Options for test runners."
  [[nil "--follower-proxy" "If true, proxy requests from followers to leader."
    :default false]

   [nil "--key-count INT" "For the append test, how many keys should we test at once?"
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--max-txn-length INT" "What's the most operations we can execute per transaction?"
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--max-writes-per-key INT" "How many writes can we perform to any single key, for append tests?"
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
    :parse-fn parse-nemesis-spec
    :validate [(partial every? (into nemeses (keys special-nemeses)))
               (str "Faults must be one of " nemeses " or "
                    (cli/one-of special-nemeses))]]

   [nil "--nemesis-interval SECONDS" "How long to wait between nemesis faults."
    :default  3
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

   [nil "--nuke-after-leave" "If true, kills and wipes data files for nodes after they leave the cluster. Enabling this flag lets the test run for longer, but also prevents us from seeing misbehavior by nodes which SOME nodes think are removed, but which haven't yet figured it out themselves. We have this because Redis doesn't actually shut nodes down when they find out they're removed."
    :default true]

   [nil "--raft-version VERSION" "What version of redis-raft should we test?"
    :default "407ed4e"]

   [nil "--raft-repo URL" "Where we clone redis-raft from?"
    :default "https://github.com/redislabs/redisraft"]

   ["-r" "--rate HZ" "Approximate number of requests per second per thread"
    :default 10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "must be a positive number"]]

   [nil "--raft-log-max-file-size BYTES" "Size of the raft log, before compaction"
    ; Default is 64MB, but we like to break things. This works out to about
    ; every 5 seconds with 5 clients.
    :default 32000
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--raft-log-max-cache-size BYTES" "Size of the in-memory Raft Log cache"
    :default 1000000
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--tcpdump" "Create tcpdump logs for debugging client-server interaction"
    :default false]

   [nil "--redis-version VERSION" "What version of Redis should we test?"
    :default "6.2.2"]

   [nil "--redis-repo URL" "Where we clone redis from?"
    :default "https://github.com/redis/redis"]

   [nil "--standby-mode" "Enable EloqKV standby mode"
    :default false]

   [nil "--auto-start" "Enable auto start EloqKV cluster"
    :default false]

   [nil "--internal-nodes nodes-list" "A comma-separated list of internal nodes which provide log service and storage service"
    :parse-fn parse-internal-nodes-spec]

   ["-w" "--workload NAME" "What workload should we run?"
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]])

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of test options, by
  combining all workloads and nemeses."
  [opts]
  (let [nemeses     (if-let [n (:nemesis opts)]  [n] standard-nemeses)
        workloads   (if-let [w (:workload opts)] [w] standard-workloads)
        counts      (range (:test-count opts))]
    (->> (for [i counts, n nemeses, w workloads]
           (assoc opts :nemesis n :workload w))
         (map redis-test))))

(defn -main
  "Handles CLI args."
  [& args]
  (cli/run! (merge (cli/test-all-cmd {:tests-fn all-tests
                                      :opt-spec cli-opts})
                   (cli/single-test-cmd {:test-fn  redis-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
