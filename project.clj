(defproject beats-clj "0.8.0"
  :description "A Clojure library to interact with the Beats Music API."
  :url "https://github.com/mikeflynn/beats-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [cheshire "5.3.1"]
                 [http-kit "2.1.16"]]
  :plugins [[codox "0.8.8"]]
  :codox {:src-dir-uri "https://github.com/mikeflynn/beats-clj/tree/master"})
