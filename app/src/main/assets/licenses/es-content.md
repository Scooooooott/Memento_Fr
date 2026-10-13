# Spanish content provenance and review notice

The production asset spanish_content.db contains 14,290 ELELex A1–C1 source
rows. It is an automatically assembled learning dataset, not a professionally
edited dictionary. The machine-generated and fallback material listed in
data/curated/spanish_full_qa.json requires editorial review before a public
release.

## Scope source

- ELELex.tsv was supplied by the project owner. The derived A1–C1 membership,
  ordering, source tags and levels are retained in the database references.
- No license file accompanied the supplied ELELex data in this workspace.
  Confirm the right to redistribute ELELex-derived membership and metadata
  before publishing the database or an APK that contains it.
- Background citation: François, T. and De Cock, S. (2018), ELELex.

## Dictionary enrichment

- English definitions, IPA, forms and some examples were extracted from the
  English Wiktionary Spanish dataset published by Kaikki:
  https://kaikki.org/dictionary/Spanish/index.html
- Spanish definitions, IPA, translations, forms and some examples were
  extracted from the Spanish Wiktionary dataset published by Kaikki:
  https://kaikki.org/eswiktionary/Espa%C3%B1ol/index.html
- Kaikki data is generated with Wiktextract from Wikimedia Wiktionary dumps.
  Wiktionary-derived text remains subject to the applicable Wiktionary
  attribution/share-alike and GFDL terms. Preserve per-entry source references
  and review the current source terms before distribution.
- Recommended academic citation: Tatu Ylonen, *Wiktextract: Wiktionary as
  Machine-Readable Structured Data*, LREC 2022.

## Example sentences

- Tatoeba sentences and direct translations are used under CC BY 2.0 FR:
  https://creativecommons.org/licenses/by/2.0/fr/
- Each affected entry records its Tatoeba sentence ID and a link to the
  sentence page so author attribution can be resolved, for example:
  https://tatoeba.org/en/sentences/show/2804
- Project/download information: https://tatoeba.org/en/downloads

## Generated material and software used offline

- Missing IPA was generated with gruut 2.4.0 / gruut-lang-es 2.0.1:
  https://github.com/rhasspy/gruut (MIT).
- Spanish conjugation tables were generated with verbecc 2.0.3:
  https://github.com/bretttolbert/verbecc (LGPL-3.0).
- Missing translations were generated locally with Argos Translate 1.9
  language packages: https://github.com/argosopentech/argos-translate
  (the Argos software is MIT/CC0).
- The language-model package index does not state a clear redistribution
  license for every model archive. The archives under .tools are build-time
  caches and are not Android assets; do not redistribute those model archives
  without a separate license review.
- A small set of high-frequency A1 function-word meanings and the development
  sample entries were manually reviewed in this project.

Conjugation structure reference:
https://www.rae.es/buen-uso-español/conjugación-española

FSRS implementation retains its MIT attribution in licenses/ts-fsrs.txt.
