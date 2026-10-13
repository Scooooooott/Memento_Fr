# Third-party lexical content

The bundled French content database includes short dictionary facts derived from
the following open resources. The generated source file records the applicable
reference IDs per lexeme in `provenance_json`.

## Wiktionary via Kaikki.org and WiktApi

French part-of-speech labels, short English and Spanish glosses, IPA, inflections,
and conjugation facts were extracted from Wiktionary snapshots through Kaikki.org
and WiktApi. Wiktionary text is available under Creative Commons Attribution-
ShareAlike; contributors and revision history are available from the corresponding
Wiktionary entry pages.

- https://kaikki.org/dictionary/French/index.html
- https://wiktapi.dev/quickstart
- https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use

## Apertium French–Spanish dictionary

Some short French-to-Spanish equivalents come from `apertium-fra-spa`, licensed
under GPL-2.0. The source dictionary and full license text used to build this asset
are retained in `data/raw/open_lexicon/` in the project source tree.

- https://github.com/apertium/apertium-fra-spa

## eSpeak NG

Where no dictionary IPA was available, broad French IPA was generated offline with
eSpeak NG. eSpeak NG is licensed under GPL-3.0.

- https://github.com/espeak-ng/espeak-ng

