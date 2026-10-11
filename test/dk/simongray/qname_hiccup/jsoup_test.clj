(ns dk.simongray.qname-hiccup.jsoup-test
  "The lenient jsoup adapter; JVM only, since jsoup is."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.qname-hiccup :as xml]
            [dk.simongray.qname-hiccup.jsoup :as jsoup]))

(deftest lenient-raw-parsing
  (let [raw (jsoup/parse-raw "<rss version=2.0 xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">
                               <channel><title>A <3 B</title><itunes:author>X</itunes:author>
                               <enclosure url=https://a/1.mp3 type=audio/mpeg /></channel></rss>")]
    (is (= "rss" (first raw)))
    (is (= {"version" "2.0" "xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"} (second raw)))
    (is (= ["title" {} "A <3 B"] (-> raw (nth 2) (nth 2))))
    (is (= ["itunes:author" {} "X"] (-> raw (nth 2) (nth 3))))
    (is (= ["enclosure" {"url" "https://a/1.mp3" "type" "audio/mpeg"}] (-> raw (nth 2) (nth 4)))))
  (testing "mismatched and unclosed tags still give a tree"
    (is (= ["a" {} ["b" {} "x" ["c" {} "y"]]] (jsoup/parse-raw "<a><b>x<c>y</b></a>"))))
  (testing "no element at all is still an error"
    (is (= ::xml/malformed (try (jsoup/parse-raw "just text")
                                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
  (testing "and so is a document nested deeper than the :max-depth of the options"
    (is (= ::xml/malformed (try (jsoup/parse-raw "<a><b><c/></b></a>" {:max-depth 2})
                                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
  (testing "white space as xml/parse-raw keeps it"
    (is (= ["p" {} ["b" {} "x"] "\n" ["i" {} "y"]] (jsoup/parse-raw "<p><b>x</b>\n<i>y</i></p>" {:whitespace :keep})))
    (is (= ["p" {"xml:space" "preserve"} ["b" {} "x"] "\n" ["i" {} "y"]]
           (jsoup/parse-raw "<p xml:space=\"preserve\"><b>x</b>\n<i>y</i></p>")))))
