(ns boot.core)

(defn set-offline! [x]
  (boot.App/setOffline (pr-str x)))

(defn set-update! [x]
  (boot.App/setUpdate (pr-str x)))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))
