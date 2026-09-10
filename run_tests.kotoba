#!/usr/bin/env nbb
;; run_tests.cljs — yard-ops の面契約検査。
;;
;;   nbb --classpath test run_tests.cljs
;;
;; yard-ops は state-less な edge BFF で、実際の計算は AgentGateway MCP と
;; pod 側の LangServer に居る。この repo の実体は『複数の面が同じ actor・同じ
;; lexicon・同じ能力表・同じ配備先について同じことを言っている』という合意
;; なので、依存ゼロの nbb + cljs.test でそれを毎回確かめる。
;;
;; 依存を持たないのは意図である。この repo に `node_modules` は 1 つも無く
;; （実測 2026-08-26）、`npm run typecheck` は tsc が解決できずに落ちる。
;; **走れない検査は、走って問題が無かった検査と同じ顔をする**ので、
;; 走る検査を別に置く。

(ns run-tests
  (:require [clojure.test :as t]
            [yard-ops.contract-test]))

(def green-marker "yard-ops contract: all green")

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (if (t/successful? m)
    (println (str "\n" green-marker))
    (do (println "\nyard-ops contract: FAILED")
        (js/process.exit 1))))

(t/run-tests 'yard-ops.contract-test)
