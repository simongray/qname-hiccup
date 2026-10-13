qname-hiccup
============

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/qname-hiccup.svg)](https://clojars.org/dk.simongray/qname-hiccup)

This is a Clojure and ClojureScript library for reading XML into Hiccup
and writing it back. It matches namespaces by their URI rather than by
the prefix that a document happens to use: you give each namespace you
care about an alias once, and its names come out as keywords under that
alias, whatever prefix the document wrote.

**NOTE:** If you only need the names as a document writes them,
[xml-hiccup](https://github.com/kuhumcst/xml-hiccup) is smaller.

- **Your aliases, not the document's.** With the alias `:dc` for Dublin
  Core, `<dc:creator>` and `<dublin:creator>` come out as `:dc/creator`,
  and so does `<creator>` where Dublin Core is the default namespace.
- **Back to XML.** A namespace that isn't in your table keeps the
  document's prefix, or stays on its element if it's a default namespace,
  so writing the tree back declares it again.
- **Safe on untrusted input.** On the JVM, no external DTD or entity is
  read, so XXE attacks don't work, and entities can't expand into
  gigabytes. On every platform, nesting has a depth limit.
- **Bytes read as a browser reads them.** The encoding comes from the
  byte order mark, then from a charset you give, e.g. that of a
  Content-Type header, and then from the XML declaration. A label means
  what it means in a browser, e.g. latin1 is windows-1252.
- **The platform's own parser.** It uses StAX on the JVM and DOMParser in
  JavaScript. Its only dependency is
  [whatwg-mime](https://github.com/simongray/whatwg-mime), which decodes
  the bytes.

The same code runs on the JVM, in Node and in the browser, with the same
results on each.

> This library was spun out of [podcast-clj](https://github.com/simongray/podcast-clj),
> a library for making podcast software, where it reads and writes RSS
> feeds and OPML files. Like podcast-clj, it was developed with
> assistance from frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. For the latest release, add it
from [Clojars](https://clojars.org/dk.simongray/qname-hiccup) to the
`:deps` in your `deps.edn`:

```clojure
dk.simongray/qname-hiccup {:mvn/version "0.4.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead:

```clojure
dk.simongray/qname-hiccup
{:git/url "https://github.com/simongray/qname-hiccup"
 :git/sha "…"}
```

For ClojureScript, shadow-cljs only reads Git dependencies from
`deps.edn`, so also set `:deps true` in your `shadow-cljs.edn`.

In ClojureScript, it parses XML with a DOMParser, which browsers have. In
Node, install one as `globalThis.DOMParser`, e.g. the one from
@xmldom/xmldom.

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

Without the map, it uses the namespaces in the tree's metadata: those
that the table lacks, and under `:xml/declared` those it has, so
`(xml/emit book)` writes the same document. If the tree uses an alias
that the map lacks, `xml/emit` throws. An alias mapped to nil is
dropped, along with its elements and attributes.

You can also write the Hiccup yourself, e.g. a sitemap or an Atom feed,
as every Hiccup renderer takes it: an element can leave out its
attribute map, as in `[:title "Emma"]`, a sequence of children is
spliced in, nil is left out, and a number is written as its text.

Reading a tree
--------------

The functions `xml/tag`, `xml/attrs`, `xml/children`, `xml/elements`,
`xml/element`, `xml/text` and `xml/deep-text` read the Hiccup, e.g.:

```clojure
(xml/text (xml/element book :dc/creator))
;; => "Jane Austen"
```

To go down more than one level, give `xml/element-in` or
`xml/elements-in` a path of tags, e.g. `[:channel :item]` for the items
of an RSS feed.

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
  elements on one line, which inline markup needs. The option
  `:whitespace :keep` keeps it all, and so does `xml:space="preserve"` on
  an element, for the element and what it holds.
- The entities of the DOCTYPE are expanded: those it declares itself,
  and in an XHTML document those of XHTML, as a browser does. No
  external DTD is read, so an entity that only one could declare is left
  out, and on the JVM `xml/report` names it. In Node, xmldom reads no
  DOCTYPE, so any entity but `&amp;` and the others of XML fails.

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
- `:whitespace`, `:compact` to drop text of only white space, as above,
  or `:keep`
- `:default-namespace`, an alias whose elements are written without a
  prefix, in the default namespace, e.g. `:atom` for
  `<feed xmlns="http://www.w3.org/2005/Atom">`

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
  written in `.cljc`, and its text helpers give the same results on both
  platforms.

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
