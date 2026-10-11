(ns ^:no-doc dk.simongray.qname-hiccup.whitespace
  "White space as the trim of either platform sees it, so that the JVM and
  JavaScript agree on it: the trim of Java keeps the no-break spaces and
  the byte order mark, which that of JavaScript removes."
  (:require [clojure.string :as str]))

(defn space-code?
  "Whether the character code `c` is white space to the trim of either
  platform."
  [^long c]
  ;; a primitive argument, and comparisons of two arguments, which Clojure
  ;; compiles inline, since this runs for every character that it checks
  (or (<= c 32)
      (== c 0xA0)
      (== c 0x1680)
      (and (<= 0x2000 c) (<= c 0x200A))
      (== c 0x2028)
      (== c 0x2029)
      (== c 0x202F)
      (== c 0x205F)
      (== c 0x3000)
      (== c 0xFEFF)))

(defn blank?
  "Whether the string `s` holds nothing but white space."
  [^String s]
  (let [n (count s)]
    (loop [i 0]
      (cond
        (== i n)
        true

        (space-code? #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i)))
        (recur (inc i))

        :else
        false))))

(defn trim
  "The string `s` without white space at either end, no-break spaces
  included."
  [^String s]
  (let [n     (count s)
        start (loop [i 0]
                (if (and (< i n)
                         (space-code? #?(:clj  (int (.charAt s i))
                                         :cljs (.charCodeAt s i))))
                  (recur (inc i))
                  i))
        end   (loop [i n]
                (if (and (> i start)
                         (space-code? #?(:clj  (int (.charAt s (dec i)))
                                         :cljs (.charCodeAt s (dec i)))))
                  (recur (dec i))
                  i))]
    ;; the string itself when there's nothing to trim, uncopied
    (if (and (zero? start) (== end n))
      s
      (subs s start end))))

(defn- kept-run
  "The transient `acc` with the run of text `run` added, unless it's empty,
  or blank without `keep?`. A blank run is kept when it's `between?` two
  elements on one line."
  [acc run between? keep?]
  (if (and run
           (not= "" run)
           (or keep?
               (not (blank? run))
               (and between?
                    (not (str/includes? run "\n"))
                    (not (str/includes? run "\r")))))
    (conj! acc run)
    acc))

(defn- runs
  "The `nodes` with adjacent strings merged, and blank ones dropped unless
  `keep?`, as kept-run says."
  [nodes keep?]
  (loop [nodes (seq nodes) acc (transient []) run nil after-element? false]
    (if nodes
      (let [node (first nodes)]
        (if (string? node)
          (recur (next nodes) acc (if run (str run node) node) after-element?)
          (let [acc (conj! (kept-run acc run after-element? keep?) node)]
            (recur (next nodes) acc nil true))))
      (persistent! (kept-run acc run false keep?)))))

(defn compact-text
  "The `nodes` with adjacent strings merged and blank ones dropped. A space
  between two elements on one line stays, as inline markup needs it."
  [nodes]
  (runs nodes false))

(defn joined-text
  "The `nodes` with adjacent strings merged, white space and all."
  [nodes]
  (runs nodes true))

;; XML 1.0, section 2.10
(defn keep-space?
  "Whether the text of an element with the raw `attrs` keeps its white
  space: by its xml:space, preserve or default, or else as its parent's
  does, `inherited?`. The `default?` is what default stands for."
  [attrs inherited? default?]
  (case (get attrs "xml:space")
    "preserve" true
    "default"  default?
    inherited?))
