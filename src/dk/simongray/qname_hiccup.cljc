(ns dk.simongray.qname-hiccup
  "XML as Hiccup, with namespaces resolved by URI rather than by prefix.

  Raw Hiccup has the names as written. Canonical Hiccup has keywords
  namespaced by the alias that a table gives the URI, e.g. :dc/creator,
  whether the name has a prefix or is in the default namespace. Its
  metadata has what the resolution found under :xml/report, and the
  namespaces that the table lacks under :xml/namespaces."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.io InputStream Reader StringReader]
                   [javax.xml.stream XMLInputFactory XMLStreamConstants
                                     XMLStreamException XMLStreamReader])))

(defn tag
  "The tag of the Hiccup `element`."
  [element]
  (nth element 0))

(defn attrs
  "The attribute map of the Hiccup `element`."
  [element]
  (nth element 1))

(defn children
  "The children of the Hiccup `element`: strings and elements."
  [element]
  (drop 2 element))

(defn text
  "The text directly in the Hiccup `element`, joined."
  [element]
  ;; one string is the common case, and needs no concatenation
  (if (and (vector? element)
           (== 3 (count element))
           (string? (nth element 2)))
    (nth element 2)
    (apply str (filter string? (children element)))))

(defn elements
  "The element children of `element`, or only those tagged `tag`."
  ([element]
   (filter vector? (children element)))
  ([element tag]
   (filter #(= tag (nth % 0)) (elements element))))

(defn element
  "The first child of `element` tagged `tag`."
  [element tag]
  (first (elements element tag)))

(defn deep-text
  "All the text in `element` and its descendants, in document order."
  [element]
  (apply str (filter string? (tree-seq vector? children element))))

(defn- space-code?
  "Whether the character code `c` is white space to the trim of either
  platform."
  [^long c]
  ;; a primitive argument, and comparisons of two arguments, which Clojure
  ;; compiles inline, since this runs for the ends of every value that a
  ;; parse reads
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

(defn trim
  "The string `s` without white space at either end, no-break spaces
  included, the same on the JVM and in JavaScript."
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
    (if (and (zero? start) (== end n))
      s
      (subs s start end))))

(defn- kept-run
  "The transient `acc` with the run of text `run` added, unless it's
  blank. A blank run is kept when it's `between?` two elements on one
  line."
  [acc run between?]
  (if (and run
           (or (not= "" (trim run))
               (and between?
                    (not (re-find #"[\r\n]" run)))))
    (conj! acc run)
    acc))

(defn ^:no-doc compact-text
  "The `nodes` with adjacent strings merged and blank ones dropped. A space
  between two elements on one line stays, as inline markup needs it."
  [nodes]
  (loop [nodes (seq nodes) acc (transient []) run nil after-element? false]
    (if nodes
      (let [node (first nodes)]
        (if (string? node)
          (recur (next nodes) acc (if run (str run node) node) after-element?)
          (let [acc (conj! (kept-run acc run after-element?) node)]
            (recur (next nodes) acc nil true))))
      (persistent! (kept-run acc run false)))))

(defn- strip-bom
  "The text `s` without a leading byte order mark."
  [s]
  (if (str/starts-with? s "\uFEFF")
    (subs s 1)
    s))

(defn- bom-encoding
  "The encoding that the byte order mark of the bytes `b` names, or nil."
  [b]
  (let [n    #?(:clj (alength ^bytes b) :cljs (.-length b))
        at   (fn [i]
               (when (< i n)
                 #?(:clj  (bit-and (aget ^bytes b i) 0xff)
                    :cljs (aget b i))))
        head (map at (range 3))]
    (cond
      (= [0xef 0xbb 0xbf] head)     "UTF-8"
      (= [0xfe 0xff] (take 2 head)) "UTF-16BE"
      (= [0xff 0xfe] (take 2 head)) "UTF-16LE")))

(defn- declared-encoding
  "The encoding that the XML declaration of the bytes `b` names, or nil."
  [b]
  (let [n    (min 200 #?(:clj (alength ^bytes b) :cljs (.-length b)))
        head #?(:clj  (String. ^bytes b 0 (int n) "ISO-8859-1")
                :cljs (.decode (js/TextDecoder. "latin1") (.subarray b 0 n)))]
    (second (re-find #"(?i)<\?xml[^>]*encoding=[\"']([\w.:-]+)" head))))

;; XML 1.0, appendix F; RFC 7303, section 3.2
(defn decode
  "The text of the XML document in the bytes `b`, in the encoding that its
  byte order mark, `charset` or XML declaration names first, or else UTF-8.

  The `charset` is e.g. that of a Content-Type header. An unknown encoding
  counts as UTF-8, and the byte order mark is dropped."
  ([b]
   (decode b nil))
  ([b charset]
   (let [encoding (or (bom-encoding b) charset (declared-encoding b) "UTF-8")]
     (strip-bom
      #?(:clj  (String. ^bytes b
                        (try
                          (java.nio.charset.Charset/forName encoding)
                          (catch Exception _
                            java.nio.charset.StandardCharsets/UTF_8)))
         :cljs (.decode (try
                          (js/TextDecoder. encoding)
                          (catch :default _
                            (js/TextDecoder. "utf-8")))
                        b))))))

(defn ^:no-doc malformed
  "The error for malformed XML, with the parser's `message` and the `cause`
  that carried it, or nil."
  [message cause]
  (ex-info (str "Malformed XML: " message) {:type ::malformed} cause))

;; Far deeper than documents nest, and shallow enough for every walk of the
;; tree to recurse on a thread with a stack of the default size
(def max-depth
  "How deep elements may nest in a document, with the root at 1. A deeper
  document is malformed."
  256)

(defn ^:no-doc check-depth
  "Nil, unless an element at `depth` nests too deep, which throws
  ::malformed."
  [depth]
  (when (> depth max-depth)
    (throw (malformed (str "elements nest deeper than " max-depth " levels")
                      nil))))

#?(:cljs
   (defn- dom-parser
     "A DOMParser of the platform, or a throw of ::no-parser without one."
     []
     (if (exists? js/DOMParser)
       (js/DOMParser.)
       (throw (ex-info (str "No DOMParser to read XML with. In Node, install"
                            " one as globalThis.DOMParser, e.g. that of"
                            " @xmldom/xmldom.")
                       {:type ::no-parser})))))

#?(:clj
   (defn- input-factory
     "A StAX factory without namespace processing, DTDs, external entities
     or the JDK's limits on text size and depth."
     ^XMLInputFactory []
     (let [factory (XMLInputFactory/newInstance)]
       (doto factory
         ;; so that undeclared prefixes survive, and the xmlns declarations
         ;; arrive as plain attributes
         (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
         (.setProperty XMLInputFactory/IS_COALESCING true)
         (.setProperty XMLInputFactory/SUPPORT_DTD false)
         (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
         (.setProperty XMLInputFactory/IS_REPLACING_ENTITY_REFERENCES true))
       ;; Recent JDKs cap a single text node at 100,000 characters, which
       ;; long texts exceed, and JDK 24 caps nesting at 100 levels. With DTDs
       ;; off the entity limits guard nothing, and read-element checks the
       ;; depth itself, the same on every JDK and in JavaScript.
       (doseq [limit ["jdk.xml.maxGeneralEntitySizeLimit"
                      "jdk.xml.totalEntitySizeLimit"
                      "jdk.xml.maxElementDepth"]]
         (try
           (.setProperty factory limit "0")
           (catch Exception _)))
       factory)))

(defn- document
  "The document in `xml`, ready to read: a StAX reader at its start on the
  JVM, and the root element of a DOM in JavaScript.

  On the JVM, `xml` is a string, an InputStream or a Reader, and in
  JavaScript a string."
  [xml]
  #?(:clj
     (let [factory (input-factory)]
       (cond
         (string? xml)
         (.createXMLStreamReader factory
                                 (StringReader. ^String (strip-bom xml)))

         (instance? InputStream xml)
         (.createXMLStreamReader factory ^InputStream xml)

         (instance? Reader xml)
         (.createXMLStreamReader factory ^Reader xml)

         :else
         (throw (ex-info "Cannot parse XML from this value"
                         {:type (type xml)}))))
     :cljs
     (let [parser (dom-parser)
           doc    (try
                    (.parseFromString parser (strip-bom xml) "text/xml")
                    (catch :default e
                      (throw (malformed (or (ex-message e) (str e)) e))))
           errors (.getElementsByTagName doc "parsererror")
           root   (.-documentElement doc)]
       ;; browsers report errors as a parsererror element, and other
       ;; implementations throw or give a document without a root
       (cond
         (pos? (.-length errors))
         (throw (malformed (.-textContent (.item errors 0)) nil))

         (nil? root)
         (throw (malformed "no root element" nil))

         :else
         root))))

(defn- start-tag
  "The name and attributes of the element at `source`, as written, in a
  pair. The namespace declarations are among the attributes.

  On the JVM, `source` is a StAX reader on its start tag, and in JavaScript
  a DOM element."
  [source]
  #?(:clj  (let [^XMLStreamReader r source
                 qname (fn [prefix local]
                         (if (str/blank? prefix) local (str prefix ":" local)))]
             [(qname (.getPrefix r) (.getLocalName r))
              (-> {}
                  (into (for [i (range (.getAttributeCount r))]
                          [(qname (.getAttributePrefix r i)
                                  (.getAttributeLocalName r i))
                           (.getAttributeValue r i)]))
                  (into (for [i (range (.getNamespaceCount r))]
                          [(qname "xmlns" (.getNamespacePrefix r i))
                           (.getNamespaceURI r (int i))])))])
     :cljs (let [attrs (.-attributes source)]
             [(.-nodeName source)
              (into {} (for [i (range (.-length attrs))
                             :let [a (.item attrs i)]]
                         [(.-nodeName a) (.-nodeValue a)]))])))

(declare read-element)

(defn- read-children
  "The raw elements and text in the element at `source`, which nest at
  `depth`. On the JVM, this reads the StAX reader up to the end tag."
  [source depth]
  #?(:clj  (let [^XMLStreamReader r source]
             (loop [acc (transient [])]
               (let [event (.next r)]
                 (cond
                   (= event XMLStreamConstants/START_ELEMENT)
                   (recur (conj! acc (read-element r depth)))

                   (or (= event XMLStreamConstants/CHARACTERS)
                       (= event XMLStreamConstants/CDATA)
                       (= event XMLStreamConstants/SPACE))
                   (recur (conj! acc (.getText r)))

                   (= event XMLStreamConstants/END_ELEMENT)
                   (persistent! acc)

                   :else
                   (recur acc)))))
     :cljs (let [nodes (.-childNodes source)]
             (keep (fn [i]
                     (let [node (.item nodes i)]
                       (case (.-nodeType node)
                         1     (read-element node depth)
                         (3 4) (.-nodeValue node)
                         nil)))
                   (range (.-length nodes))))))

(defn- read-element
  "The raw Hiccup of the element at `source`, which nests at `depth`, 1 for
  the root.

  On the JVM, `source` is a StAX reader on its start tag, and in JavaScript
  a DOM element."
  [source depth]
  (check-depth depth)
  (let [head (start-tag source)]
    ;; the start tag first: on the JVM, reading the children moves past it
    (into head (compact-text (read-children source (inc depth))))))

(defn parse-raw
  "The raw Hiccup of the string `xml`, with names as written and the
  namespace declarations as attributes.

  On the JVM, `xml` can also be a Reader, or an InputStream whose declared
  encoding is honoured. Comments and processing instructions are dropped,
  text and CDATA merged, and text of only white space removed. A document
  that isn't well-formed or nests too deep throws ::malformed."
  [xml]
  #?(:clj
     (try
       (let [^XMLStreamReader r (document xml)]
         (try
           (let [root (loop []
                        (if (= (.next r) XMLStreamConstants/START_ELEMENT)
                          (read-element r 1)
                          (recur)))]
             ;; on to the end, where anything but a comment or white space
             ;; after the root is an error, as it is in a browser
             (while (.hasNext r)
               (.next r))
             root)
           (finally
             (.close r))))
       (catch XMLStreamException e
         (throw (malformed (.getMessage e) e))))
     :cljs
     (read-element (document xml) 1)))

(defn- split-qname
  "The raw XML name `s` as a pair of its prefix, or nil, and its local
  name."
  [s]
  (if-let [i (str/index-of s ":")]
    [(subs s 0 i) (subs s (inc i))]
    [nil s]))

(defn ^:no-doc declarations
  "The namespace declarations among raw `attrs`, as a map from prefix to URI.
  The default namespace, when declared, is under the key nil."
  [attrs]
  (into {} (keep (fn [[k v]]
                   (cond
                     (= k "xmlns") [nil v]
                     (str/starts-with? k "xmlns:") [(subs k 6) v])))
        attrs))

(defn- resolve-name
  "The raw name `s` resolved in `scope` to a keyword, paired with a problem
  or nil. The `scope` maps each prefix to its URI and alias.

  A prefix that's undeclared, or whose URI is unknown, is its own alias,
  and the problem says so."
  [scope s]
  (let [[prefix local] (split-qname s)]
    (cond
      (nil? prefix)
      [(keyword local) nil]

      (= prefix "xml")
      [(keyword "xml" local) nil]

      :else
      (if-let [[uri alias] (get scope prefix)]
        (if alias
          [(keyword (name alias) local) nil]
          [(keyword prefix local)
           {:id :xml/unknown-namespace :level :info :prefix prefix :uri uri}])
        [(keyword prefix local)
         {:id :xml/undeclared-prefix :level :warning :prefix prefix}]))))

;; Namespaces in XML 1.0, section 6.2: the default namespace applies to
;; element names without a prefix, and not to attribute names
(defn- resolve-tag
  "The raw element name `s` resolved in `scope` to a keyword, paired with a
  problem or nil. Without a prefix, it takes the alias of the default
  namespace, which `scope` has under nil."
  [scope s]
  (let [[_ alias] (get scope nil)]
    (if (and alias (not (str/includes? s ":")))
      [(keyword (name alias) s) nil]
      (resolve-name scope s))))

(defn- resolve-attrs
  "The `attrs` with their names resolved in `scope`, paired with `problems`
  and those that the names add. The xmlns declarations go, but a default
  namespace without an alias stays as the attribute :xmlns."
  [scope problems attrs]
  (let [[_ default-alias] (get scope nil)]
    (reduce-kv (fn [[m problems] k v]
                 (cond
                   (= k "xmlns")
                   [(cond-> m (nil? default-alias) (assoc :xmlns v)) problems]

                   (str/starts-with? k "xmlns:")
                   [m problems]

                   :else
                   (let [[k' problem] (resolve-name scope k)]
                     [(assoc m k' v)
                      (cond-> problems problem (conj problem))])))
               [{} problems]
               attrs)))

;; Namespaces in XML 1.0, section 6.1
(defn- resolve-element
  "The `element` resolved in `scope` by `uri->alias`, paired with
  `problems` and those that it adds."
  [uri->alias scope problems [tag attrs & children :as element]]
  (let [decls                (declarations attrs)
        ;; the alias of a declared URI is looked up once, where it's
        ;; declared, not again for every name that uses it
        alias-of             (fn [[prefix uri]]
                               [prefix [uri (uri->alias uri)]])
        scope                (into scope (map alias-of) decls)
        default              (get decls nil)
        problems             (cond-> problems
                               (and (seq default)
                                    (nil? (second (get scope nil))))
                               (conj {:id    :xml/default-namespace
                                      :level :info
                                      :uri   default}))
        [tag' problem]       (resolve-tag scope tag)
        problems             (cond-> problems problem (conj problem))
        [attrs' problems]    (resolve-attrs scope problems attrs)
        resolve-child        (fn [[acc problems] child]
                               (if (string? child)
                                 [(conj acc child) problems]
                                 (let [[child' problems]
                                       (resolve-element uri->alias scope
                                                        problems child)]
                                   [(conj acc child') problems])))
        [children' problems] (reduce resolve-child [[] problems] children)]
    [(into [tag' attrs'] children') problems]))

(defn resolve-namespaces
  "The raw Hiccup `tree` as canonical Hiccup, with names namespaced by the
  aliases that `uri->alias` gives their URIs, and no xmlns declarations.

  An element name without a prefix is in the default namespace, if any.
  When `uri->alias` lacks that namespace, the names in it stay plain, and
  its declaration stays as the attribute :xmlns.

  The metadata has the problems under :xml/report, and under
  :xml/namespaces the URIs by prefix of the namespaces that `uri->alias`
  lacks. A prefix that no declaration names has nil."
  [tree uri->alias]
  (let [[tree' problems] (resolve-element uri->alias {} [] tree)
        problems         (distinct problems)
        by-prefix        (fn [id]
                           (into {} (for [p     problems
                                          :when (= id (:id p))]
                                      [(keyword (:prefix p)) (:uri p)])))
        undeclared       (by-prefix :xml/undeclared-prefix)
        unknown          (by-prefix :xml/unknown-namespace)]
    (with-meta tree' {:xml/report     (vec problems)
                      :xml/namespaces (merge undeclared unknown)})))

(defn parse
  "The canonical Hiccup of `xml`, with the namespaces resolved by
  `uri->alias`, an empty map by default."
  ([xml]
   (parse xml {}))
  ([xml uri->alias]
   (resolve-namespaces (parse-raw xml) uri->alias)))

(defn report
  "The problems found in resolving the namespaces of `tree`, as maps of an
  :id, a :level and details."
  [tree]
  (:xml/report (meta tree)))

(defn namespaces
  "The URIs by alias of the namespaces that `tree` carries in its metadata,
  e.g. those that its parse didn't know. A prefix that no declaration
  named has nil."
  [tree]
  (:xml/namespaces (meta tree)))

(def illegal-characters
  "The characters that section 2.2 of XML 1.0 forbids anywhere, as a
  pattern."
  #"[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]")

(defn legal?
  "Whether the string `s` has no character that XML 1.0 forbids."
  [^String s]
  ;; a plain loop over the characters, since a regular expression crawls
  ;; the megabytes of text in a big document thirty times slower
  (let [n (count s)]
    (loop [i 0]
      (if (= i n)
        true
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (or (< c 9)
                  (= c 11)
                  (= c 12)
                  (and (> c 13) (< c 32))
                  (= c 0xFFFE)
                  (= c 0xFFFF))
            false
            (recur (inc i))))))))

(defn- legal
  "The string `s` without the characters that XML 1.0 forbids."
  [s]
  (if (legal? s)
    s
    (str/replace s illegal-characters "")))

(defn ^:no-doc plain?
  "Whether the string `s` can be written as it is in the `context` of :text
  or :attr, with nothing that XML 1.0 forbids and nothing to escape."
  [^String s context]
  ;; one loop over the characters, which most values pass, in place of a
  ;; check of what's legal and a pattern each
  (let [attr? (= :attr context)
        n     (count s)]
    (loop [i 0]
      (if (== i n)
        true
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (if (< c 63)
                (or (== c 38) (== c 60) (== c 62)
                    (and (< c 32) (or attr? (< c 9) (> c 10)))
                    (and attr? (== c 34)))
                (>= c 0xFFFE))
            false
            (recur (inc i))))))))

;; XML 1.0, section 2.11
(defn- escape-legal-text
  "The legal string `s` escaped for a text node, with a carriage return as
  a character reference, which a parser doesn't normalize away."
  [s]
  (if (re-find #"[&<>\r]" s)
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\r" "&#13;"))
    s))

;; XML 1.0, section 3.3.3
(defn- escape-attr
  "The string `s` escaped for an attribute value, with newlines and tabs as
  character references, which a parser doesn't turn into spaces."
  [s]
  (if (plain? s :attr)
    s
    (let [s (escape-legal-text (legal s))]
      (if (re-find #"[\"\n\t]" s)
        (-> s
            (str/replace "\"" "&quot;")
            (str/replace "\n" "&#10;")
            (str/replace "\t" "&#9;"))
        s))))

(defn- append-text
  "The transient vector `acc` of string parts with the text `s` appended,
  as CDATA when it holds markup, or else escaped. A carriage return or ]]>
  rules out CDATA."
  [acc s]
  (if (plain? s :text)
    (conj! acc s)
    (let [s (legal s)]
      (if (and (str/includes? s "<")
               (not (str/includes? s "]]>"))
               (not (str/includes? s "\r")))
        (-> acc (conj! "<![CDATA[") (conj! s) (conj! "]]>"))
        (conj! acc (escape-legal-text s))))))

(defn qname
  "The XML name of the keyword `k`, e.g. dc:creator for :dc/creator."
  [k]
  (if-let [ns (namespace k)]
    (str ns ":" (name k))
    (name k)))

(defn- aliases
  "The set of namespace aliases that the tags and attributes in `tree` use."
  [tree]
  ;; one walk, which makes no lazy sequence
  (letfn [(with-ns [acc k]
            (if-let [ns (namespace k)]
              (conj! acc ns)
              acc))
          (walk [acc el]
            (let [acc (with-ns acc (tag el))
                  acc (reduce-kv (fn [acc k _] (with-ns acc k)) acc (attrs el))
                  n   (count el)]
              (loop [i 2 acc acc]
                (if (< i n)
                  (let [child (nth el i)]
                    (recur (inc i) (if (vector? child) (walk acc child) acc)))
                  acc))))]
    (into #{}
          (comp (remove #{"xml"}) (map keyword))
          (persistent! (walk (transient #{}) tree)))))

(defn ^:no-doc append-attrs
  "The transient vector `acc` of string parts with the attributes `attrs`
  appended, each after a space."
  [acc attrs]
  (reduce-kv (fn [acc k v]
               (-> acc
                   (conj! " ")
                   (conj! (qname k))
                   (conj! "=\"")
                   (conj! (escape-attr (str v)))
                   (conj! "\"")))
             acc
             attrs))

(def ^:no-doc indents
  "The indentation of an element at each depth, two spaces a level."
  (vec (take (inc max-depth) (iterate #(str % "  ") ""))))

(declare emit-compact*)

;; One flat vector joined once at the end keeps a 30 MB document from being
;; copied once for each level of nesting
(defn- emit-element*
  "The transient vector `acc` of string parts with `element` appended,
  indented for `depth`, except in mixed content."
  [acc depth element]
  (let [indent   (or (get indents depth) (apply str (repeat (* 2 depth) " ")))
        children (children element)]
    ;; indentation would join the text of mixed content and grow it on
    ;; every round trip
    (if (and (some string? children) (some vector? children))
      (emit-compact* (conj! acc indent) element append-text)
      (let [name (qname (tag element))
            acc  (-> acc
                     (conj! indent)
                     (conj! "<")
                     (conj! name)
                     (append-attrs (attrs element)))]
        (cond
          (empty? children)
          (conj! acc "/>")

          (every? string? children)
          (-> acc
              (conj! ">")
              (append-text (apply str children))
              (conj! "</")
              (conj! name)
              (conj! ">"))

          :else
          (-> (reduce (fn [acc [i child]]
                        (let [acc (if (pos? i) (conj! acc "\n") acc)]
                          (emit-element* acc (inc depth) child)))
                      (conj! acc ">\n")
                      (map-indexed vector children))
              (conj! "\n")
              (conj! indent)
              (conj! "</")
              (conj! name)
              (conj! ">")))))))

(defn- without
  "The Hiccup `element` without the elements and attributes in the
  namespaces `aliases` names, or nil when the element itself is in one."
  [aliases element]
  (let [in? #(contains? aliases (some-> (namespace %) keyword))]
    (when-not (in? (tag element))
      (into [(tag element) (into {} (remove (comp in? key)) (attrs element))]
            (keep #(if (string? %) % (without aliases %)))
            (children element)))))

(defn emit
  "The canonical Hiccup `tree` as an XML string, with the namespaces that
  it uses declared on the root element by `alias->uri`.

  The `alias->uri` map defaults to the namespaces in the metadata of
  `tree`. An alias that it maps to nil is left out, elements and
  attributes alike, and one that it lacks is an error."
  ([tree]
   (emit tree (namespaces tree)))
  ([tree alias->uri]
   (let [undeclared (when (map? alias->uri)
                      (set (keep (fn [[alias uri]] (when (nil? uri) alias))
                                 alias->uri)))
         tree       (if (seq undeclared)
                      (or (without undeclared tree) tree)
                      tree)
         uri-of     (fn [alias]
                      (or (get alias->uri alias)
                          (throw (ex-info "Unknown namespace alias"
                                          {:alias alias}))))
         decls      (into (sorted-map)
                          (for [alias (aliases tree)]
                            [(keyword "xmlns" (name alias)) (uri-of alias)]))
         root       (update tree 1 #(into decls %))
         xml-decl   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"]
     ;; the declaration and the last line break are parts too, so that the
     ;; document is put together once
     (str/join (persistent! (-> (transient [xml-decl])
                                (emit-element* 0 root)
                                (conj! "\n")))))))

(defn- emit-compact*
  "The transient vector `acc` of string parts with `element` appended on
  one line, its text by `append-fn`, a function of the vector and a string."
  [acc element append-fn]
  (let [name (qname (tag element))
        acc  (-> acc (conj! "<") (conj! name) (append-attrs (attrs element)))]
    (if (empty? (children element))
      (conj! acc "/>")
      (-> (reduce (fn [acc child]
                    (if (string? child)
                      (append-fn acc child)
                      (emit-compact* acc child append-fn)))
                  (conj! acc ">")
                  (children element))
          (conj! "</") (conj! name) (conj! ">")))))

(defn- append-escaped
  "The transient vector `acc` of string parts with the text `s` appended,
  escaped, and never as CDATA, which HTML doesn't read."
  [acc s]
  (conj! acc (if (plain? s :text) s (escape-legal-text (legal s)))))

(defn inner-xml
  "The content of `element` as markup on one line, e.g. HTML that a
  document embeds as elements. Its text stays escaped, so an escaped tag
  doesn't become an element."
  [element]
  (str/join (persistent! (reduce (fn [acc child]
                                   (if (string? child)
                                     (append-escaped acc child)
                                     (emit-compact* acc child append-escaped)))
                                 (transient [])
                                 (children element)))))

(comment
  (parse "<book xmlns:dublin=\"http://purl.org/dc/elements/1.1/\" id=\"1\">
            <title>Emma</title><dublin:creator>Jane Austen</dublin:creator>
          </book>"
         {"http://purl.org/dc/elements/1.1/" :dc})
  ;; => [:book {:id "1"}
  ;;     [:title {} "Emma"] [:dc/creator {} "Jane Austen"]]
  (println (emit *1 {:dc "http://purl.org/dc/elements/1.1/"}))
  #_.)
