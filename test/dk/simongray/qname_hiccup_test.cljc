(ns dk.simongray.qname-hiccup-test
  (:require #?(:cljs [dk.simongray.qname-hiccup.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.qname-hiccup :as xml]
            [dk.simongray.qname-hiccup.serializer :as serializer]))

(defn bytes-of
  "The bytes `xs`, numbers from 0 to 255, as the platform keeps bytes."
  [xs]
  #?(:clj  (byte-array (map unchecked-byte xs))
     :cljs (js/Uint8Array.from (into-array xs))))

(defn utf-8
  "The text `s` as the bytes of its UTF-8."
  [s]
  #?(:clj  (.getBytes ^String s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(def sample
  "<?xml version=\"1.0\"?><!-- c --><rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"
   xmlns=\"http://www.w3.org/2005/Atom\" xmlns:acme=\"https://acme.example\" version=\"2.0\">
   <channel><title>T &amp; U</title><itunes:author>A</itunes:author><acme:thing acme:mode=\"on\"/>
   <description><![CDATA[<p>Hi</p>]]> tail</description></channel></rss>")

(def itunes
  "The namespace of the iTunes tags, which sample uses."
  "http://www.itunes.com/dtds/podcast-1.0.dtd")

(def undeclared
  "A prefix used without a declaration: fine for the non-namespace-aware JVM
  parser, an error for a browser's DOMParser, so only the JVM sees it."
  "<rss version=\"2.0\"><channel><foo:bar x=\"1\"/></channel></rss>")

(deftest raw-parsing
  (let [raw (xml/parse-raw sample)]
    (is (= "rss" (first raw)))
    (is (= {"xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"
            "xmlns" "http://www.w3.org/2005/Atom"
            "xmlns:acme" "https://acme.example"
            "version" "2.0"}
           (second raw)))
    (is (= ["title" {} "T & U"] (-> raw (nth 2) (nth 2))))
    (testing "CDATA and text merge, comments vanish"
      (is (= ["description" {} "<p>Hi</p> tail"] (-> raw (nth 2) (nth 5))))))
  (testing "a space between two elements on one line is kept, layout is not"
    (is (= ["p" {} ["b" {} "Hello"] " " ["i" {} "world"]] (xml/parse-raw "<p><b>Hello</b> <i>world</i></p>")))
    (is (= ["p" {} ["b" {} "Hello"] ["i" {} "world"]] (xml/parse-raw "<p>\n  <b>Hello</b>\n  <i>world</i>\n</p>")))))

(deftest white-space-kept
  (let [mixed "<p><b>Hello</b>\n<i>world</i> <code> </code></p>"]
    (is (= ["p" {} ["b" {} "Hello"] ["i" {} "world"] " " ["code" {}]] (xml/parse-raw mixed)))
    (is (= ["p" {} ["b" {} "Hello"] "\n" ["i" {} "world"] " " ["code" {} " "]]
           (xml/parse-raw mixed {:whitespace :keep}))
        "all of it on request, as XML passes it on"))
  (testing "where xml:space asks for it, whatever the option"
    (is (= ["doc" {} ["pre" {"xml:space" "preserve"} "\n" ["b" {} " x "] "\n"] ["p" {} ["b" {} "y"]]]
           (xml/parse-raw "<doc>\n<pre xml:space=\"preserve\">\n<b> x </b>\n</pre>\n<p>\n<b>y</b>\n</p>\n</doc>"))
        "on the element and its descendants alone")
    (is (= ["doc" {"xml:space" "preserve"} ["p" {"xml:space" "default"} ["b" {} "y"]] "\n"]
           (xml/parse-raw "<doc xml:space=\"preserve\"><p xml:space=\"default\">\n<b>y</b>\n</p>\n</doc>"))
        "until an element sets it back to the default")))

(deftest hiccup-as-a-renderer-takes-it
  (let [feed [:feed [:title "Emma"] (for [i [1 2]] [:entry [:id i]]) nil]]
    (is (= (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                "<feed>\n  <title>Emma</title>\n  <entry>\n    <id>1</id>\n  </entry>\n"
                "  <entry>\n    <id>2</id>\n  </entry>\n</feed>\n")
           (xml/emit feed {}))
        "without attribute maps, with a sequence spliced in, nil left out and a number as its text")
    (is (= "Emma" (xml/text [:title "Emma"])))
    (is (= {} (xml/attrs [:title "Emma"])))
    (is (= [[:id {} "1"]] (xml/elements [:entry [:id {} "1"]])))
    (is (= "<b>x</b> y" (xml/inner-xml [:p [:b "x"] " y"]))))
  (let [canonical (xml/parse sample)]
    (is (identical? canonical (serializer/normalized canonical))
        "a canonical tree is written as it is, not copied")))

(def atom-ns
  "The namespace of Atom, which the tests of default namespaces use."
  "http://www.w3.org/2005/Atom")

(deftest namespace-resolution
  (let [tree (xml/parse sample {itunes :itunes})]
    (is (= [:rss {:version "2.0" :xmlns "http://www.w3.org/2005/Atom"}] (subvec tree 0 2)))
    (is (= [:itunes/author {} "A"] (-> tree (nth 2) (nth 3))) "by the alias of the table")
    (testing "in a default namespace that the table lacks, names stay plain keywords"
      (is (= [:title {} "T & U"] (-> tree (nth 2) (nth 2)))))
    (testing "unknown URIs keep their prefix as alias"
      (is (= [:acme/thing {:acme/mode "on"}] (-> tree (nth 2) (nth 4)))))
    (is (= #{{:id :xml/default-namespace :level :info :uri "http://www.w3.org/2005/Atom"}
             {:id :xml/unknown-namespace :level :info :prefix "acme" :uri "https://acme.example"}}
           (set (xml/report tree))))
    (is (= {:acme "https://acme.example"} (xml/namespaces tree))))
  (testing "without a table, every prefix keeps its own alias"
    (let [tree (xml/parse sample)]
      (is (= [:itunes/author {} "A"] (-> tree (nth 2) (nth 3))))
      (is (= {:itunes itunes :acme "https://acme.example"} (xml/namespaces tree)))))
  (testing "a function as the table, which gives the rest their URIs"
    (let [uri->alias #(get {itunes :itunes} % %)
          tree       (xml/parse sample uri->alias)]
      (is (= (keyword atom-ns "rss") (xml/tag tree)) "the default namespace too")
      (is (= [:itunes/author {} "A"] (-> tree (nth 2) (nth 3))))
      (is (= [(keyword "https://acme.example" "thing") {(keyword "https://acme.example" "mode") "on"}]
             (-> tree (nth 2) (nth 4))))
      (is (= {:itunes itunes} (:xml/declared (meta tree))) "under their keyword aliases alone")
      (is (= tree (xml/parse (xml/emit tree) uri->alias)) "and written back")
      (testing "while the empty URI is no namespace"
        (is (= [(keyword atom-ns "feed") {} [:entry {:xmlns ""}]]
               (xml/parse (str "<feed xmlns=\"" atom-ns "\"><entry xmlns=\"\"/></feed>")
                          uri->alias)))))))

(deftest default-namespaces
  (testing "an element name without a prefix takes its alias, an attribute name doesn't"
    (is (= [:atom/feed {:xml/lang "en"} [:atom/title {:type "text"} "T"]]
           (xml/parse (str "<feed xmlns=\"" atom-ns "\" xml:lang=\"en\">"
                           "<title type=\"text\">T</title></feed>")
                      {atom-ns :atom}))))
  (testing "so a prefix and the default namespace give the same names"
    (is (= (xml/parse (str "<a:feed xmlns:a=\"" atom-ns "\"><a:title>T</a:title></a:feed>")
                      {atom-ns :atom})
           (xml/parse (str "<feed xmlns=\"" atom-ns "\"><title>T</title></feed>")
                      {atom-ns :atom}))))
  (testing "one declared on a child applies there only"
    (is (= [:rss {} [:atom/link {:href "x"}] [:link {} "y"]]
           (xml/parse (str "<rss><link xmlns=\"" atom-ns "\" href=\"x\"/><link>y</link></rss>")
                      {atom-ns :atom}))))
  (testing "an empty one ends it, and stays"
    (is (= [:atom/feed {} [:entry {:xmlns ""}]]
           (xml/parse (str "<feed xmlns=\"" atom-ns "\"><entry xmlns=\"\"/></feed>")
                      {atom-ns :atom}))))
  (testing "one that the table lacks stays on its element, and is written back"
    (let [tree (xml/parse (str "<rss><channel><site xmlns=\"com-wordpress:feed-additions:1\">"
                               "123</site></channel></rss>"))]
      (is (= [:site {:xmlns "com-wordpress:feed-additions:1"} "123"] (-> tree (nth 2) (nth 2))))
      (is (= [{:id :xml/default-namespace :level :info :uri "com-wordpress:feed-additions:1"}]
             (xml/report tree)))
      (is (= tree (xml/parse (xml/emit tree))))))
  (testing "a known one is written back under its alias"
    (let [tree (xml/parse (str "<feed xmlns=\"" atom-ns "\"><title>T</title></feed>")
                          {atom-ns :atom})
          out  (xml/emit tree {:atom atom-ns})]
      (is (re-find #"<atom:feed xmlns:atom=" out))
      (is (= tree (xml/parse out {atom-ns :atom})))
      (testing "and the metadata has it, so that it's written back without a table"
        (is (= {:atom atom-ns} (:xml/declared (meta tree))))
        (is (= out (xml/emit tree)))))))

(def dc
  "The namespace of Dublin Core, which the tests of default namespaces use."
  "http://purl.org/dc/elements/1.1/")

(deftest emitting-a-default-namespace
  (testing "the elements of the alias have no prefix"
    (is (= (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                "<feed xmlns=\"" atom-ns "\">\n  <title>T</title>\n</feed>\n")
           (xml/emit [:atom/feed [:atom/title "T"]] {:atom atom-ns} {:default-namespace :atom}))))
  (testing "a plain element undeclares it, and one of the alias below declares it again"
    (let [tree [:atom/feed {} [:atom/entry {} [:x {} [:atom/id {} "1"]]] [:dc/creator {} "A"]]
          out  (xml/emit tree {:atom atom-ns :dc dc} {:default-namespace :atom})]
      (is (re-find #"<x xmlns=\"\">" out))
      (is (re-find (re-pattern (str "<id xmlns=\"" atom-ns "\">1</id>")) out))
      (is (re-find #"<dc:creator>A</dc:creator>" out))
      (let [back (xml/parse out {atom-ns :atom dc :dc})]
        (is (= (assoc-in tree [2 2 1] {:xmlns ""}) back) "the empty one stays, as a parse keeps it")
        (is (= out (xml/emit back nil {:default-namespace :atom}))))))
  (testing "a default namespace that the table lacks stays where it was"
    (let [tree (xml/parse (str "<feed xmlns=\"" atom-ns "\"><site xmlns=\"com-wordpress:feed-additions:1\">"
                               "<a>1</a></site></feed>")
                          {atom-ns :atom})
          out  (xml/emit tree nil {:default-namespace :atom})]
      (is (not (re-find #"xmlns=\"\"" out)))
      (is (= tree (xml/parse out {atom-ns :atom})))))
  (testing "an attribute of the alias keeps its prefix, since attributes have no default namespace"
    (let [tree [:atom/feed {:atom/x "1"} [:atom/title {} "T"]]
          out  (xml/emit tree {:atom atom-ns} {:default-namespace :atom})]
      (is (re-find #" atom:x=\"1\"" out))
      (is (re-find #" xmlns:atom=" out))
      (is (= tree (xml/parse out {atom-ns :atom}))))))

#?(:clj
   (deftest undeclared-prefixes-survive-on-the-jvm
     (let [tree (xml/parse undeclared)]
       (is (= [:foo/bar {:x "1"}] (-> tree (nth 2) (nth 2))))
       (is (= [{:id :xml/undeclared-prefix :level :warning :prefix "foo"}]
              (xml/report tree)))
       (is (= {:foo nil} (xml/namespaces tree)) "recorded with no URI")
       (is (not (re-find #"foo" (xml/emit tree))) "so that emit leaves it out"))))

(deftest decoding-a-document
  (let [doc    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><t>Blåbær</t>"
        latin  (bytes-of (map #?(:clj int :cljs #(.charCodeAt % 0)) doc))
        utf    (utf-8 (str/replace doc "ISO-8859-1" "UTF-8"))]
    (is (= doc (xml/decode latin)) "by the declaration")
    (is (= (str/replace doc "ISO-8859-1" "UTF-8") (xml/decode utf)))
    (is (= (str/replace doc "ISO-8859-1" "UTF-8") (xml/decode utf "UTF-8")) "by the charset given")
    (is (= "<a/>" (xml/decode (bytes-of [0xef 0xbb 0xbf 0x3c 0x61 0x2f 0x3e]) "ISO-8859-1"))
        "by the byte order mark first, which is left out")
    (is (= "<a/>" (xml/decode (utf-8 "<a/>") "nonsense")) "an unknown charset counts as UTF-8")
    (is (= doc (xml/decode latin "nonsense")) "and gives way to the declaration")
    (is (= "<t>“hi”</t>" (xml/decode (bytes-of [0x3c 0x74 0x3e 0x93 0x68 0x69 0x94 0x3c 0x2f 0x74 0x3e])
                                     "iso-8859-1"))
        "a label of Latin-1 as windows-1252, as in a browser")))

(deftest helpers
  (let [el [:a {:x "1"} "t1" [:b {} "inner"] "t2" [:c {}] [:b {} "again"]]]
    (is (= :a (xml/tag el)))
    (is (= {:x "1"} (xml/attrs el)))
    (is (= "t1t2" (xml/text el)))
    (is (= 3 (count (xml/elements el))))
    (is (= 2 (count (xml/elements el :b))))
    (is (= [:b {} "inner"] (xml/element el :b))))
  (testing "a path of tags, one for each level"
    (let [rss [:rss {} [:channel {} [:title {} "T"] [:item {} [:title {} "1"]] [:item {} [:title {} "2"]]]]]
      (is (= [[:title {} "1"] [:title {} "2"]] (xml/elements-in rss [:channel :item :title])))
      (is (= [:title {} "T"] (xml/element-in rss [:channel :title])))
      (is (nil? (xml/element-in rss [:channel :link])))
      (is (= [rss] (xml/elements-in rss [])) "an empty path is the element itself")))
  (is (= "Show" (xml/trim "\u00A0 Show\t\u00A0")) "no-break spaces are white space on both platforms")
  (is (= "a b" (xml/trim "a b"))))

(deftest emitting
  (testing "declarations for the aliases used, foreign ones included, and the default namespace"
    (let [out (xml/emit (xml/parse sample))]
      (is (re-find #"xmlns:acme=\"https://acme.example\"" out))
      (is (re-find #"xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\"" out))
      (is (re-find #"xmlns=\"http://www.w3.org/2005/Atom\"" out))))
  (testing "escaping and CDATA"
    (is (re-find #"<title>T &amp; U</title>" (xml/emit [:rss {} [:title {} "T & U"]])))
    (is (re-find #"<!\[CDATA\[<p>Hi</p>\]\]>" (xml/emit [:rss {} [:d {} "<p>Hi</p>"]])))
    (is (re-find #"x=\"a &quot;q&quot; &amp; b\"" (xml/emit [:rss {:x "a \"q\" & b"}]))))
  (testing "an unknown alias without a URI is an error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                 (xml/emit [:rss {} [:foo/bar {}]]))))
  (testing "a URI as a keyword namespace gets a prefix of its own, which no alias has"
    (let [tree [(keyword "DAV:" "prop") {}
                [(keyword "http://x/" "a") {(keyword "http://x/" "b") "1"} "v"]
                [:ns1/c {}]]
          out  (xml/emit tree {:ns1 "urn:c"})]
      (is (re-find #"xmlns:ns2=\"DAV:\"" out))
      (is (re-find #"xmlns:ns3=\"http://x/\"" out))
      (is (re-find #"<ns3:a ns3:b=\"1\">v</ns3:a>" out))
      (is (= tree (xml/parse out #(get {"urn:c" :ns1} % %)))))
    (testing "in a declaration, where its markup is escaped"
      (let [tree [(keyword "http://x/\"><y/><z a=\"" "a") {}]]
        (is (= tree (xml/parse (xml/emit tree) identity))))))
  (testing "a name that isn't an XML name is an error, so that no name adds markup"
    (let [table {:a "urn:a" (keyword "a b") "urn:b"}]
      (doseq [k [(keyword "http://x/" "a/></x:prop><x:remove>")
                 (keyword "a b")
                 (keyword "1a")
                 (keyword "a" "b:c")
                 (keyword "a b" "c")]]
        (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an XML name"
                              (xml/emit [:rss {} [k {}]] table))
            (str k))
        (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an XML name"
                              (xml/emit [:rss {k "1"}] table))
            (str k))))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Not an XML name"
                          (xml/inner-xml [:p {} [(keyword "http://x/" "a") {}]]))
        "nor is a URI in inner-xml, which gives it no prefix"))
  (testing "a name beyond ASCII is written"
    (let [tree [:é {:a·b "1" :_x.y-z "2"}]]
      (is (= tree (xml/parse (xml/emit tree)))))
    (is (serializer/ncname? "a𐀀") "#x10000 too, as a pair of surrogates")
    (is (not (serializer/ncname? "a\uD800"))))
  (testing "round trip"
    (let [tree (xml/parse "<rss version=\"2.0\"><channel><title>A</title><item><guid isPermaLink=\"false\">x</guid></item></channel></rss>")]
      (is (= tree (xml/parse (xml/emit tree))))))
  (testing "whitespace a parser would normalize goes out as character references"
    (let [tree [:rss {:x "a\nb\tc\rd"} [:t {} "line\r\nnext <b>"]]
          out  (xml/emit tree)]
      (is (re-find #"x=\"a&#10;b&#9;c&#13;d\"" out))
      (is (re-find #"<t>line&#13;\nnext &lt;b&gt;</t>" out))
      (is (= tree (xml/parse out)))))
  (testing "inner-xml escapes text for HTML, and never wraps it in CDATA"
    (is (= "Use &lt;pre&gt; &amp; <b>x &lt; y</b>"
           (xml/inner-xml [:description {} "Use <pre> & " [:b {} "x < y"]]))))
  (testing "an alias mapped to nil has no URI, and its elements and attributes are left out"
    (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rss>\n  <t/>\n</rss>\n"
           (xml/emit [:rss {} [:t {:fb/x "1"}] [:fb/link {} "x"]] {:fb nil}))))
  (testing "mixed content is written as it is, so that a round trip keeps it"
    (let [tree (xml/parse "<rss><x>Hello <b>big</b> <i>world</i>!</x></rss>")]
      (is (= tree (xml/parse (xml/emit tree))))
      (is (= tree (xml/parse (xml/emit (xml/parse (xml/emit tree)))))))))

#?(:clj
   (deftest long-text-nodes-on-the-jvm
     (let [s (apply str (repeat 100001 "x"))]
       (is (= s (xml/text (xml/parse (str "<a>" s "</a>"))))))
     (let [s (apply str (repeat 150000 "&amp;"))]
       (is (= 150000 (count (xml/text (xml/parse (str "<a>" s "</a>")))))
           "the JDK counts each reference towards its limits on entities"))))

(defn malformed?
  "Whether parsing the document `s` with the `opts` throws ::xml/malformed."
  ([s]
   (malformed? s {}))
  ([s opts]
   (= ::xml/malformed
      (try (xml/parse s {} opts) nil
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
             (:type (ex-data e)))))))

(defn laughs
  "A document of nested entities, each ten of the one before, down to the
  text `s`."
  [s]
  (str "<!DOCTYPE lolz [<!ENTITY lol0 \"" s "\">"
       (apply str (for [i (range 1 10)]
                    (str "<!ENTITY lol" i " \"" (apply str (repeat 10 (str "&lol" (dec i) ";"))) "\">")))
       "]><lolz>&lol9;</lolz>"))

#?(:clj
   (deftest dtds-on-the-jvm
     (testing "the internal subset declares entities"
       (is (= [:a {} "hello world"] (xml/parse "<!DOCTYPE a [<!ENTITY e \"hello\">]><a>&e; world</a>"))))
     (testing "an XHTML document has the entities of XHTML"
       (is (= [:html {} [:p {} "a b—c"]]
              (xml/parse (str "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\""
                              " \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">"
                              "<html><p>a&nbsp;b&mdash;c</p></html>")))))
     (testing "an entity of another external DTD, which is never read, is left out, as a browser does"
       (let [tree (xml/parse "<!DOCTYPE book SYSTEM \"docbook.dtd\"><book>A &product; B &product;</book>")]
         (is (= [:book {} "A  B "] tree))
         (is (= [{:id :xml/skipped-entity :level :warning :entity "product"}] (xml/report tree)))))
     (testing "but one that no DTD could declare is malformed"
       (is (malformed? "<a>&nbsp;</a>"))
       (is (malformed? "<!DOCTYPE a [<!ENTITY e \"x\">]><a>&nbsp;</a>")))
     (testing "an external entity is never read"
       (is (= [:a {}] (xml/parse "<!DOCTYPE a [<!ENTITY s SYSTEM \"file:///etc/passwd\">]><a>&s;</a>"))))
     (testing "entities that expand into gigabytes are malformed"
       (is (malformed? (laughs "lol")))
       (is (malformed? (laughs "")) "even when they expand to nothing")
       (is (malformed? (str "<!DOCTYPE a [<!ENTITY x \"" (apply str (repeat 50000 "x")) "\">]>"
                            "<a>" (apply str (repeat 1000 "&x;")) "</a>"))))
     (testing "with DOCTYPEs refused, nothing limits the references, since no entity can be declared"
       (let [s (str "<a>" (apply str (repeat 10000001 "&gt;")) "</a>")]
         (is (= 10000001 (count (xml/text (xml/parse s {} {:doctype :refuse})))))))))

(deftest refusing-a-doctype
  (let [default-laughs (str/replace (laughs "lol") "]><lolz>&lol9;</lolz>"
                                    "<!ATTLIST lolz x CDATA \"&lol9;\">]><lolz/>")]
    (testing "a DOCTYPE is read by default"
      (is (= [:a {} "x"] (xml/parse "<!DOCTYPE a><a>x</a>")))
      #?(:clj (is (malformed? default-laughs) "within the limits on entities, in a default value too")))
    (testing "and on request, a document with one is malformed, whatever it declares"
      (doseq [s ["<!DOCTYPE a><a>x</a>"
                 "<?xml version=\"1.0\"?><!-- c --><?pi x?><!DOCTYPE a SYSTEM \"a.dtd\"><a/>"
                 "<!DOCTYPE a [<!ENTITY e \"x\">]><a>&e;</a>"
                 (laughs "lol")
                 default-laughs]]
        (is (malformed? s {:doctype :refuse}) s)))
    (testing "while one without is read as ever"
      (is (= [:a {} "x > y"] (xml/parse "<a>x &gt; y</a>" {} {:doctype :refuse}))))))

(deftest malformed
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"Malformed XML"
                        (xml/parse "<rss><channel></rss>")))
  (is (= ::xml/malformed
         (try (xml/parse "nope")
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                (:type (ex-data e))))))
  (testing "markup or text after the root is an error, a comment is not"
    (doseq [s ["<rss/><b>Warning</b>" "<rss/>junk"]]
      (is (= ::xml/malformed
             (try (xml/parse s)
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                    (:type (ex-data e)))))
          s))
    (is (= [:rss {}] (xml/parse "<rss/>\n<!-- served in 0.2 s -->\n"))))
  #?(:cljs (is (malformed? undeclared)
               "a prefix that no declaration names, which the JVM reports"))
  (testing "a prefix bound to no namespace, which Namespaces in XML forbids"
    (is (malformed? "<a xmlns:b=\"\"><b:c/></a>")))
  #?(:clj
     (testing "and so is an XML declaration the parser does not read"
       (is (= ::xml/malformed
              (try (xml/parse "<?xml version=\"2.0\"?><rss/>")
                   (catch clojure.lang.ExceptionInfo e
                     (:type (ex-data e)))))))))

#?(:cljs
   (deftest without-a-dom-parser
     (let [parser js/DOMParser]
       (set! (.-DOMParser js/globalThis) js/undefined)
       (try
         (is (= ::xml/no-parser
                (try (xml/parse "<a/>") nil
                     (catch ExceptionInfo e
                       (:type (ex-data e)))))
             "a platform without a parser is no fault of the XML")
         (finally
           (set! (.-DOMParser js/globalThis) parser))))))

(deftest bom
  (is (= [:a {}] (xml/parse "﻿<a/>"))))

(deftest nesting-depth
  (let [nested (fn [n] (str (apply str (repeat n "<a>")) (apply str (repeat n "</a>"))))
        depth  (fn depth [x] (if (vector? x) (inc (reduce max 0 (map depth (xml/elements x)))) 0))]
    (testing "a document nested as deep as max-depth is read"
      (is (= xml/max-depth (depth (xml/parse (nested xml/max-depth))))))
    (testing "a deeper one is malformed, before any walk of it can overflow the stack"
      (doseq [n [(inc xml/max-depth) 10000]]
        (is (= ::xml/malformed
               (try (xml/parse (nested n)) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                      (:type (ex-data e))))))))
    (testing "a deep document is written back indented at every level"
      (let [tree (xml/parse (nested 40))
            out  (xml/emit tree)]
        (is (re-find #"\n {78}<a/>\n" out))
        (is (= tree (xml/parse out)))))))

(deftest options
  (testing "the :max-depth of the options"
    (is (= [:a {} [:b {}]] (xml/parse "<a><b/></a>" {} {:max-depth 2})))
    (is (= ::xml/malformed
           (try (xml/parse-raw "<a><b><c/></b></a>" {:max-depth 2}) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                  (:type (ex-data e)))))))
  (testing "the :indent of the options"
    (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<a>\n\t<b/>\n</a>\n"
           (xml/emit [:a {} [:b {}]] {} {:indent "\t"})))
    (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a><b>x</b><c/></a>"
           (xml/emit [:a {} [:b {} "x"] [:c {}]] {} {:indent false}))
        "false for one line"))
  (testing "an option given as nil has its default"
    (is (= (xml/emit [:a {} [:b {}]])
           (xml/emit [:a {} [:b {}]] nil {:indent nil})))))
