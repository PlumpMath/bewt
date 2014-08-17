(ns boot.aether
  (:require
   [clojure.java.io             :as io]
   [clojure.string              :as string]
   [cemerick.pomegranate.aether :as aether]
   [boot.kahnsort               :as ksort])
  (:import
   [org.sonatype.aether.resolution DependencyResolutionException]))

(def offline? (atom false))
(def update?  (atom :daily))

(defn default-repositories
  []
  [["clojars"       "http://clojars.org/repo/"]
   ["maven-central" "http://repo1.maven.org/maven2/"]])

(defn warn
  [& more]
  (binding [*out* *err*] (apply printf more) (flush)))

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error}]
  (when (and (.endsWith name ".jar") (= type :started))
    (warn "Retrieving %s from %s\n" name repo)))

(defn ^:from-leiningen build-url
  "Creates java.net.URL from string"
  [url]
  (try (java.net.URL. url)
       (catch java.net.MalformedURLException _
         (java.net.URL. (str "http://" url)))))

(defn ^:from-leiningen get-non-proxy-hosts
  []
  (let [system-no-proxy (System/getenv "no_proxy")]
    (if (not-empty system-no-proxy)
      (->> (string/split system-no-proxy #",")
           (map #(str "*" %))
           (string/join "|")))))

(defn ^:from-leiningen get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
     (if-let [proxy (System/getenv key)]
       (let [url (build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host            (.getHost url)
          :port            (.getPort url)
          :username        username
          :password        password
          :non-proxy-hosts (get-non-proxy-hosts)}))))

(defn resolve-dependencies*
  [env]
  (aether/resolve-dependencies
    :coordinates        (:dependencies env)
    :repositories       (->> (or (:repositories env) (default-repositories))
                          (map (juxt first (fn [[x y]] (if (map? y) y {:url y}))))
                          (map (juxt first (fn [[x y]] (update-in y [:update] #(or % @update?))))))
    :local-repo         (:local-repo env)
    :offline?           (or @offline? (:offline? env))
    :mirrors            (:mirrors env)
    :proxy              (or (:proxy env) (get-proxy-settings))
    :transfer-listener  transfer-listener))

(def resolve-dependencies-memoized*
  (memoize
    (fn [env]
      (try
        (->> (resolve-dependencies* env)
          ksort/topo-sort
          (map (fn [x] {:dep x :jar (.getPath (:file (meta x)))})))
        (catch Exception e
          (let [root-cause (last (take-while identity (iterate (memfn getCause) e)))]
            (if-not (and (not @offline?) (instance? java.net.UnknownHostException root-cause))
              (throw e)
              (do (reset! offline? true)
                  (resolve-dependencies-memoized* env)))))))))

(defn resolve-dependencies
  [env]
  (->> [:dependencies :repositories :local-repo :offline? :mirrors :proxy]
    (select-keys env)
    resolve-dependencies-memoized*))

(defn resolve-dependency-jars
  [sym-str version]
  (->> {:dependencies [[(symbol sym-str) version]]
        :repositories (default-repositories)}
    resolve-dependencies (map (comp io/file :jar)) (into-array java.io.File)))
