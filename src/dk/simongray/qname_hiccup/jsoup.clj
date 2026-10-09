(ns dk.simongray.qname-hiccup.jsoup
  "A lenient XML parser for documents that aren't well-formed, built on the
  XML mode of jsoup.

  Unquoted attributes, stray angle brackets in text, and unclosed or
  mismatched tags give a tree as good as it gets, rather than an error. It
  needs org.jsoup/jsoup, which qname-hiccup leaves out."
  (:require [dk.simongray.qname-hiccup :as xml])
  (:import [java.io InputStream]
           [org.jsoup Jsoup]
           [org.jsoup.nodes Attribute Document Element Node TextNode]
           [org.jsoup.parser Parser]))

(defn- attrs
  [^Element element]
  (into {} (for [^Attribute a (.attributes element)]
             [(.getKey a) (.getValue a)])))

(defn- node->raw
  "The raw Hiccup of the jsoup `node` at `depth`, 1 for the root, with
  elements and their text only."
  [^Node node depth]
  (condp instance? node
    TextNode (.getWholeText ^TextNode node)
    Element  (do (xml/check-depth depth)
                 (let [children (.childNodes ^Element node)
                       raw      (keep #(node->raw % (inc depth)) children)]
                   (into [(.tagName ^Element node) (attrs node)]
                         (xml/compact-text raw))))
    nil))

(defn- document
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
  detects, with names as written. It throws ::xml/malformed only when the
  document has no element or nests too deep."
  [xml]
  (if-let [root (first (.children (document xml)))]
    (node->raw root 1)
    (throw (ex-info "Malformed XML: no root element" {:type ::xml/malformed}))))

(comment
  (parse-raw "<book id=1><title>A <3 B</title></book>")
  #_.)
