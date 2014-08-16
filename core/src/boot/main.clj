(ns boot.main
  (:require
   [boot.cli           :as cli]
   [boot.pod           :as pod]
   [boot.core          :as core]
   [boot.util          :as util]
   [clojure.java.io    :as io]
   [clojure.stacktrace :as trace]
   [clojure.string     :as string]
   )
  )

(def boot-version (str (boot.App/getVersion) "-" (boot.App/getRelease)))

(defn read-cli [argv]
  (let [src (str "(" (string/join " " argv) "\n)")]
    (util/with-rethrow
      (read-string src)
      (format "Can't read command line as EDN: %s" src))))

(defn parse-cli [argv]
  (try (let [dfltsk `(((-> :default-task core/get-env resolve)))
             ->expr #(cond (seq? %) % (vector? %) (list* %) :else (list %))]
         (or (seq (map ->expr (or (seq (read-cli argv)) dfltsk))) dfltsk))
    (catch Throwable e (with-out-str (trace/print-cause-trace e)))))

(def ^:private cli-opts
  [["-f" "--freshen"    "Force snapshot dependency updates."]
   ["-F" "--no-freshen" "Don't update snapshot dependencies."]
   ["-h" "--help"       "Print basic usage and help info."]
   ["-o" "--offline"    "Don't check network for dependencies."]
   ["-P" "--no-profile" "Skip loading of profile.boot script."]
   ["-s" "--script"     "Print generated boot script for debugging."]
   ["-V" "--version"    "Print boot version info."]])

(defn- parse-cli-opts [args]
  ((juxt :errors :options :arguments)
   (cli/parse-opts args cli-opts :in-order true)))

(defn emit [boot? argv argv* edn-ex forms]
  `(~'(ns boot.user
        (:require
         [boot.util :refer :all]
         [boot.core :refer :all :exclude [deftask]]))
    (defmacro ~'deftask
      [~'& ~'args]
      (list* '~'deftask* ~'args))
    (comment "profile")
    ~@forms
    (comment "command line")
    ~(if boot?
       (if edn-ex
         `(binding [~'*out* ~'*err*]
            (print ~edn-ex)
            (System/exit 1))
         `(core/boot ~@argv*))
       `(when-let [main# (resolve '~'-main)] (main# ~@argv)))))

(defn -main [[arg0 & args :as args*]]
  (let [dotboot?           #(.endsWith (.getName (io/file %)) ".boot")
        script?            #(when (and % (.isFile (io/file %)) (dotboot? %)) %)
        [errs opts args**] (parse-cli-opts (if (script? arg0) args args*))]
    
    (when (seq errs)
      (util/exit-error
        (println (apply str (interpose "\n" errs)))))

    (when (:freshen    opts) (core/set-update! :always))
    (when (:no-freshen opts) (core/set-update! :never))
    (core/set-offline! (:offline opts))
    
    (binding [*out* (util/auto-flush *out*)
              *err* (util/auto-flush *err*)]
      (util/exit-ok
        (let [[arg0 & args :as args*] (concat (if (script? arg0) [arg0] []) args**)
              bootscript  (io/file "build.boot")
              userscript  (script? (io/file (boot.App/getBootDir) "profile.boot"))
              [arg0 args] (cond
                            (script? arg0)       [arg0 args]
                            (script? bootscript) [bootscript args*]
                            :else                [nil args*])
              boot?       (contains? #{nil bootscript} arg0)
              profile?    (not (:no-profile opts))
              cljarg      (parse-cli args)
              ex          (when (string? cljarg) cljarg)
              args*       (when-not (string? cljarg) cljarg)
              bootforms   (some->> arg0 slurp util/read-string-all)
              userforms   (when profile? (some->> userscript slurp util/read-string-all))
              commented   (concat () userforms [`(comment "script")] bootforms)
              scriptforms (emit boot? args args* ex commented)
              scriptstr   (str (string/join "\n\n" (map util/pp-str scriptforms)) "\n")]
          (when (:script  opts) (util/exit-ok (print scriptstr)))
          (when (:version opts) (util/exit-ok (println boot-version)))
          (prn {:boot-version boot-version
                :boot-options opts
                :default-task 'boot.task.core/help})
          )))))
