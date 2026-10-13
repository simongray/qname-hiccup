(ns ^:no-doc dk.simongray.qname-hiccup.serializer
  "Canonical Hiccup written as XML 1.0, into one buffer of text that's read
  once at the end."
  (:require [clojure.string :as str])
  #?(:cljs (:import [goog.string StringBuffer])))

(def illegal-characters
  "The characters that section 2.2 of XML 1.0 forbids anywhere, as a
  pattern."
  #"[\x00-\x08\x0B\x0C\x0E-\x1F\uFFFE\uFFFF]")

(defn legal?
  "Whether the string `s` has no character that XML 1.0 forbids."
  [^String s]
  ;; a plain loop over the characters, since a regular expression crawls
  ;; the megabytes of text in a big document thirty times slower, and the
  ;; common case first: a character from the space up passes on one test
  (let [n (count s)]
    (loop [i 0]
      (if (== i n)
        true
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (if (< c 32)
                (or (== c 9) (== c 10) (== c 13))
                (< c 0xFFFE))
            (recur (inc i))
            false))))))

(defn- legal
  "The string `s` without the characters that XML 1.0 forbids."
  [s]
  (if (legal? s)
    s
    (str/replace s illegal-characters "")))

;; XML 1.0, section 2.11: a carriage return is a reference, the only form
;; of it that a parser doesn't normalize away. Section 3.3.3: a parser
;; turns a newline or a tab in an attribute value into a space, unless
;; it's a reference.
(defn- escape
  "What the character code `c` is written as, in an attribute value when
  `attr?` and else in text: a reference, nothing when XML 1.0 forbids it,
  or nil when it's written as it is."
  [^long c attr?]
  (cond
    (>= c 63) (when (>= c 0xFFFE) "")
    (== c 38) "&amp;"
    (== c 60) "&lt;"
    (== c 62) "&gt;"
    (== c 13) "&#13;"
    (== c 34) (when attr? "&quot;")
    (== c 10) (when attr? "&#10;")
    (== c 9)  (when attr? "&#9;")
    (< c 32)  ""))

(defn qname
  "The XML name of the keyword `k`, e.g. dc:creator for :dc/creator."
  [k]
  (if-let [ns (namespace k)]
    (str ns ":" (name k))
    (name k)))

(defn- buffer
  "An empty buffer of text."
  []
  #?(:clj (StringBuilder.) :cljs (StringBuffer.)))

(defn- add
  "The buffer `buf` with the string `s` added at the end."
  [buf s]
  #?(:clj  (.append ^StringBuilder buf ^String s)
     :cljs (.append buf s)))

(defn- add-slice
  "The buffer `buf` with the characters of the string `s` from `start` up
  to `end` added at the end."
  [buf ^String s start end]
  #?(:clj  (.append ^StringBuilder buf s (int start) (int end))
     :cljs (.append buf (.substring s start end))))

(defn- add-escaped
  "The buffer `buf` with the string `s` added, escaped for the `context` of
  :text or :attr, in one pass that copies the runs between escapes."
  [buf ^String s context]
  (let [attr? (= :attr context)
        n     (count s)]
    (loop [buf buf start 0 i 0]
      (if (== i n)
        (add-slice buf s start n)
        (if-let [ref (escape #?(:clj  (int (.charAt s i))
                                :cljs (.charCodeAt s i))
                             attr?)]
          (recur (-> buf (add-slice s start i) (add ref)) (inc i) (inc i))
          (recur buf start (inc i)))))))

(defn- add-text
  "The buffer `buf` with the text `s` added, as CDATA when it holds markup,
  or else escaped. A carriage return or ]]> rules out CDATA."
  [buf s]
  (if (str/includes? s "<")
    (let [s (legal s)]
      (if (or (str/includes? s "]]>")
              (str/includes? s "\r"))
        (add-escaped buf s :text)
        (-> buf (add "<![CDATA[") (add s) (add "]]>"))))
    (add-escaped buf s :text)))

(defn- add-inner-text
  "The buffer `buf` with the text `s` added, escaped, and never as CDATA,
  which HTML doesn't read."
  [buf s]
  (add-escaped buf s :text))

(defn- add-start-tag
  "The buffer `buf` with the start tag of `element` added, unclosed, under
  its `name`."
  [buf element name]
  (reduce-kv (fn [buf k v]
               (-> buf
                   (add " ")
                   (add (qname k))
                   (add "=\"")
                   (add-escaped (str v) :attr)
                   (add "\"")))
             (-> buf (add "<") (add name))
             (nth element 1)))

(defn- add-end-tag
  "The buffer `buf` with the end tag of `name` added."
  [buf name]
  (-> buf (add "</") (add name) (add ">")))

(defn- content
  "What the children of `element` are: :none, :text, :elements or :mixed."
  [element]
  (let [n (count element)]
    (loop [i 2 text? false element? false]
      (if (< i n)
        (if (string? (nth element i))
          (recur (inc i) true element?)
          (recur (inc i) text? true))
        (cond
          (and text? element?) :mixed
          text?                :text
          element?             :elements
          :else                :none)))))

(defn- add-compact
  "The buffer `buf` with `element` added on one line, its text by
  `add-fn`, a function of the buffer and a string."
  [buf element add-fn]
  (let [name (qname (nth element 0))
        buf  (add-start-tag buf element name)]
    (if (== 2 (count element))
      (add buf "/>")
      (-> (reduce (fn [buf child]
                    (if (string? child)
                      (add-fn buf child)
                      (add-compact buf child add-fn)))
                  (add buf ">")
                  (subvec element 2))
          (add-end-tag name)))))

(defn- indents
  "The indentation of the first levels of elements, by `indent` a level."
  [indent]
  (into [] (take 32) (iterate #(str % indent) "")))

(defn- indent-at
  "The indentation at `depth` among the `indents`, or deeper down."
  [indents depth]
  (or (get indents depth)
      (str/join (repeat depth (nth indents 1)))))

(defn- add-element
  "The buffer `buf` with `element` added at `depth`, indented by the
  `indents`, but on one line when its content is mixed."
  [buf indents depth element]
  (let [indent  (indent-at indents depth)
        content (content element)]
    (if (= :mixed content)
      ;; indentation would join the text of mixed content, and grow it on
      ;; every round trip
      (add-compact (add buf indent) element add-text)
      (let [name (qname (nth element 0))
            buf  (add-start-tag (add buf indent) element name)]
        (case content
          :none
          (add buf "/>")

          :text
          (-> buf
              (add ">")
              (add-text (if (== 3 (count element))
                          (nth element 2)
                          (str/join (subvec element 2))))
              (add-end-tag name))

          :elements
          (-> (reduce (fn [buf child]
                        (-> buf
                            (add-element indents (inc depth) child)
                            (add "\n")))
                      (add buf ">\n")
                      (subvec element 2))
              (add indent)
              (add-end-tag name)))))))

(defn- namespaces-in
  "The set of the keyword namespaces, as strings, that the tags and
  attributes in `tree` use, but xml."
  [tree]
  ;; one walk, which makes no lazy sequence
  (letfn [(with-ns [acc k]
            (if-let [ns (namespace k)]
              (conj! acc ns)
              acc))
          (walk [acc el]
            (let [acc (with-ns acc (nth el 0))
                  acc (reduce-kv (fn [acc k _] (with-ns acc k)) acc (nth el 1))
                  n   (count el)]
              (loop [i 2 acc acc]
                (if (< i n)
                  (let [child (nth el i)]
                    (recur (inc i) (if (vector? child) (walk acc child) acc)))
                  acc))))]
    (disj (persistent! (walk (transient #{}) tree)) "xml")))

;; Namespaces in XML 1.0, section 3: a prefix is an NCName, which has no
;; colon, and a namespace name is a URI, which has one after its scheme
(defn- namespace-uri?
  "Whether the keyword namespace `ns` is a namespace URI rather than an
  alias."
  [ns]
  (str/includes? ns ":"))

(defn- prefixes
  "A prefix for each namespace URI among the keyword namespaces `used`, by
  URI: ns1, ns2 and so on, in the order of the URIs, but none of those
  `taken`."
  [used taken]
  (let [uris (sort (filter namespace-uri? used))]
    (zipmap uris (remove #(contains? taken %) (map #(str "ns" %) (iterate inc 1))))))

(defn- renamed
  "The canonical `element` with each name in a namespace that `prefix-of`
  has under that prefix instead."
  [prefix-of element]
  (let [rename #(if-let [prefix (some-> (namespace %) prefix-of)]
                  (keyword prefix (name %))
                  %)]
    (into [(rename (nth element 0)) (update-keys (nth element 1) rename)]
          (map #(if (string? %) % (renamed prefix-of %)))
          (subvec element 2))))

(defn- without
  "The Hiccup `element` without the elements and attributes in the
  namespaces `aliases` names, or nil when the element itself is in one."
  [aliases element]
  (let [in? #(contains? aliases (some-> (namespace %) keyword))]
    (when-not (in? (nth element 0))
      (into [(nth element 0)
             (into {} (remove (comp in? key)) (nth element 1))]
            (keep #(if (string? %) % (without aliases %)))
            (subvec element 2)))))

(declare normalized)

(defn- children-of
  "The `nodes` as the children of an element: strings and elements, with a
  sequence spliced in, nil left out, and anything else as its text, as
  Hiccup renderers read them."
  [nodes]
  (mapcat (fn [x]
            (cond
              (string? x) [x]
              (vector? x) [(normalized x)]
              (seq? x)    (children-of x)
              (nil? x)    []
              :else       [(str x)]))
          nodes))

(defn ^:no-doc normalized
  "The Hiccup `element` with an attribute map and its children as strings
  and elements, as children-of reads them, e.g. [:title {} \"Emma\"] for
  [:title \"Emma\"]. An element that has them already stays the same
  value."
  [element]
  (let [attrs? (map? (nth element 1 nil))
        start  (if attrs? 2 1)
        n      (count element)
        plain? (loop [i start]
                 (or (== i n)
                     (let [x (nth element i)]
                       (and (or (string? x) (vector? x))
                            (recur (inc i))))))]
    (if (and attrs? plain?)
      ;; the common case, a canonical tree, is kept rather than copied
      (reduce (fn [el i]
                (let [x (nth el i)]
                  (if (vector? x)
                    (let [y (normalized x)]
                      (if (identical? x y) el (assoc el i y)))
                    el)))
              element
              (range 2 n))
      (into [(nth element 0) (if attrs? (nth element 1) {})]
            (children-of (subvec element start))))))

;; Namespaces in XML 1.0, section 6.2: an empty xmlns undeclares the
;; default namespace
(defn- defaulted
  "The canonical `element` with the tags in the namespace of `alias`
  unprefixed, in the default namespace `uri`, where `current` is the
  default namespace of its parent as written, and `plain` that of its
  plain tags as the tree has it, \"\" for none.

  An element that needs another default than `current` declares it as
  :xmlns. In the tree, a plain tag is in the default namespace that the
  :xmlns of its nearest ancestor names, if any."
  [alias uri current plain element]
  (let [tag      (nth element 0)
        attrs    (nth element 1)
        explicit (get attrs :xmlns)
        plain    (or explicit plain)
        ns       (namespace tag)
        wanted   (cond
                   (= alias ns) uri
                   (nil? ns)    plain)
        attrs    (if (and wanted (not= wanted (or explicit current)))
                   (assoc attrs :xmlns wanted)
                   attrs)
        current  (get attrs :xmlns current)]
    (into [(if (= alias ns) (keyword (name tag)) tag) attrs]
          (map #(if (string? %) % (defaulted alias uri current plain %)))
          (subvec element 2))))

(defn emit
  "The Hiccup `tree` as an XML string, with the namespaces that it uses
  declared on the root element by `alias->uri`, each level of elements
  indented by `indent`, or all on one line when it's false, and those in
  the namespace of `default-alias` in the default namespace, if it isn't
  nil. The tree is canonical, or Hiccup as normalized reads it. A
  keyword namespace that's a URI gets a prefix of its own."
  [tree alias->uri indent default-alias]
  (let [tree      (normalized tree)
        dropped   (when (map? alias->uri)
                    (into #{} (keep (fn [[alias uri]] (when (nil? uri) alias)))
                          alias->uri))
        tree      (if (seq dropped)
                    (or (without dropped tree) tree)
                    tree)
        uri-of    (fn [alias]
                    (or (get alias->uri alias)
                        (throw (ex-info "Unknown namespace alias"
                                        {:alias alias}))))
        tree      (if (and default-alias (not (contains? dropped default-alias)))
                    (defaulted (name default-alias) (uri-of default-alias) "" ""
                               tree)
                    tree)
        used      (namespaces-in tree)
        prefix-of (prefixes used (into used (map name) (keys alias->uri)))
        decls     (into (sorted-map)
                        (map (fn [ns]
                               (if-let [prefix (prefix-of ns)]
                                 [(keyword "xmlns" prefix) ns]
                                 [(keyword "xmlns" ns) (uri-of (keyword ns))])))
                        used)
        root      (update (cond->> tree (seq prefix-of) (renamed prefix-of))
                          1 #(into decls %))]
    (str (if (false? indent)
           (-> (buffer)
               (add "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
               (add-compact root add-text))
           (-> (buffer)
               (add "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
               (add-element (indents indent) 0 root)
               (add "\n"))))))

(defn inner-xml
  "The content of `element` as markup on one line, with its text escaped."
  [element]
  (str (reduce (fn [buf child]
                 (if (string? child)
                   (add-inner-text buf child)
                   (add-compact buf child add-inner-text)))
               (buffer)
               (subvec (normalized element) 2))))
