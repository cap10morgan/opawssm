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
                 (str/replace #"-\d+$" "")
                 (str/replace #"-server" "")))
(def os-arch (let [arch (System/getProperty "os.arch")]
               (case arch
                 "x86_64" "amd64"
                 "aarch64" "arm64"
                 arch)))
(defn native-image-path
  [& [os arch]]
  (str/join File/separator ["target" version
                            (str (or os os-name) "-" (or arch os-arch))
                            "opawssm"]))
(def docker-graal-version "22.1.0")
(def docker-clojure-version "1.11.1.1129")

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
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis        basis
                  :src-dirs     ["src"]
                  :class-dir    class-dir
                  :compile-opts {:direct-linking true}})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :basis     basis
           :main      main}))

(defn native-image [_]
  (println "Building opawssm for" (str os-name "/" os-arch))
  (uberjar nil)
  (println "Built uberjar")
  (let [nip (native-image-path)]
    (println "Building binary in" nip)
    (io/make-parents nip)
    (let [ni-home (or (System/getenv "GRAALVM_HOME")
                      (System/getenv "JAVA_HOME"))]
      (b/process {:command-args
                  [#_(str/join File/separator [ni-home "bin" native-image-bin])
                   native-image-bin
                   "-jar" jar-file
                   "--initialize-at-build-time"
                   "--no-fallback" "-H:IncludeResources=.*"
                   "-H:ReflectionConfigurationFiles=resources/reflect-config.json"
                   (str "-H:Name=" nip)]}))))

(defn native-image-docker [{:keys [arch]}]
  (let [arch (if (= "aarch64" arch) "arm64" arch)
        path (native-image-path "linux" arch)]
    (b/process {:command-args
                ["docker" "buildx" "build" "--progress=plain"
                 (str "--platform=linux/" arch)
                 (str "--build-arg=CLJ_VERSION=" docker-clojure-version)
                 (str "--build-arg=GRAAL_VERSION=" docker-graal-version)
                 "--load" "-t" "opawssm-builder" "."]})
    (b/process {:command-args
                ["docker" "rm" "-f" "opawssm-builder"]})
    (b/process {:command-args
                ["docker" "create" (str "--platform=linux/" arch)
                 "--name" "opawssm-builder" "opawssm-builder"
                 "ls" "-la"]})
    (b/process {:command-args
                ["docker" "run" (str "--platform=linux/" arch)
                 "--rm" "opawssm-builder"
                 "ls" "-la" "target"]})
    (b/process {:command-args
                ["docker" "run" (str "--platform=linux/" arch)
                 "--rm" "opawssm-builder"
                 "ls" "-la" "target/0.1.0"]})
    (b/process {:command-args
                ["docker" "run" (str "--platform=linux/" arch)
                 "--rm" "opawssm-builder"
                 "ls" "-la" "target/0.1.0/*/"]})
    (io/make-parents path)
    (b/process {:command-args
                ["docker" "start" "-a" "opawssm-builder"]})
    (b/process {:command-args
                ["docker" "cp"
                 (str "opawssm-builder:/opawssm/" path)
                 path]})))
    ;(b/process {:command-args ["docker" "rm" "opawssm-builder"]})))

(defn all [_]
  (native-image nil)
  (native-image-docker {:arch "arm64"})
  (native-image-docker {:arch "amd64"}))
