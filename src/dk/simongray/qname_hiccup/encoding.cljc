(ns ^:no-doc dk.simongray.qname-hiccup.encoding
  "The encoding of an XML document in bytes, by XML 1.0, appendix F, and
  RFC 7303, section 3.2, with each label read as a browser reads it."
  (:require [clojure.string :as str]
            [dk.simongray.whatwg-mime :as mime]))

(defn strip-bom
  "The text `s` without a leading byte order mark."
  [s]
  (if (str/starts-with? s "﻿")
    (subs s 1)
    s))

(defn- declared-encoding
  "The encoding that the XML declaration of the bytes `b` names, or nil."
  [b]
  ;; the declaration comes first, so the start of the document is enough
  (let [n    (min 200 #?(:clj (alength ^bytes b) :cljs (.-length b)))
        head #?(:clj  (String. ^bytes b 0 (int n) "ISO-8859-1")
                :cljs (.decode (js/TextDecoder. "latin1") (.subarray b 0 n)))]
    (some-> (re-find #"(?i)<\?xml[^>]*encoding=[\"']([\w.:-]+)" head)
            second
            mime/label->encoding)))

(defn decode
  "The text of the XML document in the bytes `b`, in the encoding that its
  byte order mark, `charset` or XML declaration names first, or else UTF-8.
  A label of no encoding doesn't count, and the byte order mark is
  dropped."
  [b charset]
  ;; the byte order mark comes first, as mime/decode reads it
  (mime/decode b (or (mime/label->encoding charset)
                     (declared-encoding b))))
