(ns jepsen.redis.register
  "Single atomic register test"
  (:refer-clojure :exclude [test read])
  (:require [jepsen [client :as client]
             [checker :as checker]
             [generator :as gen]
             [independent :as independent]
             [util :refer [meh]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.tests.linearizable-register :as lr]
            [clojure.tools.logging :refer :all]
            [jepsen.redis [client :as rc]]
[taoensso.carmine :as car :refer [wcar]]
            [knossos.model :as model]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn apply-mop!
  "Executes a micro-operation against a Carmine connection. This gets used in
  two different ways. Executed directly, it returns a completed mop. In a txn
  context, it's executed for SIDE effects, which must be reconstituted later."
  [conn [f kv :as mop]]
  (info  "mop:" mop " f:"  f " kv:" kv)
  (case f
    :read  ();;    [f k (wcar conn (car/lrange k 0 -1))]
    :write ();;(do (wcar conn (car/rpush k (str v)))
              ;; mop)))
    :cas ()
  )
)

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (rc/delay-exceptions 5
                         (let [c (rc/open node)]
                           ; (info :conn c)
                           (assoc this :conn (rc/open node)))))

  (setup! [_ test])

  (invoke! [_ test op]
    (rc/with-exceptions op #{}
      (rc/with-conn conn
        (info "op:" (vector (:f op) ))
        (->> (
               ; Just execute the mop directly, without a txn
               (->> (:value op)
                    (mapv (partial apply-mop! conn))))

             ; Parse integer reads
            ;;  (mapv (partial parse-read conn))
             ; Returning that completed txn as an OK op
             (assoc op :type :ok, :value)))))

  (teardown! [_ test])

  (close! [this test]
    (rc/close! conn)))


(defn workload
  [opts]
  (let [w (lr/test (assoc opts :model (model/cas-register 0)))]
    (-> w
        (assoc :client (Client. nil))
        (update :generator (partial gen/stagger 1/10)))))