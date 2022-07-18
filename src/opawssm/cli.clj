(ns opawssm.cli
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [opawssm.debug :refer [debug *debug*]]
            [opawssm.op :as op]
            [opawssm.aws :as aws]
            [clojure.java.shell :refer [sh with-sh-env]])
  (:import (java.time Instant)
           (java.util Date))
  (:gen-class))

(set! *warn-on-reflection* true)

(def global-cli-options
  [["-h" "--help" "Display this message"]
   ["-d" "--debug" "Turn on debugging output"
    :default false]
   ["-a" "--account ACCOUNT" "Name of 1Password account to search for AWS login items"
    :default-desc "All accounts"]
   ["-v" "--vault VAULT" "Name of 1Password vault to search for AWS login items"
    :default-desc "All vaults"]
   ["-t" "--tag TAG" "Name of 1Password tag to filter on"
    :default "AWS"]
   [nil "--no-update" "Disable updating of 1Password item with discovered values"
    :default false]
   ["-r" "--role ROLE" "Name of IAM role you want to assume. Must be a field label in your 1Password item in a section called 'Roles'. The value must be the role ARN."]])

(defn usage [options-summary]
  (->> ["opawssm is a 1Password AWS Session Manager"
        ""
        "Global options:"
        options-summary
        ""
        "Actions:"
        "  exec ['AWS login item name']     Run a command with temporary AWS credentials"
        "  login ['AWS login item name']    Open the AWS web console in your default browser using temporary credentials"
        ""
        "Note: Global options must be specified before actions because actions may have their own options"]
       (str/join \newline)))

(defn error-msg
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn validate-args
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args global-cli-options
                                                               :in-order true)]
    (binding [*debug* (:debug options)]
      (debug "\nargs:" (pr-str arguments))
      (debug "\nopts:" (pr-str options))
      (cond
        (:help options)
        {:exit-message (usage summary) :ok? true}

        errors
        {:exit-message (error-msg errors)}

        (= "exec" (first arguments))
        (let [[arguments cmd] (split-with #(not= "--" %) (rest arguments))
              command (rest cmd)]
          (debug "exec args:" (pr-str arguments))
          (debug "exec cmd:" (pr-str command))
          {:action    "exec"
           :arguments arguments
           :options   options
           :command   command})

        (= "login" (first arguments))
        {:action    "login"
         :arguments (rest arguments)
         :options   options}

        :else
        {:exit-message (usage summary)}))))

(defn exit
  [status msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit status))

(defn exec
  [arguments {:keys [account vault tag] :as options} command]
  (let [item-name         (first arguments)
        op-list-items     (if account
                            #(op/list-items :accounts [account] :tags [%])
                            #(op/list-items :tags [%]))
        op-item-name      (or item-name
                              (-> tag op-list-items first :title))
        op-creds-item     (if account
                            (op/get-item account op-item-name)
                            (op/get-item op-item-name))
        _                 (when op-creds-item
                            (debug "\nFound 1Password item:" op-item-name))
        _                 (debug "\n1Password item details:"
                                 (with-out-str
                                   (pprint/pprint op-creds-item)))
        _                 (debug "\nTop-level 1Password item keys:"
                                 (-> op-creds-item keys pr-str))
        _                 (debug "\n1Password item field labels:"
                                 (->> op-creds-item :fields (map :label) pr-str))
        access-key-id     (op/get-item-field-value :item op-creds-item
                                                   :field :AccessKeyId)
        secret-access-key (op/get-item-field-value :item op-creds-item
                                                   :field :SecretAccessKey)
        account-id        (op/get-item-field-value :item op-creds-item
                                                   :field :AccountId)
        username          (op/get-item-field-value :item op-creds-item
                                                   :field :username)
        mfa-arn           (op/get-item-field-value :item op-creds-item
                                                   :field :MfaArn)
        mfa-serial        (cond
                            mfa-arn
                            (do
                              (debug "\nFound MfaArn in 1Password item")
                              mfa-arn)

                            (and account-id username)
                            (do
                              (debug "\nFound AccountId and username in 1Password item")
                              (str "arn:aws:iam::" account-id ":mfa/" username))

                            :else
                            (do
                              (debug "\nGetting MFA device serial from AWS")
                              (when-let [mfa-serial (-> (aws/get-mfa-devices
                                                          access-key-id
                                                          secret-access-key)
                                                        first
                                                        :SerialNumber)]
                                (when-not (:no-update options)
                                  (let [section (op/get-item-field-section
                                                  op-creds-item :AccessKeyId)]
                                    (op/assoc-item-field-value :item op-creds-item
                                                               :section section
                                                               :field :MfaArn
                                                               :value mfa-serial)))
                                mfa-serial)))
        token             (op/get-item-otp op-item-name)
        _                 (if access-key-id
                            (debug "\nFound AccessKeyId:" access-key-id)
                            (exit 1 "No AccessKeyId field found in 1Password item"))
        _                 (if secret-access-key
                            (debug "Found SecretAccessKey:"
                                   (apply str (repeat (count secret-access-key) \*)))
                            (exit 1 "No SecretAccessKey field found in 1Password item"))
        _                 (if token
                            (debug "Found OTP in 1Password")
                            (exit 1 "No OTP found in 1Password item"))
        ;; for testing invalid tokens
        ;token             (+ 100000 (rand-int 900000))
        temp-creds        (if-let [role (:role options)]
                            (let [role-arn (op/get-item-field-value
                                             :item op-creds-item
                                             :section "Roles"
                                             :field role)]
                              (aws/assume-role
                                :access-key-id access-key-id
                                :secret-access-key secret-access-key
                                :role role-arn
                                :session-name (-> role
                                                  (str/replace #"\s+" "_")
                                                  (str/replace #"[^\w+=,.@-]" ""))
                                :serial mfa-serial
                                :token token))
                            (aws/get-session-token access-key-id
                                                   secret-access-key
                                                   {:serial mfa-serial
                                                    :token token}))]
    (when-let [error (:error temp-creds)]
      (exit 1 (:Message error)))
    (debug "\nRunning" command)
    (let [^Instant expiry (.toInstant ^Date (:Expiration temp-creds))
          env             (into {} (System/getenv))]
      (with-sh-env
        (merge env {"AWS_ACCESS_KEY_ID"      (:AccessKeyId temp-creds)
                    "AWS_SECRET_ACCESS_KEY"  (:SecretAccessKey temp-creds)
                    "AWS_SESSION_TOKEN"      (:SessionToken temp-creds)
                    "AWS_SECURITY_TOKEN"     (:SessionToken temp-creds)
                    "AWS_SESSION_EXPIRATION" (.toString expiry)})
        (let [{:keys [out err] :as result} (apply sh command)]
          (if (zero? (:exit result))
            (println out)
            (exit (:exit result) err)))))))

(defn -main
  [& args]
  (let [{:keys [action arguments command options exit-message ok?]}
        (validate-args args)]
    (binding [*debug* (:debug options)]
      (if exit-message
        (exit (if ok? 0 1) exit-message)
        (case action
          "exec" (do
                   (exec arguments options command)
                   (shutdown-agents)
                   (exit 0 ""))
          "login" (println "\nOpening the web console!")
          (do
            (println "Unknown action" action)))))))

