(ns boot.core
  (:require
   [boot.pod :as pod]
   )
  )

(defn set-offline! [x]
  (pod/eval-in (boot.App/getAether)
    `(do (require '~'boot.aether)
         (reset! boot.aether/offline? ~x))))

(defn set-update! [x]
  (pod/eval-in (boot.App/getAether)
    `(do (require '~'boot.aether)
         (reset! boot.aether/update? ~x))))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
