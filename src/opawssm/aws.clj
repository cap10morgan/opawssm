(ns opawssm.aws
  (:require ;; these requires prevent dynamic loads at runtime that make GraalVM sad
            [cognitect.aws.protocols.json]
            [cognitect.aws.protocols.common]
            [cognitect.aws.protocols.rest]
            [cognitect.aws.protocols.rest-xml]
            [cognitect.aws.protocols.query]
            [cognitect.aws.http.cognitect :as http]
            [cognitect.aws.sts.specs]
            [clojure.spec.alpha]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as aws-creds]
            [opawssm.debug :refer [debug]]))

(defn make-client
  [api creds-provider]
  (let [client (aws/client {:api                  api
                            :credentials-provider creds-provider
                            :http-client          (http/create)})]
    (aws/validate-requests client true)
    client))

(def make-sts-client (partial make-client :sts))
(def make-iam-client (partial make-client :iam))

(defn get-mfa-devices
  [access-key-id secret-access-key]
  (let [cp     (aws-creds/basic-credentials-provider
                 {:access-key-id     access-key-id
                  :secret-access-key secret-access-key})
        client (make-iam-client cp)
        req    {:op :ListMFADevices}
        resp   (aws/invoke client req)]
    (debug "\nListMFADevices response:" (pr-str resp))
    (:MFADevices resp)))

(defn get-session-token
  [access-key-id secret-access-key {:keys [serial token] :as _mfa} & [opts]]
  (let [cp     (aws-creds/basic-credentials-provider
                 {:access-key-id     access-key-id
                  :secret-access-key secret-access-key})
        client (make-sts-client cp)
        req    (cond-> {:op      :GetSessionToken
                        :request {:DurationSeconds (or (:duration opts) (* 15 60))}}
                       (and serial token)
                       (update :request merge
                               {:SerialNumber serial
                                :TokenCode    (str token)}))
        _      (debug "\nAWS GetSessionToken request:" (pr-str req))
        resp   (aws/invoke client req)]
    (debug "\nAWS GetSessionToken response:" (pr-str resp))
    (if-let [error (:ErrorResponse resp)]
      {:error (:Error error)}
      (:Credentials resp))))

(defn assume-role
  [& {:keys [access-key-id secret-access-key role session-name serial token
             duration]}]
  (let [cp     (aws-creds/basic-credentials-provider
                 {:access-key-id     access-key-id
                  :secret-access-key secret-access-key})
        client (make-sts-client cp)
        req    (cond-> {:op      :AssumeRole
                        :request {:RoleArn         role
                                  :RoleSessionName session-name
                                  :DurationSeconds (or duration (* 15 60))}}
                       (and serial token)
                       (update :request merge
                               {:SerialNumber serial
                                :TokenCode    (str token)}))
        _      (debug "\nAWS AssumeRole request:" (pr-str req))
        resp   (aws/invoke client req)]
    (debug "\nAWS AssumeRole response:" (pr-str resp))
    (if-let [error (:ErrorResponse resp)]
      {:error (:Error error)}
      (:Credentials resp))))
