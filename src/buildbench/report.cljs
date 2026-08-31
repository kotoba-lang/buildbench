(ns buildbench.report
  "Turn samples into a report that says what it does and does not establish.

  Two separate qualifications are recorded, because they answer two different
  questions and conflating them is how a benchmark becomes folklore:

  - **Absolute times** are qualified by a quiet-host gate. If the host was
    busy, the milliseconds are not portable to anyone else's machine, and
    saying so is the whole point.

  - **The ordering between two lanes** is qualified by `perfgate`, which
    refuses a ranking whose gap is inside the arms' own spread. Because the
    lanes are interleaved on one host, a gap that survives that test survives
    the host being busy — the load fell on both arms.

  A run can therefore honestly rank lanes against each other while refusing
  to publish its own milliseconds as a portable number, and the report says
  which of the two it is doing."
  (:require [clojure.string :as str]
            [machine.core :as m]
            [perfgate.core :as g]))

(def quiet-load1-limit 1.0)

(defn- observation [machine lane-id scale samples source]
  (g/observation {:id (keyword (str (name lane-id) "-k" scale))
                  :machine machine
                  :metric :build-wall-time-ms
                  :unit :ms
                  :samples samples
                  :source source
                  :lower-is-better? true}))

(defn- pairwise
  "Rank every other measured lane against the Kotoba lane at this scale."
  [machine scale lane-results baseline-id source policy]
  (let [measured (into {} (for [[id r] lane-results
                                :when (and (= "measured" (:status r)) (seq (:samplesMilliseconds r)))]
                            [id r]))]
    (when-let [base (get measured baseline-id)]
      (let [base-obs (observation machine baseline-id scale (:samplesMilliseconds base) source)]
        (into {}
              (for [[id r] measured :when (not= id baseline-id)]
                (let [cand (observation machine id scale (:samplesMilliseconds r) source)
                      ;; the question is "is the Kotoba lane faster than this
                      ;; one", so the Kotoba lane is the candidate.
                      verdict (g/qualify base-obs cand policy)]
                  [id {:comparedAgainst (name id)
                       :kotobaQualifiedFaster (:qualified? verdict)
                       :improvement (:improvement verdict)
                       :separation (:separation verdict)
                       :reasons (mapv #(update % :reason name) (:reasons verdict))}])))))))

(defn build
  [{:keys [scales environment machine harness-source baseline-id policy generated-at
           lane-metadata]}]
  (let [loads (mapcat (juxt :load1Before :load1After) scales)
        quiet? (and (seq loads) (every? #(<= % quiet-load1-limit) loads))]
    {:format "kotoba.buildbench/v1"
     :generatedAt generated-at
     :question
     (str "How does process-cold build wall time grow with source size, for the "
          "same program expressed in each toolchain's own language?")
     :notEstablished
     ["Runtime speed of the emitted code — this measures building, not running."
      "A portable millisecond figure, unless absoluteTimes.qualified is true."
      "That any two lanes emit comparable artifacts: targets, ABIs, optimisation levels and runtime contracts differ."
      "Anything about a lane reported as unavailable, invalid or not-run."]
     :environment environment
     :machine machine
     :machineProvenance (:machine/provenance machine)
     :lanes lane-metadata
     :method
     {:workload "K independent 4-operation leaf functions plus one accumulator entry point; every leaf is live"
      :interleaving "lanes rotate their starting position every round"
      :validation "artifacts are executed or structurally checked after the clock stops"
      :warmupsPerLane 1
      :harness harness-source}
     :absoluteTimes
     {:qualified quiet?
      :quietLoad1Limit quiet-load1-limit
      :observedLoad1 (vec (map #(js/Number (.toFixed % 2)) loads))
      :explanation
      (if quiet?
        "The host stayed under the quiet-load gate, so these milliseconds may be read as absolute figures for this machine."
        (str "The host never became quiet enough for the milliseconds to be portable. "
             "They remain valid observations of this run, and the interleaved "
             "pairwise ordering below is qualified separately."))}
     :orderingPolicy policy
     :scales
     (vec (for [s scales]
            (assoc s :ordering
                   (pairwise machine (:k s) (:lanes s) baseline-id harness-source policy))))}))

(defn summarize-line [scale]
  (str "K=" (:k scale) "  "
       (str/join "  "
                 (for [[id r] (sort-by key (:lanes scale))]
                   (str (name id) "="
                        (case (:status r)
                          "measured" (str (js/Number
                                           (.toFixed (:median (g/summarize (:samplesMilliseconds r))) 1))
                                          "ms/n" (:n r))
                          (:status r)))))))
