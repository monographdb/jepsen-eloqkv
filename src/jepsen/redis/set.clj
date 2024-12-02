(ns jepsen.redis.set
  "Adds elements to sets and reads them back"
  (:refer-clojure :exclude [test read])
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
             [client :as client]
             [generator :as gen]
             [util :as util :refer [parse-long]]]
            [jepsen.redis [client :as rc]]
            [taoensso.carmine :as car :refer [wcar]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def set-name "test")

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (rc/delay-exceptions 5
                         (let [c (rc/open node)]
                           (assoc this :conn (rc/open node)))))

  (setup! [_ test])

  (invoke! [_ test op]
    (rc/with-exceptions op #{:read}
      (rc/with-conn conn

        (info "op:" op)
        (case (:f op)
          :add    (let [elem (str (:value op))]
                    (wcar conn (car/sadd set-name elem))
                    (assoc op :type :ok))
          :read   (let [v  (wcar conn (car/smembers set-name))
                        v-num (sort (mapv parse-long v))]
                    (assoc op :type :ok, :value v-num))))))

  (teardown! [_ test])

  (close! [this test]
    (rc/close! conn)))

(defn adds
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :add, :value x}))
       gen/seq))

(defn reads
  []
  {:type :invoke, :f :read, :value nil})

(defn workload
  [opts]
  {:client (Client. nil)
   :generator (->> (gen/reserve (/ (:concurrency opts) 2)
                                (adds)
                                (reads))
                   (gen/stagger 1/2))
   :checker   (checker/set-full)})
