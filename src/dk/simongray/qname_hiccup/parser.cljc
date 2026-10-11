(ns ^:no-doc dk.simongray.qname-hiccup.parser
  "The strict parse of the platform, StAX on the JVM and a DOMParser in
  JavaScript, read into raw Hiccup with the names as written."
  (:require [dk.simongray.qname-hiccup :as-alias xml]
            [dk.simongray.qname-hiccup.encoding :as encoding]
            [dk.simongray.qname-hiccup.whitespace :as whitespace])
  #?(:clj (:import [java.io InputStream Reader StringReader]
                   [javax.xml.stream XMLInputFactory XMLStreamConstants
                                     XMLStreamException XMLStreamReader])))

(defn malformed
  "The error for malformed XML, with the parser's `message` and the `cause`
  that carried it, or nil."
  [message cause]
  (ex-info (str "Malformed XML: " message) {:type ::xml/malformed} cause))

(defn check-depth
  "Nil, unless an element at `depth` nests deeper than `max-depth`, which
  throws."
  [depth max-depth]
  (when (> depth max-depth)
    (throw (malformed (str "elements nest deeper than " max-depth " levels")
                      nil))))

#?(:cljs
   (defn- dom-parser
     "A DOMParser of the platform, or a throw without one."
     []
     (if (exists? js/DOMParser)
       (js/DOMParser.)
       (throw (ex-info (str "No DOMParser to read XML with. In Node, install"
                            " one as globalThis.DOMParser, e.g. that of"
                            " @xmldom/xmldom.")
                       {:type ::xml/no-parser})))))

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
       ;; off the entity limits guard nothing, and the depth is checked
       ;; here, the same on every JDK and in JavaScript.
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
                                 (StringReader. (encoding/strip-bom xml)))

         (instance? InputStream xml)
         (.createXMLStreamReader factory ^InputStream xml)

         (instance? Reader xml)
         (.createXMLStreamReader factory ^Reader xml)

         :else
         (throw (ex-info "Cannot parse XML from this value"
                         {:type (type xml)}))))
     :cljs
     ;; the parser first, since a platform without one is no fault of the
     ;; XML
     (let [parser (dom-parser)
           doc    (try
                    (.parseFromString parser
                                      (encoding/strip-bom xml)
                                      "text/xml")
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

#?(:clj
   (defn- qname
     "The name of `local` with `prefix`, as a document writes it."
     [prefix local]
     (if (empty? prefix)
       local
       (str prefix ":" local))))

(defn- start-tag
  "The name and attributes of the element at `source`, as written, in a
  pair. The namespace declarations are among the attributes.

  On the JVM, `source` is a StAX reader on its start tag, and in JavaScript
  a DOM element."
  [source]
  #?(:clj  (let [^XMLStreamReader r source
                 n-attrs (.getAttributeCount r)
                 n-decls (.getNamespaceCount r)]
             [(qname (.getPrefix r) (.getLocalName r))
              ;; most elements have neither
              (if (== 0 (+ n-attrs n-decls))
                {}
                (-> {}
                    (into (map (fn [i]
                                 [(qname (.getAttributePrefix r i)
                                         (.getAttributeLocalName r i))
                                  (.getAttributeValue r i)]))
                          (range n-attrs))
                    (into (map (fn [i]
                                 [(qname "xmlns" (.getNamespacePrefix r i))
                                  (.getNamespaceURI r (int i))]))
                          (range n-decls))))])
     :cljs (let [attrs (.-attributes source)
                 attr  (fn [i]
                         (let [a (.item attrs i)]
                           [(.-nodeName a) (.-nodeValue a)]))]
             [(.-nodeName source)
              (into {} (map attr) (range (.-length attrs)))])))

(declare read-element)

(defn- read-children
  "The raw elements and text in the element at `source`, which nest at
  `depth`, no deeper than `max-depth`, with white space kept as `keep?`
  and `default?` say to keep-space?. On the JVM, this reads the StAX
  reader up to the end tag."
  [source depth max-depth default? keep?]
  #?(:clj  (let [^XMLStreamReader r source]
             (loop [acc (transient [])]
               (let [event (.next r)]
                 (cond
                   (== event XMLStreamConstants/START_ELEMENT)
                   (recur (conj! acc (read-element r depth max-depth default?
                                                   keep?)))

                   (or (== event XMLStreamConstants/CHARACTERS)
                       (== event XMLStreamConstants/CDATA)
                       (== event XMLStreamConstants/SPACE))
                   (recur (conj! acc (.getText r)))

                   (== event XMLStreamConstants/END_ELEMENT)
                   (persistent! acc)

                   :else
                   (recur acc)))))
     :cljs (let [nodes (.-childNodes source)
                 child (fn [i]
                         (let [node (.item nodes i)]
                           (case (.-nodeType node)
                             1     (read-element node depth max-depth default?
                                                 keep?)
                             (3 4) (.-nodeValue node)
                             nil)))]
             (into [] (keep child) (range (.-length nodes))))))

(defn- read-element
  "The raw Hiccup of the element at `source`, which nests at `depth`, 1 for
  the root, no deeper than `max-depth`, with white space kept as its
  xml:space says, or else as its parent's, `inherited?`, and `default?`
  for xml:space=\"default\"."
  [source depth max-depth default? inherited?]
  (check-depth depth max-depth)
  (let [[_ attrs :as head] (start-tag source)
        keep?              (whitespace/keep-space? attrs inherited? default?)
        ;; the start tag first: on the JVM, reading the children moves
        ;; past it
        children           (read-children source (inc depth) max-depth
                                          default? keep?)]
    (into head (if keep?
                 (whitespace/joined-text children)
                 (whitespace/compact-text children)))))

(defn parse-raw
  "The raw Hiccup of `xml`, with elements nested no deeper than
  `max-depth`, and white space kept when `keep?`, or else only where
  xml:space says so."
  [xml max-depth keep?]
  #?(:clj
     (try
       (let [^XMLStreamReader r (document xml)]
         (try
           (let [root (loop []
                        (if (== (.next r) XMLStreamConstants/START_ELEMENT)
                          (read-element r 1 max-depth keep? keep?)
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
     (read-element (document xml) 1 max-depth keep? keep?)))
