(ns jepsen.redis.counter
  "Single atomic register test"
  (:refer-clojure :exclude [test read])
  (:require
   [clojure.tools.logging :refer :all]
   [jepsen [client :as client]
    [checker :as checker]
    [generator :as gen]
    [independent :as independent]
    [util :as util :refer [parse-long]]]
   [jepsen.checker.timeline :as timeline]
   [jepsen.redis.client :as rc]
            [knossos.model :as model :refer [inconsistent]]
   [slingshot.slingshot :refer [throw+]]
   [taoensso.carmine :as car :refer [wcar]])
  (:import (knossos.model Model))
  )

(defn r   [_ _] {:type :invoke, :f :read, :value [[:read "key" nil]]})
(defn w   [_ _] {:type :invoke, :f :write, :value [[:write "key" (rand-int 5)]]})
(defn incr [_ _] {:type :invoke, :f :incr, :value [[:incr "key" nil]]})

(defn apply-mop!
  "Executes a micro-operation against a Carmine connection. This gets used in
  two different ways. Executed directly, it returns a completed mop. In a txn
  context, it's executed for SIDE effects, which must be reconstituted later."
  [conn [f k v :as mop]]
  (case f
    :read      [f k (wcar conn (car/get k))]
    :write (do (wcar conn (car/set k (str v)))
               mop)
    :incr      [f k (wcar conn (car/incr k))]))

(defn parse-read
  "Turns reads of [:r :x ['1' '2'] into reads of [:r :x [1 2]]."
  [conn [f k v :as mop]]
  (println "f:" f " k:" k " v:" v)
  (println "type v:" (type v))
  (try
    (case f
      :read [f k (parse-long v)]
      :write mop
      :incr mop)
    (catch ClassCastException e
      (throw+ {:type        :unexpected-read-type
               :key         k
               :value       v}))))
               ; We're getting QUEUED in response to non-MULTI operations; I'm
               ; trying to figure out why, and to get debugging info, I'm gonna
               ; log whatever happens from an EXEC here.
               ;:exec-result (wcar conn (car/exec))}))))

(defrecord AtomicClient [conn]
  client/Client

  (open! [this test node]
    (rc/delay-exceptions 5
                         (let [c (rc/open node)]
                           ; (info :conn c)
                           (assoc this :conn (rc/open node)))))

  (setup! [_ test])


  (invoke! [_ test op]
    (println "value " (:value op) " count:" (count (:value op)))
    (rc/with-exceptions op #{}
      (rc/with-conn conn
        (->> (if (< 1 (count (:value op)))
                 ; We need a transaction
               (->> (:value op)
                      ; Perform micro-ops for side effects
                    (mapv (partial apply-mop! conn))
                      ; In a transaction
                    (rc/with-txn conn)
                      ; And zip results back into the original txn
                    (mapv (fn [[f k v] record]
                            [f k (case f
                                   :read      record
                                   :write v)])
                          (:value op)))

                 ; Just execute the mop directly, without a txn
               (->> (:value op)
                    (mapv (partial apply-mop! conn))))

               ; Parse integer reads
             (mapv (partial parse-read conn))
               ; Returning that completed txn as an OK op
             (assoc op :type :ok, :value)))))

  (teardown! [_ test])

  (close! [this test]
    (rc/close! conn)))

(defrecord IntRegister [value]
  Model
  (step [r op]
    (condp = (:f op)
      :write (IntRegister. (:value op))
      :incr  (do ;;(println "Current value before increment:" value) ; 打印当前 value 的值 
               (if (= (inc value) (:value op))
                 (IntRegister. (:value op))
                 (inconsistent (str "can't incr register from " value
                                                  " to " (:value op)))))
      :read  (if (or (nil? (:value op))
                     (= value (:value op)))
               r
               (inconsistent (str "can't read " (:value op)
                                                " from register " value)))))
  Object
  (toString [this] (pr-str value)))


(defn int-register
  "Given a map of account IDs to balances, models transfers between those
  accounts."
  ([] (IntRegister. nil))
  ([value] (IntRegister. value)))

(defn my_test
  "A partial test, including a generator, model, and checker. You'll need to
  provide a client. Options:

    :nodes            A set of nodes you're going to operate on. We only care
                      about the count, so we can figure out how many workers
                      to use per key.
    :model            A model for checking. Default is (model/cas-register).
    :per-key-limit    Maximum number of ops per key.
    :process-limit    Maximum number of processes that can interact with a
                      given key. Default 20."
  [opts]
  {:checker (independent/checker
             (checker/compose
              {:linearizable (checker/linearizable
                              {:model (:model opts (model/cas-register))})
               :timeline     (timeline/html)}))
;;    :generator (let [n (count (:nodes opts))]
;;                 (independent/concurrent-generator
;;                  n
;;                  (range)
;;                  (fn [k]
;;                    (cond->> (gen/reserve n r (gen/mix [w w w]))
;;                          ; We randomize the limit a bit so that over time, keys
;;                          ; become misaligned, which prevents us from lining up
;;                          ; on Significant Event Boundaries.
;;                      (:per-key-limit opts)
;;                      (gen/limit (* (+ (rand 0.1) 0.9)
;;                                    (:per-key-limit opts)))

;;                      true
;;                      (gen/process-limit (:process-limit opts 20))))))
   :generator (->> (gen/mix [r w incr])
                ;;    (gen/stagger 1)

                   (gen/time-limit (:time-limit opts)))})


(defn workload
  [opts]
  (let [w (my_test (assoc opts :model (int-register 0)))]
    (-> w
        (assoc :client (AtomicClient. nil))
        (update :generator (partial gen/stagger 1/10)))))