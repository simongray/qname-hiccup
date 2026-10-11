(ns dk.simongray.qname-hiccup.jsoup
  "A lenient XML parser for documents that aren't well-formed, built on the
  XML mode of jsoup.

  Unquoted attributes, stray angle brackets in text, and unclosed or
  mismatched tags give a tree as good as it gets, rather than an error. It
  needs org.jsoup/jsoup, which qname-hiccup leaves out."
  (:require [dk.simongray.qname-hiccup :as xml]
            [dk.simongray.qname-hiccup.parser :as parser]
            [dk.simongray.qname-hiccup.whitespace :as whitespace])
  (:import [java.io InputStream]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Document Element Node TextNode]
           [org.jsoup.parser Parser]))

(defn- attrs
  "The attributes of the jsoup `element`, by name as written."
  [^Element element]
  (into {}
        (map (fn [^Attribute a] [(.getKey a) (.getValue a)]))
        (.attributes element)))

(defn- node->raw
  "The raw Hiccup of the jsoup `node` at `depth`, 1 for the root and no
  deeper than `max-depth`, with elements and their text only, and white
  space kept as `inherited?` and `default?` say to whitespace/keep-space?."
  [^Node node depth max-depth default? inherited?]
  (condp instance? node
    TextNode (.getWholeText ^TextNode node)
    Element  (do (parser/check-depth depth max-depth)
                 (let [attributes (attrs node)
                       keep?      (whitespace/keep-space? attributes
                                                          inherited?
                                                          default?)
                       children   (into []
                                        (keep #(node->raw % (inc depth)
                                                          max-depth default?
                                                          keep?))
                                        (.childNodes ^Element node))]
                   (into [(.tagName ^Element node) attributes]
                         (if keep?
                           (whitespace/joined-text children)
                           (whitespace/compact-text children)))))
    nil))

(defn- document
  "The jsoup document of `xml`, read in its XML mode."
  ^Document [xml]
  (cond
    (string? xml)
    (Jsoup/parse ^String xml "" (Parser/xmlParser))

    (instance? InputStream xml)
    (Jsoup/parse ^InputStream xml nil "" (Parser/xmlParser))

    :else
    (throw (ex-info "Cannot parse XML from this value" {:type (type xml)}))))

(defn parse-raw
  "The raw Hiccup of `xml`, a string or an InputStream whose charset jsoup
  detects, with names as written and elements nested no deeper than the
  :max-depth of `opts`, and white space as their :whitespace says, as in
  xml/parse-raw. It throws ::xml/malformed only when the document has no
  element or nests too deep."
  ([xml]
   (parse-raw xml {}))
  ([xml opts]
   (if-let [root (first (.children (document xml)))]
     (let [keep? (= :keep (:whitespace opts))]
       (node->raw root 1 (or (:max-depth opts) xml/max-depth) keep? keep?))
     (throw (parser/malformed "no root element" nil)))))

(comment
  (parse-raw "<book id=1><title>A <3 B</title></book>")
  #_.)
