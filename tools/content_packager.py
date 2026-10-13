"""The packager consumes normalized records and has no language-specific branches."""
from pathlib import Path
import sqlite3
from content_contract import validate_intermediate
from content_search import search_key,VERSION as SEARCH_VERSION

ROOT=Path(__file__).resolve().parents[1]
SCHEMA=ROOT/'schemas/content-v1.sql'

def package(content,destination):
    validate_intermediate(content)
    destination=Path(destination)
    if destination.exists():
        raise ValueError('Build a new candidate; do not overwrite an unchecked package')
    db=sqlite3.connect(destination)
    try:
        db.executescript(SCHEMA.read_text(encoding='utf-8'))
        with db:
            target=content['target_language']
            db.execute('INSERT INTO package_language(language_id) VALUES(?)',(target,))
            metadata={'schema_version':'1','target_language':target,'content_version':content['content_version'],
                      'normalization_version':SEARCH_VERSION,'conversion_version':'memento-legacy-adapter-1'}
            metadata.update({'legacy/'+key:value for key,value in content['legacy_meta'].items()})
            db.executemany('INSERT INTO content_meta VALUES(?,?)',sorted(metadata.items()))
            for word in content['lexemes']:
                uid=word['uid']
                db.execute('INSERT INTO lexeme VALUES(?,?,?,?,?,?,?,?,?,?)',
                    (uid,target,word['lemma'],word['part_of_speech'],word['homonym'],word['level_system'],word['level_value'],word['gender'],word['order'],word['provenance_json']))
                search_parts=[word['lemma']]
                for sense in word['senses']:
                    sid=sense['id']
                    db.execute('INSERT INTO sense VALUES(?,?,?,?)',(sid,uid,sense['order'],int(sense['is_core'])))
                    for language,translation in sorted(sense['translations'].items()):
                        db.execute('INSERT INTO sense_translation VALUES(?,?,?,?,?)',(sid,language,translation['text'],translation['review_status'],translation['source_json']))
                        search_parts.append(translation['text'])
                    for example in sense['examples']:
                        eid=example['id']
                        db.execute('INSERT INTO example VALUES(?,?,?,?)',(eid,sid,example['order'],example['source_text']))
                        for language,translation in sorted(example['translations'].items()):
                            db.execute('INSERT INTO example_translation VALUES(?,?,?,?,?)',(eid,language,translation['text'],translation['review_status'],translation['source_json']))
                for p in word['pronunciations']:
                    db.execute('INSERT INTO pronunciation VALUES(?,?,?,?,?)',(uid,p['locale'],p['reading_type'],p['reading_text'],p['audio_asset']))
                for f in word['forms']:
                    db.execute('INSERT INTO word_form VALUES(?,?,?,?,?)',(uid,f['order'],f['label'],f['form'],f['features_json']))
                for name,value in sorted(word['grammar_features'].items()):
                    db.execute('INSERT INTO grammar_feature VALUES(?,?,?)',(uid,name,value))
                for p in word['paradigms']:
                    db.execute('INSERT INTO conjugation_paradigm VALUES(?,?,?,?,?,?,?,?)',
                        (uid,p['id'],p['mood'],p['tense'],p['label'],p['region'],p['note'],p['order']))
                    for f in p['forms']:
                        db.execute('INSERT INTO conjugation_form VALUES(?,?,?,?,?,?)',(uid,p['id'],f['subject_key'],f['pronoun'],f['form'],f['order']))
                db.execute('INSERT INTO lexeme_search VALUES(?,?,?)',(uid,search_key(word['lemma']),search_key('\n'.join(search_parts))))
            for book in content['books']:
                bid=book['id']
                db.execute('INSERT INTO vocabulary_book VALUES(?,?,?,?,?)',(bid,target,book['order'],book['level_system'],book['level_value']))
                for language,translation in sorted(book['translations'].items()):
                    db.execute('INSERT INTO book_translation VALUES(?,?,?,?)',(bid,language,translation['title'],translation['description']))
                db.executemany('INSERT INTO book_lexeme VALUES(?,?,?)',((bid,m['uid'],m['order']) for m in book['members']))
            db.executemany('INSERT INTO attribution VALUES(?,?,?)',((a['source_id'],a['source_json'],a['license_notice']) for a in content['attribution']))
        if list(db.execute('PRAGMA foreign_key_check')) or db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':
            raise ValueError('Packaged database failed integrity checks')
        db.execute('VACUUM')
    finally:
        db.close()
