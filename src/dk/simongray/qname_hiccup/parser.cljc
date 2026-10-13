(ns ^:no-doc dk.simongray.qname-hiccup.parser
  "The strict parse of the platform, StAX on the JVM and a DOMParser in
  JavaScript, read into raw Hiccup with the names as written."
  (:require [dk.simongray.qname-hiccup :as-alias xml]
            [dk.simongray.qname-hiccup.encoding :as encoding]
            #?(:clj [dk.simongray.qname-hiccup.xhtml :as xhtml])
            [dk.simongray.qname-hiccup.whitespace :as whitespace])
  #?(:clj (:import [java.io ByteArrayInputStream InputStream Reader
                    StringReader]
                   [javax.xml.stream XMLInputFactory XMLResolver
                                     XMLStreamConstants XMLStreamException
                                     XMLStreamReader])))

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

(defn check-doctype
  "Nil, unless the document has a DOCTYPE while DTDs aren't read, which
  throws. The `doctype?` says whether it has one, and `dtd?` whether DTDs
  are read."
  [doctype? dtd?]
  (when (and doctype? (not dtd?))
    (throw (malformed (str "the document has a DOCTYPE, which the :doctype"
                           " option refuses")
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
   (def ^:no-doc entity-limits
     "The JDK's limits on entities when a DTD is read, the same on every
     JDK, which stop a document whose entities expand into gigabytes.

     The JDK counts each &amp; and the like towards the total, and the
     whole document towards the size of one entity, so the total also
     stops a document with more than ten million of those, and the size
     of one entity is unlimited. Without a DTD, no entity can be declared
     and the text can't grow longer than the document, so no limit is set.
     The depth of elements is checked here, the same on every JDK and in
     JavaScript."
     {"entityExpansionLimit"      "64000"
      "totalEntitySizeLimit"      "10000000"
      "maxGeneralEntitySizeLimit" "0"
      "maxElementDepth"           "0"}))

#?(:clj
   (defn- resolver
     "An XMLResolver that never fetches: it gives the entities of XHTML
     for the external DTD of an XHTML document, and nothing for any other
     external DTD."
     ^XMLResolver []
     (reify XMLResolver
       (resolveEntity [_ public-id _ _ _]
         (ByteArrayInputStream.
          (.getBytes ^String (if (xhtml/xhtml? public-id) xhtml/dtd "")
                     "UTF-8"))))))

#?(:clj
   (defn- input-factory
     "A StAX factory without namespace processing or external entities,
     which reads the internal subset of a DTD within entity-limits when
     `dtd?`, or else no DTD at all, without limits."
     ^XMLInputFactory [dtd?]
     (let [factory (XMLInputFactory/newInstance)]
       (doto factory
         ;; so that undeclared prefixes survive, and the xmlns declarations
         ;; arrive as plain attributes
         (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
         (.setProperty XMLInputFactory/IS_COALESCING true)
         ;; when false, the JDK still scans the internal subset, but
         ;; declares and expands none of its entities
         (.setProperty XMLInputFactory/SUPPORT_DTD dtd?)
         (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
         (.setProperty XMLInputFactory/IS_REPLACING_ENTITY_REFERENCES true)
         (.setXMLResolver (resolver)))
       ;; JDK 11 knows only the legacy names of the limits. A limit of 0 is
       ;; no limit.
       (doseq [prefix        ["http://www.oracle.com/xml/jaxp/properties/"
                              "jdk.xml."]
               [limit value] entity-limits]
         (try
           (.setProperty factory (str prefix limit) (if dtd? value "0"))
           (catch IllegalArgumentException _)))
       factory)))

(defn- document
  "The document in `xml`, ready to read: a StAX reader at its start on the
  JVM, and the root element of a DOM in JavaScript.

  On the JVM, `xml` is a string, an InputStream or a Reader, and the
  reader reads a DTD only when `dtd?`. In JavaScript, `xml` is a string,
  and a document with a DOCTYPE throws unless `dtd?`."
  [xml dtd?]
  #?(:clj
     (let [factory (input-factory dtd?)]
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
         (do (check-doctype (some? (.-doctype doc)) dtd?)
             root)))))

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
  and `default?` say to keep-space?, and the names of skipped entities
  added to the volatile `skipped`. On the JVM, this reads the StAX reader
  up to the end tag."
  [source depth max-depth default? keep? skipped]
  #?(:clj  (let [^XMLStreamReader r source]
             (loop [acc (transient [])]
               (let [event (.next r)]
                 (cond
                   (== event XMLStreamConstants/START_ELEMENT)
                   (recur (conj! acc (read-element r depth max-depth default?
                                                   keep? skipped)))

                   (or (== event XMLStreamConstants/CHARACTERS)
                       (== event XMLStreamConstants/CDATA)
                       (== event XMLStreamConstants/SPACE))
                   (recur (conj! acc (.getText r)))

                   (== event XMLStreamConstants/END_ELEMENT)
                   (persistent! acc)

                   ;; XML 1.0, section 4.1, VC Entity Declared: an entity
                   ;; that only the external DTD can declare, which is
                   ;; never read, is skipped, as a browser skips it
                   (== event XMLStreamConstants/ENTITY_REFERENCE)
                   (do (vswap! skipped conj (.getLocalName r))
                       (recur acc))

                   :else
                   (recur acc)))))
     :cljs (let [nodes (.-childNodes source)
                 child (fn [i]
                         (let [node (.item nodes i)]
                           (case (.-nodeType node)
                             1     (read-element node depth max-depth default?
                                                 keep? skipped)
                             (3 4) (.-nodeValue node)
                             nil)))]
             (into [] (keep child) (range (.-length nodes))))))

(defn- read-element
  "The raw Hiccup of the element at `source`, which nests at `depth`, 1 for
  the root, no deeper than `max-depth`, with white space kept as its
  xml:space says, or else as its parent's, `inherited?`, `default?` for
  xml:space=\"default\", and the names of skipped entities added to the
  volatile `skipped`."
  [source depth max-depth default? inherited? skipped]
  (check-depth depth max-depth)
  (let [[_ attrs :as head] (start-tag source)
        keep?              (whitespace/keep-space? attrs inherited? default?)
        ;; the start tag first: on the JVM, reading the children moves
        ;; past it
        children           (read-children source (inc depth) max-depth
                                          default? keep? skipped)]
    (into head (if keep?
                 (whitespace/joined-text children)
                 (whitespace/compact-text children)))))

#?(:clj
   (defn- with-skipped
     "The raw Hiccup `root` with a report of the entities named in `skipped`
     in its metadata, if any."
     [root skipped]
     (if (empty? skipped)
       root
       (with-meta root
         {:xml/report (mapv (fn [entity]
                              {:id :xml/skipped-entity :level :warning
                               :entity entity})
                            (distinct skipped))}))))

(defn parse-raw
  "The raw Hiccup of `xml`, with elements nested no deeper than
  `max-depth`, and white space kept when `keep?`, or else only where
  xml:space says so. Unless `dtd?`, a document with a DOCTYPE throws."
  [xml max-depth keep? dtd?]
  #?(:clj
     (try
       (let [^XMLStreamReader r (document xml dtd?)
             skipped            (volatile! [])]
         (try
           (let [root (loop []
                        (let [event (.next r)]
                          (check-doctype (== event XMLStreamConstants/DTD) dtd?)
                          (if (== event XMLStreamConstants/START_ELEMENT)
                            (read-element r 1 max-depth keep? keep? skipped)
                            (recur))))]
             ;; on to the end, where anything but a comment or white space
             ;; after the root is an error, as it is in a browser
             (while (.hasNext r)
               (.next r))
             (with-skipped root @skipped))
           (finally
             (.close r))))
       (catch XMLStreamException e
         (throw (malformed (.getMessage e) e))))
     :cljs
     (read-element (document xml dtd?) 1 max-depth keep? keep? nil)))
