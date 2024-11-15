(ns jepsen.eloqkv.register
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [taoensso.carmine :as car :refer [wcar]]
            [knossos.model :as model]
            [jepsen [checker :as checker]
             [cli :as cli]
             [client :as client]
             [control :as c]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]
             [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
             
            [jepsen.control.util :as cu]
            [jepsen.eloqkv.db :as db]
            [jepsen.eloqkv.connect :as redis-conn])

  (:import (knossos.model Model)))


(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn incr   [_ _] {:type :invoke, :f :incr})



(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong (str s))))

(defrecord IntRegister [value]
  Model
  (step [r op]
    (condp = (:f op)
      :write (IntRegister. (:value op))
      :incr  (do ;;(println "Current value before increment:" value) ; 打印当前 value 的值 
               (if (= (inc value) (:value op))
                 (IntRegister. (:value op))
                 (knossos.model/inconsistent (str "can't incr register from " value
                                                  " to " (:value op)))))
      :read  (if (or (nil? (:value op))
                     (= value (:value op)))
               r
               (knossos.model/inconsistent (str "can't read " (:value op)
                                                " from register " value)))))
  Object
  (toString [this] (pr-str value)))


(defn int-register
  "Given a map of account IDs to balances, models transfers between those
  accounts."
  ([] (IntRegister. nil))
  ([value] (IntRegister. value)))

(defn log-worker-node-mapping
  [worker node]
  (info "Worker ID:" worker "is assigned to node:" node))



(defn create-conn-spec [node]
  {:host      (name node)
   :port       6379
   :timeout-ms 10000})

;; Function to generate the connection options with a specified node
(defn create-wcar-opts [node]
  {:pool (car/connection-pool {})
   :spec (create-conn-spec node)})


(defn client
  "A client for a single register"
  [conn]
  (let [conn-atom (atom nil)]
    (reify client/Client

    ;; (open! [this test node] this)

    ;; (setup! [_ test node]
    ;;   (log-worker-node-mapping (:worker-id test) node)
    ;;   (client {:pool {} :spec {:host (name node) :port 6379 :timeout-ms 5000}}))
      (open! [this test node]
       (do
         (info "hostname " node)
         (reset! conn-atom (create-wcar-opts node)) ;; 
         (info "Connection reopen on node: " node)
         this))
      
      ;; (setup! [this test node] (do (log-worker-node-mapping (:worker-id test) node) 
      ;;                              this))
      (setup! [this test node]
      
        (info "Setting up with node: " node)
        this)  

      (invoke! [this test op]
      ;; (println  (info "invoke conn:" conn))
        (try
          (case (:f op)
          ;; :read (assoc op :type :ok, :value (parse-long (get (car/wcar conn (car/get "int")) 0 "")))
          ;; :write (do (car/wcar conn (car/set "int" (:value op)))
          ;;            (assoc op :type :ok))
          ;; :incr (do (let [new-value (car/wcar conn (car/incr "int"))]
          ;;             ;; (println "new value return by incr" new-value)
          ;;             (assoc op :type :ok, :value new-value))))
            ;; :read (assoc op :type :ok, :value (parse-long (get (let [return-value (redis-conn/with-conn  @conn-atom (car/get "int"))] (info "carmine return value:" return-value) return-value) 0 "")))
            ;; :write (do (redis-conn/with-conn  @conn-atom  (car/set "int" (:value op)))
            ;;            (assoc op :type :ok))
            ;; :incr (do (let [new-value (redis-conn/with-conn  @conn-atom (car/incr "int"))] 
            ;;             (assoc op :type :ok, :value new-value))))
            :read (assoc op :type :ok, :value (parse-long  
                                                           (let [return-value (car/wcar  @conn-atom (car/get "int"))] 
                                                             (info "carmine return value:" return-value) 
                                                             return-value) 
                                                           ))
            :write (do (car/wcar  @conn-atom  (car/set "int" (:value op)))
                       (assoc op :type :ok))
            :incr (do (let [new-value (car/wcar  @conn-atom (car/incr "int"))]
                        (assoc op :type :ok, :value new-value))))

          (catch clojure.lang.ExceptionInfo e
          ;; (println "clojure.lang.ExceptionInfo ")
            (let [err_str (str (.getMessage e))]
              (let [follower_write (re-find #"Transaction failed due to internal cc request is directed to a follower.*" err_str)]
                (let [server_error (re-find #"Server returned unknown reply type*" err_str)]
                  (assoc op :type (if (or (= :read (:f op)) follower_write server_error) :fail :info), :error err_str)))))

          (catch java.net.SocketTimeoutException e
            (println "java.net.SocketTimeoutException")
            (assoc op :type (if (= :read (:f op)) :fail :info), :error :timeout))

          (catch java.io.EOFException e
            (assoc op :type :fail, :error :eof_exception))

          (catch java.lang.NumberFormatException e
            (assoc op :type :fail, :error (str "readnil--- " e)))))

      (teardown! [_ test])

      (close! [this test] ))))


(defn register-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name "register-test"
          ;:os debian/os
          :db (db/db "v2.0.4")
          :client (client nil)
          :nemesis (nemesis/partition-random-halves)
          :model (int-register 0)
          ;; :checker (checker/compose
          ;;           {:perf     (checker/perf)
          ;;            :timeline (timeline/html)
          ;;            :linear   (checker/linearizable)})
          :checker (checker/compose
                    {:perf     (checker/perf)
                     :timeline (timeline/html)
                     :linear   (checker/linearizable)})
          :generator (->> (gen/mix [r w incr] )
                          (gen/stagger 1/5)
                          (gen/nemesis 
                           (gen/seq (cycle [(gen/sleep 30)
                                            {:type :info, :f :start}
                                            (gen/sleep 30)
                                            {:type :info, :f :stop}])))
                          (gen/time-limit (:time-limit opts)))}
         opts))
