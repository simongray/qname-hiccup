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
  deeper than `max-depth`, with elements and their text only."
  [^Node node depth max-depth]
  (condp instance? node
    TextNode (.getWholeText ^TextNode node)
    Element  (do (parser/check-depth depth max-depth)
                 (into [(.tagName ^Element node) (attrs node)]
                       (whitespace/compact-text
                        (into []
                              (keep #(node->raw % (inc depth) max-depth))
                              (.childNodes ^Element node)))))
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
  :max-depth of `opts`. It throws ::xml/malformed only when the document
  has no element or nests too deep."
  ([xml]
   (parse-raw xml {}))
  ([xml opts]
   (if-let [root (first (.children (document xml)))]
     (node->raw root 1 (or (:max-depth opts) xml/max-depth))
     (throw (parser/malformed "no root element" nil)))))

(comment
  (parse-raw "<book id=1><title>A <3 B</title></book>")
  #_.)
