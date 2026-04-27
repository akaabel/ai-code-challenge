(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(def class-dir "target/classes")
(def uber-file "target/app.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn uber [_]
  (println "Cleaning" class-dir)
  (b/delete {:path class-dir})
  (println "Copying src + resources")
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (when (.exists (io/file "target/resources"))
    (println "Copying pre-generated assets")
    (b/copy-dir {:src-dirs ["target/resources"] :target-dir class-dir}))
  (println "Compiling Clojure sources")
  (b/compile-clj {:basis @basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (println "Building uberjar:" uber-file)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main 'com.robotfund})
  (println "Done."))
