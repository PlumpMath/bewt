(ns boot.pod
  (:require
   [clojure.java.io            :as io]
   [dynapath.util              :as dp]
   [dynapath.dynamic-classpath :as cp])
  (:import
   [java.net     URL URLClassLoader]
   [clojure.lang DynamicClassLoader])
  (:refer-clojure :exclude [add-classpath]))

(defn seal-app-classloader
  "see https://github.com/clojure-emacs/cider-nrepl#with-immutant"
  []
  (extend sun.misc.Launcher$AppClassLoader
    cp/DynamicClasspath
    (assoc cp/base-readable-addable-classpath
      :classpath-urls #(seq (.getURLs %))
      :can-add? (constantly false))))

(defn classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip] (->> tip (iterate #(.getParent %)) (take-while boolean))))

(defn modifiable-classloader?
  "Returns true iff the given ClassLoader is of a type that satisfies
   the dynapath.dynamic-classpath/DynamicClasspath protocol, and it can
   be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
     (if-not (dp/add-classpath-url classloader (.toURL (.toURI (io/file jar-or-dir))))
       (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                            classloaders)))))))

(defn get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   (System/getProperty \"java.class.path\") as a seq of URL strings.

   Produces the classpath from all classloaders by default, or from a
   collection of classloaders if provided.  This allows you to easily look
   at subsets of the current classloader hierarchy, e.g.:

   (get-classpath (drop 2 (classloader-hierarchy)))"
  ([classloaders]
    (->> (reverse classloaders)
      (mapcat #(dp/classpath-urls %))
      (map str)))
  ([] (get-classpath (classloader-hierarchy))))

(defn classloader-resources
  "Returns a sequence of [classloader url-seq] pairs representing all
   of the resources of the specified name on the classpath of each
   classloader. If no classloaders are given, uses the
   classloader-heirarchy, in which case the order of pairs will be
   such that the first url mentioned will in most circumstances match
   what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (for [classloader (reverse classloaders)]
       [classloader (enumeration-seq
                      (.getResources ^ClassLoader classloader resource-name))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn resources
  "Returns a sequence of URLs representing all of the resources of the
   specified name on the effective classpath. This can be useful for
   finding name collisions among items on the classpath. In most
   circumstances, the first of the returned sequence will be the same
   as what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (distinct (mapcat second (classloader-resources classloaders resource-name))))
  ([resource-name] (resources (classloader-hierarchy) resource-name)))

(defn copy-resource
  [resource-path out-path]
  (with-open [in  (io/input-stream (io/resource resource-path))
              out (io/output-stream (io/file out-path))]
    (io/copy in out)))

(def lein-project
  (memoize
    (fn [sym]
      (let [parse #(read-string (str "(" (slurp %) "\n)"))
            sym?  #(and (seq? %) (= 'defproject (first %)) (= sym (second %)))
            ->map #(apply hash-map (drop 1 %))]
        (->> "project.clj" resources (map parse) (mapcat #(filter sym? %)) first ->map)))))

(defn entries
  [jar]
  (->> (enumeration-seq (.entries jar))
    (reduce #(assoc %1 (.getName %) %) {})))

(defn eval-in
  ([expr] (pr-str (eval (read-string expr))))
  ([pod expr] (read-string (.invoke pod "boot.pod/eval-in" (pr-str expr)))))

(defn resolve-dependencies
  [env]
  (eval-in (boot.App/getAether)
    `(do (require '~'boot.aether)
         (boot.aether/resolve-dependencies '~env))))

(defn resolve-dependency-jars
  [env]
  (->> env resolve-dependencies (map (comp io/file :jar))))

(defn add-dependencies
  [env]
  (doseq [jar (resolve-dependency-jars env)] (add-classpath jar)))

(defn make-pod
  ([] (boot.App/newPod))
  ([{:keys [src-paths] :as env}]
     (let [dirs (map io/file src-paths)
           jars (resolve-dependency-jars env)]
       (->> (concat dirs jars) (into-array java.io.File) (boot.App/newPod)))))
