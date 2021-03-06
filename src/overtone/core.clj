(ns overtone.core
  (:use overtone.ns)
  (:require clojure.stacktrace
            midi osc byte-spec
            (overtone config time-utils log)
            (overtone.sc core ugen synth synthdef envelope sample)
            (overtone.music rhythm pitch tuning)
            (overtone.studio core fx)
            (overtone.console viz)))

(immigrate
  'clojure.stacktrace
  'osc
  'midi
  'overtone.time-utils
  'overtone.util
  'overtone.event
  'overtone.sc.core
  'overtone.sc.ugen
  'overtone.sc.synth
  'overtone.sc.sample
  'overtone.sc.synthdef
  'overtone.sc.envelope
  'overtone.music.rhythm
  'overtone.music.pitch
  'overtone.music.tuning
  'overtone.studio.core
  'overtone.studio.fx
  )
