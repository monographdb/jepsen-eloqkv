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
   [jepsen.redis.client :as rc]
   [knossos.model :as model :refer [inconsistent]]
   [jepsen.checker.timeline :as timeline]
   [slingshot.slingshot :refer [throw+]]
   [taoensso.carmine :as car :refer [wcar]])
  (:import
   (knossos.model Model)))

(def key-range (vec (range 3)))
(defn rand-val [] (rand-int 5))

(defn r
  "Read a random subset of keys."
  [_ _]
  (->> (util/random-nonempty-subset key-range)
       (mapv (fn [k] [:read k nil]))
       (array-map :type :invoke, :f :txn, :value)))

(defn w [_ _]
  "Write a random subset of keys."
  (->> (util/random-nonempty-subset key-range)
       (mapv (fn [k] [:write k (rand-val)]))
       (array-map :type :invoke, :f :txn, :value)))

(defn incr [_ _]
  "Write a random subset of keys."
  (->> (util/random-nonempty-subset key-range)
       (mapv (fn [k] [:incr k nil]))
       (array-map :type :invoke, :f :txn, :value)))

(defn apply-mop!
  "Executes a micro-operation against a Carmine connection. This gets used in
  two different ways. Executed directly, it returns a completed mop. In a txn
  context, it's executed for SIDE effects, which must be reconstituted later."
  [conn [f k v :as mop]]
  ;; (try 
  (info "mop:" mop)
  (case f
    :read      [f k (wcar conn (car/get k))]
    :write (do (wcar conn (car/set k (str v)))
               mop)
    :incr      [f k (wcar conn (car/incr k))]))



(defn parse-read
  "Turns reads of [:r :x ['1' '2'] into reads of [:r :x [1 2]]."
  [conn [f k v :as mop]]
  (info "f:" f ", type of f:" (type f) " k:" k ", type of k:" (type f) " v:" v ", type of v:" (type v))
  ;; (println "type v:" (type v))
  (try
    (let [result
          (case f
            :read [f k (if (nil? v) nil (parse-long v))]
            :write mop
            :incr mop)]
      (info "parse result:" result)
      result)
    (catch ClassCastException e
      (throw+ {:type        :unexpected-read-type
               :key         k
               :value       v}))))
               ; We're getting QUEUED in response to non-MULTI operations; I'm
               ; trying to figure out why, and to get debugging info, I'm gonna
               ; log whatever happens from an EXEC here.
               ;:exec-result (wcar conn (car/exec))}))))

(defrecord Client [conn]
  client/Client

  (open! [this test node]
    (rc/delay-exceptions 5
                         (let [c (rc/open node)]
                           ; (info :conn c)
                           (assoc this :conn (rc/open node)))))

  (setup! [_ test] (info " use client"))


  (invoke! [_ test op]
    (let [value (second (:value op))]
      (info "value " value " count:" (count value))
      (rc/with-exceptions op #{:read}
        (rc/with-conn conn
          (->> (if (< 1 (count value))
                 ; We need a transaction
                 (->> value
                      ; Perform micro-ops for side effects
                      (mapv (partial apply-mop! conn))
                      ; In a transaction
                      (rc/with-txn conn)
                      ; And zip results back into the original txn
                      (mapv (fn [[f k v] record]
                              (info "make result" f k v record)
                              [f k (case f
                                     :read      record
                                     :write v
                                     :incr record)])
                            value))

                 ; Just execute the mop directly, without a txn
                 (->> value
                      (mapv (partial apply-mop! conn))))

               ; Parse integer reads
               (mapv (partial parse-read conn))
               ; Returning that completed txn as an OK op
               (assoc op :type :ok, :value)
            ;;  (info )
               )))))

  (teardown! [_ test])

  (close! [this test]
    (rc/close! conn)))

(defrecord MultiRegister []
  Model
  (step [this op]
    (assert (= (:f op) :txn))
    ;; (info "op:" op)
    (reduce (fn [state [f k v]]
                  ;; (info "state: " state)
              ;; (info ":f" f " k: " k " v:" v)
              ; Apply this particular op
              (case f
                :read  (if (or (nil? v)
                               (= v (get state k)))
                         state
                         (reduced
                          (inconsistent
                           (str (pr-str (get state k)) "≠" (pr-str v)))))
                :incr  (cond
                         (nil? v)  state
                         (= (- v 1) (get state k 0)) (assoc state k v)
                         :else (reduced (inconsistent
                                         (str "incr inconsistent:" (pr-str (get state k 0)) "≠" (pr-str v)))))

                :write (assoc state k v)))

            this
            (:value op))))

(defn multi-register
  "A register supporting read and write transactions over registers identified
  by keys. Takes a map of initial keys to values. Supports a single :f for ops,
  :txn, whose value is a transaction: a sequence of [f k v] tuples, where :f is
  :read or :write, k is a key, and v is a value. Nil reads are always legal."
  [values]
  (map->MultiRegister values))


(defn workload
  [opts]
  (let [n (count (:nodes opts))]
    {:client (Client. nil)
     :generator
     (independent/concurrent-generator
      (* 2 n)
      (range)
      (fn [k]
        (->> (gen/reserve n r w incr)
             (gen/stagger 1)
             (gen/process-limit 20))))
     :checker   (independent/checker
                 (checker/compose
                  {:timeline (timeline/html)
                   :linear   (checker/linearizable
                              {:model (multi-register {})})}))}))
