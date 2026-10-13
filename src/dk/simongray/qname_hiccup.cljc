(ns dk.simongray.qname-hiccup
  "XML as Hiccup, with namespaces resolved by URI rather than by prefix.

  Raw Hiccup has the names as written. Canonical Hiccup has keywords
  namespaced by the alias that a table gives the URI, e.g. :dc/creator,
  whether the name has a prefix or is in the default namespace. Its
  metadata has what the resolution found under :xml/report, the
  namespaces that the table lacks under :xml/namespaces, and those it has
  under :xml/declared."
  (:require [clojure.string :as str]
            [dk.simongray.qname-hiccup.encoding :as encoding]
            [dk.simongray.qname-hiccup.parser :as parser]
            [dk.simongray.qname-hiccup.serializer :as serializer]
            [dk.simongray.qname-hiccup.whitespace :as whitespace]))

(defn tag
  "The tag of the Hiccup `element`."
  [element]
  (nth element 0))

(defn attrs
  "The attribute map of the Hiccup `element`, empty when it has none, e.g.
  of [:title \"Emma\"]."
  [element]
  (let [a (nth element 1 nil)]
    (if (map? a) a {})))

(defn children
  "The children of the Hiccup `element`: strings and elements."
  [element]
  (drop (if (map? (nth element 1 nil)) 2 1) element))

(defn text
  "The text directly in the Hiccup `element`, joined."
  [element]
  ;; one string is the common case, and needs no joining
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

(defn elements-in
  "The descendants of `element` along the path of `tags`, a tag for each
  level, e.g. the items of an RSS feed for [:channel :item]."
  [element tags]
  (reduce (fn [els tag] (mapcat #(elements % tag) els)) [element] tags))

(defn element-in
  "The first descendant of `element` along the path of `tags`, a tag for
  each level, e.g. the title of an RSS feed for [:channel :title]."
  [element tags]
  (first (elements-in element tags)))

(defn deep-text
  "All the text in `element` and its descendants, in document order."
  [element]
  (apply str (filter string? (tree-seq vector? children element))))

(defn trim
  "The string `s` without white space at either end, no-break spaces
  included, the same on the JVM and in JavaScript."
  [s]
  (whitespace/trim s))

(defn decode
  "The text of the XML document in the bytes `b`, in the encoding that its
  byte order mark, `charset` or XML declaration names first, or else UTF-8.

  The `charset` is e.g. that of a Content-Type header. Each label names an
  encoding as a browser reads it, e.g. latin1 names windows-1252, and a
  label of no encoding doesn't count. The byte order mark is dropped."
  ([b]
   (decode b nil))
  ([b charset]
   (encoding/decode b charset)))

;; far deeper than documents nest, and shallow enough for every walk of the
;; tree to recurse on a thread with a stack of the default size
(def max-depth
  "How deep elements may nest in a document, with the root at 1. A deeper
  document is malformed."
  256)

(def indent
  "The indentation of each level of elements in a written document."
  "  ")

(def default-options
  "The default of each option, the vars of the same name, and these:

  - :whitespace, :compact to leave out text of only white space, but a
    space between two elements on one line, or :keep to keep all text, as
    XML 1.0, section 2.10, has a processor do
  - :default-namespace, nil or the alias whose elements are written
    without a prefix, in the default namespace, e.g. :atom for
    <feed xmlns=\"http://www.w3.org/2005/Atom\">"
  {:max-depth         max-depth
   :indent            indent
   :whitespace        :compact
   :default-namespace nil})

(defn- options
  "The `opts` with the defaults of the keys that they leave out or give as
  nil."
  [opts]
  (if (empty? opts)
    default-options
    (into default-options (remove (comp nil? val)) opts)))

(defn parse-raw
  "The raw Hiccup of the string `xml`, nested no deeper than the :max-depth
  of `opts`, with names as written and the namespace declarations as
  attributes.

  On the JVM, `xml` can also be a Reader, or an InputStream whose declared
  encoding is honoured. Comments and processing instructions are dropped,
  and text and CDATA merged. Text of only white space is left out, as the
  :whitespace of `opts` says, but kept in an element with
  xml:space=\"preserve\" and its descendants. A document that isn't
  well-formed or nests too deep throws ::malformed.

  On the JVM, the entities of the internal DTD subset are expanded, and an
  XHTML document has those of XHTML, but no external DTD or entity is
  read. An entity that only such a DTD could declare is left out, and
  the :xml/report in the metadata names those in text."
  ([xml]
   (parse-raw xml {}))
  ([xml opts]
   (let [{:keys [max-depth whitespace]} (options opts)]
     (parser/parse-raw xml max-depth (= :keep whitespace)))))

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

(defn- in-scope
  "The `scope` with the namespaces that the raw `attrs` declare, each under
  its prefix as its URI and the alias that `uri->alias` gives it.

  The URI of each namespace with an alias is added to the volatile
  `declared`, under the alias. A default namespace without an alias is a
  problem, added to the volatile `problems`."
  [uri->alias problems declared scope attrs]
  (let [decls (if (zero? (count attrs)) {} (declarations attrs))]
    (if (zero? (count decls))
      scope
      (let [bound   (map (fn [[prefix uri]] [prefix [uri (uri->alias uri)]])
                         decls)
            scope   (into scope bound)
            default (get decls nil)]
        (vswap! declared into
                (keep (fn [[_ [uri alias]]] (when alias [alias uri])))
                bound)
        (when (and (seq default)
                   (nil? (second (get scope nil))))
          (vswap! problems conj
                  {:id :xml/default-namespace :level :info :uri default}))
        scope))))

(defn- resolve-name
  "The raw name `s` resolved in `scope` to a keyword. A prefix that's
  undeclared, or whose URI is unknown, is its own alias, and a problem that
  says so is added to the volatile `problems`."
  [scope problems s]
  (let [[prefix local] (split-qname s)]
    (if (or (nil? prefix) (= "xml" prefix))
      (keyword prefix local)
      (let [[uri alias :as binding] (get scope prefix)]
        (when-not alias
          (vswap! problems conj
                  (if binding
                    {:id :xml/unknown-namespace :level :info
                     :prefix prefix :uri uri}
                    {:id :xml/undeclared-prefix :level :warning
                     :prefix prefix})))
        (keyword (if alias (name alias) prefix) local)))))

;; Namespaces in XML 1.0, section 6.2: the default namespace applies to
;; element names without a prefix, and not to attribute names
(defn- resolve-tag
  "The raw element name `s` resolved in `scope` to a keyword, in the default
  namespace when it has no prefix. Problems are added to the volatile
  `problems`."
  [scope problems s]
  (let [[_ alias] (get scope nil)]
    (if (and alias (not (str/includes? s ":")))
      (keyword (name alias) s)
      (resolve-name scope problems s))))

(defn- resolve-attrs
  "The raw `attrs` with their names resolved in `scope`, and without the
  xmlns declarations but that of a default namespace without an alias,
  which stays as :xmlns. Problems are added to the volatile `problems`."
  [scope problems attrs]
  (if (zero? (count attrs))
    {}
    (let [[_ default-alias] (get scope nil)]
      (persistent!
       (reduce-kv (fn [m k v]
                    (cond
                      (= k "xmlns")
                      (cond-> m (nil? default-alias) (assoc! :xmlns v))

                      (str/starts-with? k "xmlns:")
                      m

                      :else
                      (assoc! m (resolve-name scope problems k) v)))
                  (transient {})
                  attrs)))))

;; Namespaces in XML 1.0, section 6.1
(defn- resolve-element
  "The raw Hiccup `element` resolved by `uri->alias` in `scope`, which holds
  the declarations of its ancestors. Problems are added to the volatile
  `problems`, and the namespaces with an alias to the volatile `declared`."
  [uri->alias problems declared scope [tag attrs & children]]
  (let [scope (in-scope uri->alias problems declared scope attrs)]
    (into [(resolve-tag scope problems tag)
           (resolve-attrs scope problems attrs)]
          (map #(if (string? %)
                  %
                  (resolve-element uri->alias problems declared scope %)))
          children)))

(defn resolve-namespaces
  "The raw Hiccup `tree` as canonical Hiccup, with names namespaced by the
  aliases that `uri->alias` gives their URIs, and no xmlns declarations.

  An element name without a prefix is in the default namespace, if any.
  When `uri->alias` lacks that namespace, the names in it stay plain, and
  its declaration stays as the attribute :xmlns.

  The metadata has:

  - :xml/report, the problems, after those in the metadata of `tree`
  - :xml/namespaces, the URIs by prefix of the namespaces that
    `uri->alias` lacks, with nil for a prefix that no declaration names
  - :xml/declared, the URIs by alias of the namespaces that the tree
    declares and `uri->alias` has"
  [tree uri->alias]
  (let [found     (volatile! (vec (:xml/report (meta tree))))
        declared  (volatile! {})
        tree'     (resolve-element uri->alias found declared {} tree)
        problems  (distinct @found)
        by-prefix (fn [id]
                    (into {}
                          (comp (filter #(= id (:id %)))
                                (map (juxt (comp keyword :prefix) :uri)))
                          problems))
        foreign   (merge (by-prefix :xml/undeclared-prefix)
                         (by-prefix :xml/unknown-namespace))]
    (with-meta tree' {:xml/report     (vec problems)
                      :xml/namespaces foreign
                      :xml/declared   @declared})))

(defn parse
  "The canonical Hiccup of `xml`, with the namespaces resolved by
  `uri->alias`, an empty map by default, and the `opts` of parse-raw."
  ([xml]
   (parse xml {}))
  ([xml uri->alias]
   (parse xml uri->alias {}))
  ([xml uri->alias opts]
   (resolve-namespaces (parse-raw xml opts) uri->alias)))

(defn report
  "The problems found in parsing `tree` and resolving its namespaces, as
  maps of an :id, a :level and details."
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
  serializer/illegal-characters)

(defn legal?
  "Whether the string `s` has no character that XML 1.0 forbids."
  [s]
  (serializer/legal? s))

(defn qname
  "The XML name of the keyword `k`, e.g. dc:creator for :dc/creator."
  [k]
  (serializer/qname k))

(defn emit
  "The canonical Hiccup `tree` as an XML string, with the namespaces that
  it uses declared on the root element by `alias->uri`, and the :indent
  and :default-namespace of `opts`.

  The `alias->uri` map defaults to the namespaces in the metadata of
  `tree`, under :xml/namespaces and :xml/declared. An alias that it maps
  to nil is left out, elements and attributes alike, and one that it
  lacks is an error. The tree can also be Hiccup as a renderer reads it,
  e.g. [:title \"Emma\"] without an attribute map, or with a sequence of
  children, which is spliced in."
  ([tree]
   (emit tree nil))
  ([tree alias->uri]
   (emit tree alias->uri {}))
  ([tree alias->uri opts]
   (let [{:keys [indent default-namespace]} (options opts)]
     (serializer/emit tree
                      (or alias->uri
                          (merge (namespaces tree)
                                 (:xml/declared (meta tree))))
                      indent
                      default-namespace))))

(defn inner-xml
  "The content of `element` as markup on one line, e.g. HTML that a
  document embeds as elements. Its text stays escaped, so an escaped tag
  doesn't become an element."
  [element]
  (serializer/inner-xml element))

(comment
  (parse "<book xmlns:dublin=\"http://purl.org/dc/elements/1.1/\" id=\"1\">
            <title>Emma</title><dublin:creator>Jane Austen</dublin:creator>
          </book>"
         {"http://purl.org/dc/elements/1.1/" :dc})
  ;; => [:book {:id "1"}
  ;;     [:title {} "Emma"] [:dc/creator {} "Jane Austen"]]
  (println (emit *1 {:dc "http://purl.org/dc/elements/1.1/"}))
  #_.)
