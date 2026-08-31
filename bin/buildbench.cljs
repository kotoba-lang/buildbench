#!/usr/bin/env nbb
(ns buildbench.main
  "buildbench — how build time grows with code size, with the checks attached.

  usage:
    nbb --classpath src:<perfgate>/src:<machine>/src bin/buildbench.cljs \\
      --scales 1,32,128,512,2048 --runs 7 --budget-ms 120000 \\
      --amu /path/to/amu --output results/latest.json

  Exit codes are three-valued on purpose: 0 clean, 1 a lane produced an
  invalid artifact, 2 the harness could not answer at all. A run that could
  not measure must not exit like a run that measured and found nothing wrong."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [buildbench.host :as host]
            [buildbench.lanes :as lanes]
            [buildbench.measure :as measure]
            [buildbench.report :as report]
            [perfgate.core :as g]
            [clojure.string :as str]))

(defn- opt [args flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (nth args (inc i) default)
    default))

(defn -main [& args]
  (let [scales (mapv #(js/parseInt % 10) (str/split (opt args "--scales" "1,32,128,512") #","))
        runs (js/parseInt (opt args "--runs" "7") 10)
        budget (js/parseInt (opt args "--budget-ms" "120000") 10)
        amu (opt args "--amu" nil)
        out (opt args "--output" "results/latest.json")
        harness (opt args "--harness-url"
                     "https://github.com/kotoba-lang/buildbench")
        work (fs/mkdtempSync (path/join (os/tmpdir) "buildbench-"))
        all (lanes/lanes {:amu amu})
        available (filterv :available? all)]
    (when (empty? available)
      (js/console.error "buildbench: no toolchain found on this host; refusing to report a pass")
      (js/process.exit 2))
    (println "buildbench: work dir" work)
    (println "buildbench: lanes"
             (str/join ", " (for [l all] (str (name (:id l))
                                              (if (:available? l) "" " (unavailable)")))))
    (let [scale-results
          (vec (for [k scales]
                 (let [r (measure/measure-scale
                          {:k k :runs runs :budget-ms budget :warmups 1
                           :work-root work :lanes all})]
                   (println (report/summarize-line r))
                   r)))
          machine (host/descriptor)
          lane-metadata (into {} (for [l all]
                                   [(:id l)
                                    (cond-> {:label (:label l)
                                             :target (:target l)
                                             :language (name (:language l))
                                             :available (boolean (:available? l))}
                                      (:note l) (assoc :note (:note l))
                                      (:tool l) (assoc :tool (:tool l))
                                      (:version-cmd l)
                                      (assoc :version
                                             (let [[c a] (:version-cmd l)]
                                               (lanes/version-of c a))))]))
          rep (report/build
               {:scales scale-results
                :environment (host/environment)
                :machine machine
                :lane-metadata lane-metadata
                :harness-source harness
                :baseline-id :kotoba-wasm-cli
                ;; perfgate's own default, unrelaxed. A gate loosened to let
                ;; this run's claim through would be this benchmark measuring
                ;; its own thresholds.
                :policy g/default-policy
                :generated-at (.toISOString (js/Date.))})
          invalid (for [s scale-results
                        [id r] (:lanes s)
                        :when (#{"invalid" "failed"} (:status r))]
                    (str "K=" (:k s) " " (name id) ": " (:status r) " — " (:error r)))]
      (fs/mkdirSync (path/dirname out) #js {:recursive true})
      (fs/writeFileSync out (str (js/JSON.stringify (clj->js rep) nil 2) "\n"))
      (println "buildbench: wrote" out)
      (when (seq invalid)
        (println "\nbuildbench: lanes that did not produce the program:")
        (doseq [l invalid] (println "  " l))
        (js/process.exit 1)))))

(apply -main *command-line-args*)
