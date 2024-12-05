(ns jepsen.eloqkv.client
  "Helper functions for working with Carmine, our redis client."
  (:require [clojure.tools.logging :refer [info warn]]
            [slingshot.slingshot :refer [try+ throw+]]
            [taoensso.carmine :as car :refer [wcar]]
            [taoensso.carmine [connections :as conn]]))

; There's a lot of weirdness in the way Carmine handles connection pooling;
; you're expected to pass around maps which *specify* how to build a connection
; pool, and it maps that to a connection pool under the hood--I think via
; connections/conn-pool memoization. What worries me is that that memoization
; might return connection pools for *other* clients, royally screwing up our
; open/close logic.

; Now, I can't figure out a way to just get you know, a Plain old Connection
; out of Carmine safely. So what we're gonna do here is cheat: we build a
; Carmine pool that builds a fresh conn on every call to get-conn, pull a
; connection out of it, throw away the pool (which is not in general a good
; idea, but we know what we're doing), and wrap that connection in our *own*
; pool. This doesn't have to do any actual connection tracking because we only
; ever have one thread interact with a pool at a time.

(defrecord SingleConnectionPool [conn]
  conn/IConnectionPool
  (get-conn [_ spec] conn)

  (release-conn [_ conn])

  (release-conn [_ conn exception])

  java.io.Closeable
  (close [_] (conn/close-conn conn)))

(defn open
  "Opens a connection to a node. Our connections are Carmine IConnectionPools.
  Options are merged into the conn pool spec."
  ([node]
   (open node {}))
  ([node opts]
   (info "open connection")
   (let [spec (merge {:host       node
                      :port       6389
                      :timeout-ms 10000}
                     opts)
         seed-pool (conn/conn-pool :none)
         conn      (conn/get-conn seed-pool spec)]
     {:pool (SingleConnectionPool. conn)
      ; See with-txn
      :in-txn? (atom false)
      :spec spec})))

(defn close!
  "Closes a connection to a node."
  [^java.io.Closeable conn]
  (info "close connection")
  (.close (:pool conn)))

(defmacro with-exceptions
  "Takes an operation, an idempotent :f set, and a body; evaluates body,
  converting known exceptions to failed ops."
  [op idempotent & body]
  `(let [crash# (if (~idempotent (:f ~op)) :fail :info)]
     (try ~@body
          (catch clojure.lang.ExceptionInfo e#
            (let [message# (.getMessage e#)
                  message-type# (type message#)
                  data# (.getData e#)]
              (info "Message type: " message-type#)
              (condp re-find message#
                (re-pattern
                 (str "Transaction failed due to internal cc request is directed to a follower.|"
                      "Failed to initialize the transaction.|"
                      "Transaction failed due to internal request timeout.|"
                      "Eloqkv internal error during transaction.|"
                      "MOVED|"
                      "Fail to perform operations on a follower node when EloqKV is in standby mode."))
                (assoc ~op :type :fail, :error [:eloqkv-internal-error message#])
                

                ;; #"Transaction failed due to internal cc request is directed to a follower."
                ;; (assoc ~op :type :fail, :error [:eloqkv-internal-error message#])

                ;; #"Failed to initialize the transaction."
                ;; (assoc ~op :type :fail, :error [:eloqkv-internal-error message#])

                ;; #"Transaction failed due to internal request timeout."
                ;; (assoc ~op :type :fail, :error [:eloqkv-internal-error message#])

                ;; #"Eloqkv internal error during transaction."
                ;; (assoc ~op :type :fail, :error [:eloqkv-internal-error message#])

                ;; #"MOVED"
                ;; (assoc ~op :type :fail, :error [:eloqkv-internal-error message#])

                (do (info "unknown ExceptionInfo: " message#)
                    (assoc ~op :type :info, :error message#)))))

          (catch java.net.SocketException e#
            (assoc ~op :type crash#, :error [:socket (.getMessage e#)]))
          (catch java.net.SocketTimeoutException e#
            (assoc ~op :type crash#, :error [:socket-timeout (.getMessage e#)]))
          (catch  java.lang.NumberFormatException e#
            (assoc ~op :type crash#, :error [:number-format-exception (.getMessage e#)]))
          (catch  java.net.ConnectException e#
            (assoc ~op :type crash#, :error [:net-connect-exception (.getMessage e#)]))
          (catch   java.io.EOFException e#
            (assoc ~op :type crash#, :error [:io-eof-exception (.getMessage e#)]))

          (catch Throwable t#
            (let [error-message# (.getMessage t#)]
              (.printStackTrace t#)
              (info "Caught Throwable:" error-message# " type:" (type t#))
              (assoc ~op :type :fail, :error error-message#))))))

(def primary-test-key "primary-test")

(defn is-primary
  [node]
  (try
    (let [conn (open node)]
      (info "node: " node)
      (wcar conn (car/set primary-test-key 1))
      node)
    (catch Exception e
      (info node " is not primary"))))

(defn is-primary-with-conn
  [conn]
  (try
    (wcar conn (car/set primary-test-key 1))
    true
    (catch Exception e
      (info "Is not primary")
      false)))

(defmacro delay-exceptions
  "Adds a short (n second) delay when an exception is thrown from body. Helpful
  for not spamming the log with reconnection attempts to a down server, at the
  cost of potentially missing the first moments of a server's life."
  [n & body]
  `(try ~@body
        (catch Exception e#
          (try
            (Thread/sleep (* ~n 1000))
            (catch InterruptedException interruped-error#
              (Thread/interrupted)
              (warn "Sleep was interrupted!" interruped-error#)))
          ;; (throw e#)
          )))

(defn abort-txn!
  "Takes a connection and, if in a transaction, calls discard on it, resetting
  the in-txn state to false. None of this is threadsafe; we can cheat because
  our conns are bound to threads. We ignore discard-without-multi because we
  must, whenever a MULTI throws, issue a discard just in case."
  [conn]
  (when @(:in-txn? conn)
    (try+
      ;(info :multi-discarding)
     (wcar conn (car/discard))
     (catch [:prefix :err] e
        ;(info :abort-caught (.getMessage (:throwable &throw-context)))
       (condp re-find (.getMessage (:throwable &throw-context))
          ; Don't care, we're being safe!
         #"ERR DISCARD without MULTI" nil
          ; Something else
         (throw+))))
    ;(info :multi-discarded)
    (reset! (:in-txn? conn) false))
  conn)

(defn start-txn!
  "Takes a connection and begins a MULTI transaction, updating the connection's
  transaction state. Forces the current txn to discard, if one exists."
  [conn]
  (if (compare-and-set! (:in-txn? conn) false true)
    (do ;(info :multi-starting)
      (wcar conn (car/multi))
        ;(info :multi-started)
      conn)
    (do ;(info "Completing discard of previous (likely aborted) transaction before new one.")
      (abort-txn! conn)
      (recur conn))))

(defmacro with-conn
  "Call before *any* use of a connection. Ensures that the connection is not in
  a transaction state before executing body. Not thread-safe, like everything
  else here, but that's OK, cuz we're singlethreaded."
  [test conn & body]
  `(do (abort-txn! ~conn)
       (if (and (:standby-mode ~test) (is-primary-with-conn ~conn))
         (throw (ex-info "Fail to perform operations on a follower node when EloqKV is in standby mode." {}))
         (do ~@body))))

(defmacro with-txn
  "Runs in a multi ... exec scope. Discards body, returns the results of exec."
  [conn & body]
  `(try (start-txn! ~conn)
        ~@body
        ;(info :multi-exec)
        (let [r# (wcar ~conn (car/exec))]
          (info "tranaction result:" r#)
          (if (nil? r#)
            (throw (ex-info "Eloqkv internal error during transaction." {}))
            r#))

        (catch clojure.lang.ExceptionInfo e#
          (throw e#))

        (catch Throwable t#
          ; This might fail, but we try to be polite.
          (let [error-message# (.getMessage t#)]
            (info "abort txn, error message:" error-message#))
          (abort-txn! ~conn)
          (throw t#))))
