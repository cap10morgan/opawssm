(ns opawssm.op
  (:require [clojure.java.shell :refer [sh]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [opawssm.debug :refer [debug]]))

(defn- run-command
  [& args]
  (let [[args json?] (if (= :raw (last args))
                       [(butlast args) false]
                       [(concat args ["--format" "json"]) true])
        command (concat ["op"] args)
        _       (debug "\nRunning command:" (pr-str command))
        {:keys [out err exit]} (apply sh command)]
    (if (zero? exit)
      (do
        (debug "\n1Password command result:" (pr-str out))
        {:result (if json?
                   (json/parse-string out true)
                   (str/trim-newline out))})
      {:error err, :status exit})))

(defn list-accounts
  []
  (let [command ["account" "list"]
        {:keys [result error status]} (apply run-command command)]
    (if result
      result
      (throw (ex-info error
                      {:command (str/join " " (cons "op" command))
                       :status  status})))))

(defn account->shorthand
  [account]
  (-> account :url (str/split #"\.") first))

(defn list-items
  [& {:keys [accounts categories tags]}]
  (let [accounts (or accounts (map account->shorthand (list-accounts)))]
    (loop [account  (first accounts)
           accounts (rest accounts)
           items    []]
      (let [categories (if categories (str/join "," categories) "Login")
            command    ["item" "list" "--account" account "--categories" categories
                        "--tags" (str/join "," tags)]
            {:keys [result error status]} (apply run-command command)]
        (if error
          (throw (ex-info error
                          {:command (str/join " " (cons "op" command))
                           :status  status}))
          (if (seq accounts)
            (recur (first accounts) (rest accounts) (concat items result))
            (concat items result)))))))

(defn get-item
  ([name] (get-item nil name))
  ([account name]
   (let [command ["item" "get" name "--account" account]
         {:keys [result error status]} (apply run-command command)]
     (if result
       result
       (throw (ex-info error
                       {:command (str/join " " (cons "op" command))
                        :status  status}))))))

(defn get-item-field
  [& {:keys [item section field]}]
  (some #(when (and
                 (if section
                   (= (name section) (get-in % [:section :label]))
                   true)
                 (= (name field) (:label %))) %)
        (:fields item)))

(defn get-item-field-value
  [& {:keys [item section field]}]
  (when-let [f (get-item-field :item item :section section :field field)]
    (:value f)))

(defn get-item-field-section
  [& {:keys [item field]}]
  (when-let [f (get-item-field :item item :field field)]
    (get-in f [:section :label])))

(defn get-item-otp
  [name]
  (:result (run-command "item" "get" name "--otp" :raw)))

(defn assoc-item-field-value
  [& {:keys [item section field type value] :or {type "text"}}]
  (let [field-specifier (if section
                          (str (name section) "." (name field))
                          (name field))]
    (run-command "item" "edit" (:title item)
                 (str field-specifier "[" type "]=" value))))
