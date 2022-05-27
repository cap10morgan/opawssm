(ns opawssm.debug)

(def ^:dynamic *debug* false)

(defn debug
  [& msg]
  (binding [*out* *err*]
    (when *debug* (apply println msg))))
