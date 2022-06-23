(ns opawssm.debug)

(set! *warn-on-reflection* true)

(def ^:dynamic *debug* false)

(defn debug
  [& msg]
  (binding [*out* *err*]
    (when *debug* (apply println msg))))
