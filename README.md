qname-hiccup
============

This is a Clojure and ClojureScript library for reading XML into Hiccup
and writing it back. It matches namespaces by their URI rather than by
the prefix that a document happens to use: you give each namespace you
care about an alias once, and its names come out as keywords under that
alias, whatever prefix the document wrote.

- **Your aliases, not the document's.** With the alias `:dc` for Dublin
  Core, `<dc:creator>` and `<dublin:creator>` come out as `:dc/creator`,
  and so does `<creator>` where Dublin Core is the default namespace.
- **Back to XML.** A namespace that isn't in your table keeps the
  document's prefix, or stays on its element if it's a default namespace,
  so writing the tree back declares it again.
- **Safe on untrusted input.** On the JVM, DTDs and external entities
  are off, so XXE and entity expansion attacks don't work. On every
  platform, nesting has a depth limit.
- **Bytes read as a browser reads them.** The encoding comes from the
  byte order mark, then from a charset you give, e.g. that of a
  Content-Type header, and then from the XML declaration.
- **No dependencies.** It uses the platform's own parser: StAX on the JVM
  and DOMParser in JavaScript.

The same code runs on the JVM, in Node and in the browser, with the same
results on each. If you only need the names as a document writes them,
[xml-hiccup](https://github.com/kuhumcst/xml-hiccup) is smaller.

> This library was spun out of [podcast-clj](https://github.com/simongray/podcast-clj),
> a library for making podcast software, where it reads and writes RSS
> feeds and OPML files. Like podcast-clj, it was developed with
> assistance from frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. Add it to the `:deps` in your
`deps.edn` as a Git dependency, with the SHA of the latest commit on
`master`:

```clojure
dk.simongray/qname-hiccup
{:git/url "https://github.com/simongray/qname-hiccup"
 :git/sha "…"}
```

For ClojureScript, also set `:deps true` in your `shadow-cljs.edn`, since
shadow-cljs only reads Git dependencies from `deps.edn`. Browsers have a
DOMParser. In Node, install one as `globalThis.DOMParser`, e.g. the one
from @xmldom/xmldom.

Parse a document with a table of the namespaces you know, from URI to
alias:

```clojure
(require '[dk.simongray.qname-hiccup :as xml])

(def dc
  "http://purl.org/dc/elements/1.1/")

(def book
  (xml/parse "<book xmlns:dublin=\"http://purl.org/dc/elements/1.1/\"
                    xmlns:shelf=\"https://example.org/shelf\" id=\"1\">
                <title>Emma</title>
                <dublin:creator>Jane Austen</dublin:creator>
                <shelf:spot row=\"3\">Fiction</shelf:spot>
              </book>"
             {dc :dc}))

book
;; => [:book {:id "1"}
;;     [:title {} "Emma"]
;;     [:dc/creator {} "Jane Austen"]
;;     [:shelf/spot {:row "3"} "Fiction"]]
```

The shelf namespace isn't in the table, so it keeps the document's
prefix, and the tree's metadata records its URI:

```clojure
(xml/namespaces book)
;; => {:shelf "https://example.org/shelf"}
```

A default namespace is looked up in the table too, so element names
without a prefix come out under its alias. If the table lacks it, the
names stay plain keywords, and the declaration stays on its element as
the attribute `:xmlns`, so it's written back where it was.

The metadata also holds a report of what the parse found, e.g. the
unknown shelf namespace or a prefix that no declaration names. Get it
with `xml/report`.

To write the tree back, give `xml/emit` a map of each alias in the tree
to its URI. It declares those namespaces on the root element:

```clojure
(println (xml/emit book (assoc (xml/namespaces book) :dc dc)))
;; <?xml version="1.0" encoding="UTF-8"?>
;; <book id="1" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:shelf="https://example.org/shelf">
;;   <title>Emma</title>
;;   <dc:creator>Jane Austen</dc:creator>
;;   <shelf:spot row="3">Fiction</shelf:spot>
;; </book>
```

Without the map, it uses the namespaces in the tree's metadata. If the
tree uses an alias that the map lacks, `xml/emit` throws. An alias
mapped to nil is dropped, along with its elements and attributes.

Reading a tree
--------------

The functions `xml/tag`, `xml/attrs`, `xml/children`, `xml/elements`,
`xml/element`, `xml/text` and `xml/deep-text` read the Hiccup, e.g.:

```clojure
(xml/text (xml/element book :dc/creator))
;; => "Jane Austen"
```

Text isn't trimmed. Use `xml/trim` to trim it the same way on every
platform: like JavaScript's trim, and unlike Java's, it removes no-break
spaces too.

For more control, parse in two steps: `xml/parse-raw` gives Hiccup with
the names as written, as strings, and `xml/resolve-namespaces` resolves
them, which also works on the raw Hiccup of another parser. Use
`xml/decode` to turn the bytes of a document into text, and
`xml/inner-xml` to write the content of an element as markup, e.g. HTML
that a document embeds as XML.

What a tree keeps
-----------------

A tree keeps the elements, attributes and text of a document, with these
changes:

- Comments and processing instructions are dropped.
- CDATA becomes text, joined to the text around it.
- Text of only white space is dropped, except a space between two
  elements on one line, which inline markup needs.

Parsing a document that isn't well-formed, or that nests deeper than the
`:max-depth` option, throws an ex-info with the `:type`
`:dk.simongray.qname-hiccup/malformed`.

Options
-------

The functions `xml/parse`, `xml/parse-raw` and `xml/emit` take a map of
options as their last argument, which you can leave out:

- `:max-depth`, how deep elements may nest, 256 by default
- `:indent`, what each level is indented by when written, two spaces by
  default

```clojure
(print (xml/emit [:book {} [:title {} "Emma"]] {} {:indent "\t"}))
;; <?xml version="1.0" encoding="UTF-8"?>
;; <book>
;; 	<title>Emma</title>
;; </book>
```

The defaults are in `xml/default-options`.

Lenient parsing
---------------

On the JVM, `dk.simongray.qname-hiccup.jsoup/parse-raw` uses the XML mode
of jsoup to read documents that aren't well-formed, so unclosed tags and
unquoted attributes still give a tree. It takes the `:max-depth` option
too. Add `org.jsoup/jsoup` to your dependencies to use it:

```clojure
(require '[dk.simongray.qname-hiccup.jsoup :as jsoup])

(xml/resolve-namespaces
 (jsoup/parse-raw "<book xmlns:dublin=\"http://purl.org/dc/elements/1.1/\">
                     <title>A <3 B</title><dublin:creator>Jane</book>")
 {dc :dc})
;; => [:book {} [:title {} "A <3 B"] [:dc/creator {} "Jane"]]
```

Principles
----------

- **Standards first.** The code follows XML 1.0, Namespaces in XML 1.0
  and RFC 7303, and cites their sections.
- **Strict unless you ask.** The parser rejects what isn't well-formed,
  and the lenient parser is a separate one that you choose.
- **One codebase.** Apart from the JVM-only jsoup parser, the library is
  written in `.cljc`, with no dependencies but Clojure, and its text
  helpers give the same results on both platforms.

Development
-----------

```bash
clojure -X:test               # the tests on the JVM
npm install                   # once, for the Node tests
clojure -M:cljs compile test  # the tests in Node
```

License
-------

The qname-hiccup project is licensed under the [MIT licence](LICENSE).
