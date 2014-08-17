(ns boot.task.built-in
  (:require
   [clojure.java.io    :as io]
   [clojure.set        :as set]
   [clojure.pprint     :as pprint]
   [clojure.string     :as string]
   [clojure.stacktrace :as trace]
   [boot.core          :as core]
   [boot.main          :as main]
   [boot.conch         :as conch]
   [boot.repl          :as repl]
   [boot.file          :as file]
   [boot.table.core    :as table]))

(defn- first-line [s] (when s (first (string/split s #"\n"))))
(defn- not-blank? [s] (when-not (string/blank? s) s))
(defn- now        [ ] (System/currentTimeMillis))
(defn- ms->s      [x] (double (/ x 1000)))

(defmacro ^:private print-time [ok fail expr]
  `(let [start# (now)]
     (try
       (let [end# (do ~expr (ms->s (- (now) start#)))]
         (printf ~ok end#))
       (catch Throwable e#
         (let [time#  (ms->s (- (now) start#))
               trace# (with-out-str (trace/print-cause-trace e#))]
           (println (format ~fail trace# time#)))))))

(defn- tasks-table [tasks]
  (let [get-task  #(-> % :name str)
        get-desc  #(-> % :doc first-line)]
    (->> tasks (map (fn [x] ["" (get-task x) (get-desc x)])))))

(defn- set-title [[[_ & xs] & zs] title] (into [(into [title] xs)] zs))

(defn- version-str []
  (format "Boot Version:  %s\nDocumentation: %s"
    (core/get-env :boot-version) "http://github.com/tailrecursion/boot"))

(defn- available-tasks [sym]
  (let [base  {nil (the-ns sym)}
        task? #(:boot.core/task %)
        addrf #(if-not (seq %1) %2 (symbol %1 (str %2)))
        proc  (fn [a [k v]] (assoc (meta v) :name (addrf a k) :var v))
        pubs  (fn [[k v]] (map proc (repeat (str k)) (ns-publics v)))]
    (->>
      (concat
        (->> sym ns-refers (map proc (repeat nil)))
        (->> sym ns-aliases (into base) (mapcat pubs)))
      (filter task?) (sort-by :name))))

(def ^:private ^:dynamic *sh-dir* nil)

(defn- sh [& args]
  (let [opts (into [:redirect-err true] (when *sh-dir* [:dir *sh-dir*]))
        proc (apply conch/proc (concat args opts))]
    (future (conch/stream-to-out proc :out))
    #(.waitFor (:process proc))))

(defn- pp-str [form]
  (with-out-str (pprint/pprint form)))

(defn- ns-requires [sym]
  (let [ns   (the-ns sym)
        core (the-ns 'clojure.core)
        sift #(remove (partial contains? #{nil ns core}) (set %))]
    (-> (->> sym ns-aliases vals)
      (concat (->> sym ns-map vals (map (comp :ns meta))))
      sift)))

;; ## Core Tasks
;;
;; These tasks are included in boot core and are listed by the `help` task.

(core/deftask debug
  "Print the value of a boot environment key.

  Multiple `keys` specify a path, similar to `#'clojure.core/get-in`."
  [& keys]
  (core/with-pre-wrap
    (let [e (core/get-env)]
      (print (pp-str (if-not (seq keys) e (get-in e keys)))))))

(core/deftask help
  "Print help and usage info for a task.
  
  Help and usage info is derived from metadata on the task var. The task must be
  required into the boot script namespace for this medatada to be found."
  ([]
     (core/with-pre-wrap
       (let [tasks (available-tasks 'boot.user)
             opts  (->> main/cli-opts (mapv (fn [[x y z]] ["" (str x " " y) z])))]
         (printf "%s\n\n" (version-str))
         (printf "%s\n"
           (-> [[""       ""]
                ["Usage:" "boot OPTS task ..."]
                [""       "boot OPTS [task arg arg] ..."]
                [""       "boot OPTS [help task]"]]
             (table/table :style :none)
             with-out-str))
         (printf "%s\n"
           (-> [["" "" ""]]
             (into (set-title (conj opts ["" "" ""]) "OPTS:"))
             (into (set-title (tasks-table tasks) "Tasks:"))
             (table/table :style :none)
             with-out-str)))))
  ([task]
     (core/with-pre-wrap
       (let [task* (->> (available-tasks 'boot.user)
                     (map :var) (filter #(= task (var-get %))) first)
             {args :arglists doc :doc task :name} (meta task*)]
         (assert task* "Not a valid task")
         (printf "%s\n\n" (version-str))
         (printf "%s\n%s\n  %s\n\n" task args doc)))))

(defn- generate-lein-project-file!
  [& {:keys [keep-project] :or {:keep-project true}}]
  (let [pfile (io/file "project.clj")
        pname (or (core/get-env :project) 'boot-project)
        pvers (or (core/get-env :version) "0.1.0-SNAPSHOT")
        prop  #(when-let [x (core/get-env %2)] [%1 x])
        head  (list* 'defproject pname pvers
                     (concat
                      (prop :url :url)
                      (prop :license :license)
                      (prop :description :description)
                      [:dependencies (core/get-env :dependencies)
                       :source-paths (vec (core/get-env :src-paths))]))
        proj  (pp-str (concat head (mapcat identity (core/get-env :lein))))]
    (if-not keep-project (.deleteOnExit pfile))
    (spit pfile proj)))

(core/deftask lein-generate
  "Generate a leiningen `project.clj` file.

  This task generates a leiningen `project.clj` file based on the boot
  environment configuration, including project name and version (generated
  if not present), dependencies, and source paths. Additional keys may be added
  to the generated `project.clj` file by specifying a `:lein` key in the boot
  environment whose value is a map of keys-value pairs to add to `project.clj`."
  []
  (core/with-pre-wrap
    (generate-lein-project-file! :keep-project true)))

(core/deftask lein
  "Run a leiningen task with a generated `project.clj`.

  This task generates a leiningen `project.clj` file based on the boot
  environment configuration, including project name and version (generated
  if not present), dependencies, and source paths. Additional keys may be added
  to the generated `project.clj` file by specifying a `:lein` key in the boot
  environment whose value is a map of keys-value pairs to add to `project.clj`.

  Once the `project.clj` file has been generated, the specified lein task is
  then run. Note that leiningen is run in another process. This task cannot be
  used to run interactive lein tasks (yet) because stdin is not currently piped
  to leiningen."
  [& args]
  (core/with-pre-wrap
    (generate-lein-project-file! :keep-project true)
    ((apply sh "lein" (map str args)))))

(def ^:private bgs
  "List of tasks running in other threads that will need to be cleaned up before
  boot can exit."
  (atom ()))

;; cleanup background tasks on shutdown
(-> (Runtime/getRuntime)
  (.addShutdownHook (Thread. #(doseq [job @bgs] (future-cancel job)))))

(core/deftask once
  "Evaluate the given `task` only once.

  Subsequent evaluations will pass through."
  [task]
  (let [ran? (atom false)
        run? (partial compare-and-set! ran? @ran?)]
    (fn [continue]
      (let [task (task continue)]
        #(continue ((if (run? true) task identity) %))))))

(core/deftask wait
  "Wait for msec milliseconds before calling its continuation."
  [msec]
  (core/with-pre-wrap (Thread/sleep msec)))

(core/deftask bg
  "Run the given `task` once, in a separate, background thread."
  [task]
  (once
    (core/with-pre-wrap
      (swap! bgs conj (future ((task identity) core/*event*))))))

(defn auto
  "Run every `msec` (default 200) milliseconds."
  [& [msec]]
  (fn [continue]
    (fn [event]
      (continue event)
      (Thread/sleep (or msec 200))
      (recur (core/make-event event)))))

(defn files-changed?
  [& [type quiet?]]
  (let [dirs      (remove core/tmpfile? (core/get-env :src-paths)) 
        watchers  (map file/make-watcher dirs)
        since     (atom 0)]
    (fn [continue]
      (fn [event]
        (let [clean #(assoc %2 %1 (set (remove core/ignored? (get %2 %1))))
              info  (->> (map #(%) watchers)
                      (reduce (partial merge-with set/union))
                      (clean :time)
                      (clean :hash))]
          (if-let [mods (->> (or type :time) (get info) seq)]
            (do
              (let [path   (file/path (first mods))
                    ok-v   "\033[34m↳ Elapsed time: %6.3f sec ›\033[33m 00:00:00 \033[0m"
                    ok-q   "Elapsed time: %6.3f sec\n"
                    fail-v "\n\033[31m%s\033[0m\n\033[34m↳ Elapsed time: %6.3f sec ›\033[33m 00:00:00 \033[0m"
                    fail-q "\n%s\nElapsed time: %6.3f sec\n"
                    ok     (if quiet? ok-q ok-v)
                    fail   (if quiet? fail-q fail-v)]
                (when (not= 0 @since) (println)) 
                (reset! since (:time event))
                (print-time ok fail (continue (assoc event :watch info)))))
            (let [diff  (long (/ (- (:time event) @since) 1000))
                  pad   (apply str (repeat 9 "\b"))
                  s     (mod diff 60)
                  m     (mod (long (/ diff 60)) 60)
                  h     (mod (long (/ diff 3600)) 24)]
              (core/sync!)
              (when-not quiet? (printf "\033[33m%s%02d:%02d:%02d \033[0m" pad h m s)))))))))

(core/deftask watch
  "Watch `:src-paths` and call its continuation when files change.

  Options:

    :type    Specifies how changes to files are detected and can be either 
             :time or :hash (default :time).
    :msec    Specifies the polling interval in milliseconds (default 200).
    :quiet   When set to true no ANSI colors or updating timer are printed. "
  [& {:keys [type msec quiet] :or {type :time msec 200 quiet false}}]
  (comp (auto msec) (files-changed? type quiet)))

(core/deftask syncdir
  "Copy/sync files between directories.

  The files in `from-dirs` will be overlayed on the `to-dir` directory. Empty 
  directories are ignored."
  [to-dir & from-dirs]
  (core/add-sync! to-dir from-dirs)
  identity)

(core/deftask repl
  "Start a repl session for the current project.

   Subcommands:

   <none> | [:host \"127.0.0.1\"] [:port random] [:middlewares []] [:init-ns 'user]

   :headless [:host host] [:port port] [:middlewares []]
     This will launch an nREPL server and wait, rather than connecting
     a client to it.

   :pass-through [:host host] [:port port] [:middlewares []]
     Like :headless, but does not block the task chain.

   :connect [dest]
     Connects to an already running nREPL server. Dest can be:
     - host:port -- connects to the specified host and port;
     - port -- host defaults to localhost

  If no dest is given, resolves the host as described above
  and the port from .nrepl-port in the project root."
  [& [cmd & opts :as args]]
  (let [argv (apply vector args)]
    (repl/load-dynamic-dependencies argv)
    (core/with-pre-wrap
      (repl/exec argv))))
