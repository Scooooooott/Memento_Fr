PRAGMA page_size = 4096;
PRAGMA encoding = 'UTF-8';
PRAGMA foreign_keys = ON;
PRAGMA user_version = 2;

CREATE TABLE content_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE lexeme (
  lexeme_uid TEXT PRIMARY KEY,
  language TEXT NOT NULL CHECK (language = 'fr-FR'),
  lemma TEXT NOT NULL,
  part_of_speech TEXT NOT NULL CHECK (part_of_speech IN (
    'verb','noun','adjective','adverb','interjection','preposition','conjunction',
    'pronoun','determiner','proper_noun','expression','numeral'
  )),
  homonym INTEGER NOT NULL CHECK (homonym > 0),
  level TEXT NOT NULL CHECK (level IN ('A1','A2','B1','B2','C1','C2')),
  gender TEXT NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL UNIQUE,
  provenance_json TEXT NOT NULL,
  UNIQUE(language, lemma, part_of_speech, homonym)
);
CREATE TABLE sense (
  sense_id TEXT PRIMARY KEY,
  lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),
  sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
  english TEXT NOT NULL CHECK (length(trim(english)) > 0),
  spanish TEXT NOT NULL CHECK (length(trim(spanish)) > 0),
  chinese TEXT NOT NULL DEFAULT '',
  is_core INTEGER NOT NULL CHECK (is_core IN (0,1)),
  UNIQUE(lexeme_uid, sort_order)
);
CREATE TABLE example (
  example_id TEXT PRIMARY KEY,
  sense_id TEXT NOT NULL REFERENCES sense(sense_id),
  sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
  french TEXT NOT NULL CHECK (length(trim(french)) > 0),
  english TEXT NOT NULL CHECK (length(trim(english)) > 0),
  spanish TEXT NOT NULL CHECK (length(trim(spanish)) > 0),
  chinese TEXT NOT NULL DEFAULT '',
  UNIQUE(sense_id, sort_order)
);
CREATE TABLE word_form (
  lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),
  sort_order INTEGER NOT NULL CHECK (sort_order >= 0),
  label TEXT NOT NULL,
  form TEXT NOT NULL CHECK (length(trim(form)) > 0),
  PRIMARY KEY(lexeme_uid, sort_order)
);
CREATE TABLE verb_info (
  lexeme_uid TEXT PRIMARY KEY REFERENCES lexeme(lexeme_uid),
  verb_group TEXT NOT NULL,
  auxiliary TEXT NOT NULL CHECK (auxiliary IN ('avoir','être')),
  past_participle TEXT NOT NULL,
  present_participle TEXT NOT NULL
);
CREATE TABLE conjugation_form (
  lexeme_uid TEXT NOT NULL REFERENCES verb_info(lexeme_uid),
  mood TEXT NOT NULL CHECK (mood = 'indicatif'),
  tense TEXT NOT NULL CHECK (tense IN ('présent','passé composé','imparfait')),
  tense_order INTEGER NOT NULL,
  person INTEGER NOT NULL CHECK (person BETWEEN 1 AND 6),
  pronoun TEXT NOT NULL,
  form TEXT NOT NULL CHECK (length(trim(form)) > 0),
  PRIMARY KEY(lexeme_uid, mood, tense, person)
);
CREATE TABLE pronunciation (
  lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),
  locale TEXT NOT NULL CHECK (locale = 'fr-FR'),
  ipa TEXT NOT NULL CHECK (length(trim(ipa)) > 2),
  audio_asset TEXT,
  PRIMARY KEY(lexeme_uid, locale)
);
CREATE TABLE vocabulary_book (
  book_id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  sort_order INTEGER NOT NULL UNIQUE
);
CREATE TABLE book_lexeme (
  book_id TEXT NOT NULL REFERENCES vocabulary_book(book_id),
  lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),
  sort_order INTEGER NOT NULL,
  PRIMARY KEY(book_id, lexeme_uid),
  UNIQUE(book_id, sort_order)
);
CREATE INDEX example_sense_idx ON example(sense_id);
CREATE INDEX sense_lexeme_idx ON sense(lexeme_uid);
CREATE INDEX book_lexeme_uid_idx ON book_lexeme(lexeme_uid);
