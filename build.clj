(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.io File)))

(def main 'opawssm.cli)
(def version "0.1.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" "opawssm" version))
(def os-name (-> "os.name"
                 System/getProperty
                 str/lower-case
                 (str/replace #"\s+" "-")
                 (str/replace #"-\d+$" "")))
(def os-arch (System/getProperty "os.arch"))
(def native-image-path (str/join File/separator ["target" version
                                                 (str os-name "-" os-arch)
                                                 "opawssm"]))
(def docker-graal-version "22.1.0")
(def docker-clojure-version "1.11.1.1113")

(def windows?
  (some-> (System/getProperty "os.name")
          str/lower-case
          (str/index-of "win")))

(def native-image-bin
  (cond-> "native-image"
          windows? (str ".cmd")))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :compile-opts {:direct-linking true}})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis basis
           :main main}))

(defn native-image [_]
  (println "Building opawssm for" (str os-name "/" os-arch))
  (println "PATH:" (System/getenv "PATH"))
  (println "JAVA_HOME:" (System/getenv "JAVA_HOME"))
  (uberjar nil)
  (io/make-parents native-image-path)
  (let [java-home (System/getenv "JAVA_HOME")]
    (b/process {:command-args
                [(str/join File/separator [java-home "bin" native-image-bin])
                 "-jar" jar-file
                 "--initialize-at-build-time"
                 "--no-fallback" "-H:IncludeResources=.*"
                 "-H:ReflectionConfigurationFiles=resources/reflect-config.json"
                 (str "-H:Name=" native-image-path)]})))

(defn native-image-docker [{:keys [arch]}]
  (let [arch (if (= "arm64" arch) "aarch64" arch)
        dir (str/join File/separator ["target" version (str "linux-" arch)])]
    (b/process {:command-args
                ["docker" "buildx" "build" (str "--platform=linux/" arch)
                 (str "--build-arg=CLJ_VERSION=" docker-clojure-version)
                 (str "--build-arg=GRAAL_VERSION=" docker-graal-version)
                 "--load" "-t" "opawssm-builder" "."]})
    (b/process {:command-args
                ["docker" "rm" "-f" "opawssm-builder"]})
    (b/process {:command-args
                ["docker" "run" (str "--platform=linux/" arch)
                 "--name" "opawssm-builder" "opawssm-builder"]})
    (b/process {:command-args ["docker" "stop" "opawssm-builder"]})
    (io/make-parents dir)
    (b/process {:command-args
                ["docker" "cp"
                 (str "opawssm-builder:/opawssm/" dir "/opawssm")
                 (str dir "/opawssm")]})
    (b/process {:command-args ["docker" "rm" "opawssm-builder"]})))

(defn all [_]
  (native-image nil)
  (native-image-docker {:arch "aarch64"})
  (native-image-docker {:arch "amd64"}))
