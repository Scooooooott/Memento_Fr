PRAGMA foreign_keys=ON;
PRAGMA user_version=1;
CREATE TABLE content_meta(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL);
CREATE TABLE package_language(
 language_id TEXT PRIMARY KEY NOT NULL,singleton INTEGER NOT NULL DEFAULT 1 UNIQUE CHECK(singleton=1),
 CHECK(language_id=lower(language_id)),
 CHECK(language_id NOT IN ('en','eng') AND language_id NOT LIKE 'en-%' AND language_id NOT LIKE 'en\_%' ESCAPE '\' AND language_id NOT LIKE 'eng-%' AND language_id NOT LIKE 'eng\_%' ESCAPE '\')
);
CREATE TABLE lexeme(
 lexeme_uid TEXT PRIMARY KEY NOT NULL,
 target_language TEXT NOT NULL REFERENCES package_language(language_id),
 lemma TEXT NOT NULL CHECK(length(trim(lemma))>0),part_of_speech TEXT NOT NULL,
 homonym INTEGER NOT NULL CHECK(homonym>0),level_system TEXT NOT NULL,level_value TEXT NOT NULL,
 gender TEXT NOT NULL DEFAULT '',sort_order INTEGER NOT NULL UNIQUE,
 provenance_json TEXT NOT NULL,
 UNIQUE(target_language,lemma,part_of_speech,homonym),
 CHECK(substr(lexeme_uid,1,length(target_language)+1)=target_language || ':')
);
CREATE TABLE sense(
 sense_id TEXT PRIMARY KEY NOT NULL,lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),
 sort_order INTEGER NOT NULL CHECK(sort_order>=0),is_core INTEGER NOT NULL DEFAULT 1 CHECK(is_core IN(0,1)),
 UNIQUE(lexeme_uid,sort_order)
);
CREATE TABLE sense_translation(
 sense_id TEXT NOT NULL REFERENCES sense(sense_id),learning_language TEXT NOT NULL,
 text TEXT NOT NULL CHECK(length(trim(text))>0),review_status TEXT NOT NULL,source_json TEXT NOT NULL,
 PRIMARY KEY(sense_id,learning_language)
);
CREATE TABLE example(
 example_id TEXT PRIMARY KEY NOT NULL,sense_id TEXT NOT NULL REFERENCES sense(sense_id),
 sort_order INTEGER NOT NULL CHECK(sort_order>=0),source_text TEXT NOT NULL CHECK(length(trim(source_text))>0),
 UNIQUE(sense_id,sort_order)
);
CREATE TABLE example_translation(
 example_id TEXT NOT NULL REFERENCES example(example_id),learning_language TEXT NOT NULL,
 text TEXT NOT NULL CHECK(length(trim(text))>0),review_status TEXT NOT NULL,source_json TEXT NOT NULL,
 PRIMARY KEY(example_id,learning_language)
);
CREATE TABLE pronunciation(
 lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),locale TEXT NOT NULL,
 reading_type TEXT NOT NULL,reading_text TEXT NOT NULL,audio_asset TEXT,
 PRIMARY KEY(lexeme_uid,locale,reading_type)
);
CREATE TABLE word_form(
 lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),sort_order INTEGER NOT NULL,
 label TEXT NOT NULL,form TEXT NOT NULL CHECK(length(trim(form))>0),features_json TEXT NOT NULL DEFAULT '{}',
 PRIMARY KEY(lexeme_uid,sort_order)
);
CREATE TABLE grammar_feature(
 lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),name TEXT NOT NULL,value TEXT NOT NULL,
 PRIMARY KEY(lexeme_uid,name)
);
CREATE TABLE conjugation_paradigm(
 lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),paradigm_id TEXT NOT NULL,
 mood TEXT NOT NULL,tense TEXT NOT NULL,label TEXT NOT NULL,region TEXT NOT NULL DEFAULT '',
 note TEXT NOT NULL DEFAULT '',sort_order INTEGER NOT NULL,
 PRIMARY KEY(lexeme_uid,paradigm_id),UNIQUE(lexeme_uid,sort_order)
);
CREATE TABLE conjugation_form(
 lexeme_uid TEXT NOT NULL,paradigm_id TEXT NOT NULL,subject_key TEXT NOT NULL,
 pronoun TEXT NOT NULL,form TEXT NOT NULL CHECK(length(trim(form))>0),sort_order INTEGER NOT NULL,
 PRIMARY KEY(lexeme_uid,paradigm_id,subject_key),UNIQUE(lexeme_uid,paradigm_id,sort_order),
 FOREIGN KEY(lexeme_uid,paradigm_id) REFERENCES conjugation_paradigm(lexeme_uid,paradigm_id)
);
CREATE TABLE vocabulary_book(
 book_id TEXT PRIMARY KEY NOT NULL,target_language TEXT NOT NULL REFERENCES package_language(language_id),
 sort_order INTEGER NOT NULL UNIQUE,level_system TEXT NOT NULL DEFAULT '',level_value TEXT NOT NULL DEFAULT ''
);
CREATE TABLE book_translation(
 book_id TEXT NOT NULL REFERENCES vocabulary_book(book_id),learning_language TEXT NOT NULL,
 title TEXT NOT NULL,description TEXT NOT NULL,PRIMARY KEY(book_id,learning_language)
);
CREATE TABLE book_lexeme(
 book_id TEXT NOT NULL REFERENCES vocabulary_book(book_id),lexeme_uid TEXT NOT NULL REFERENCES lexeme(lexeme_uid),
 sort_order INTEGER NOT NULL,PRIMARY KEY(book_id,lexeme_uid),UNIQUE(book_id,sort_order)
);
CREATE TABLE attribution(source_id TEXT PRIMARY KEY NOT NULL,source_json TEXT NOT NULL,license_notice TEXT NOT NULL);
CREATE TABLE lexeme_search(
 lexeme_uid TEXT PRIMARY KEY NOT NULL REFERENCES lexeme(lexeme_uid),
 lemma_key TEXT NOT NULL,search_key TEXT NOT NULL
);
CREATE INDEX search_lemma ON lexeme_search(lemma_key);
CREATE INDEX sense_lexeme ON sense(lexeme_uid,sort_order);
CREATE INDEX example_sense ON example(sense_id,sort_order);
CREATE INDEX book_member_uid ON book_lexeme(lexeme_uid);
