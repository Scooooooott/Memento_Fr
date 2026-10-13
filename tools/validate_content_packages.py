"""Independent SQL round trip: compare every original field and association, not just counts."""
from hashlib import sha256
import argparse
import json
from pathlib import Path
import sqlite3
from content_search import search_key

ROOT=Path(__file__).resolve().parents[1]

def verify(target,source,package,license_notice):
    db=sqlite3.connect(Path(package).resolve().as_uri()+'?mode=ro',uri=True)
    try:
        db.execute('PRAGMA query_only=ON')
        db.execute('ATTACH DATABASE ? AS legacy',(Path(source).resolve().as_uri()+'?mode=ro',))
        schema=db.execute('PRAGMA user_version').fetchone()[0]
        source_hash=sha256(Path(source).read_bytes()).hexdigest()
        meta=dict(db.execute('SELECT key,value FROM content_meta'))
        if schema!=1 or list(db.execute('SELECT language_id FROM package_language'))!=[(target,)]:
            raise ValueError('Invalid package identity')
        if meta['content_version']!=source_hash or meta['target_language']!=target:
            raise ValueError('Invalid content identity')
        if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok' or list(db.execute('PRAGMA foreign_key_check')):
            raise ValueError('Invalid package structure')
        checks={}
        def compare(table,projection):
            expected=f'SELECT * FROM legacy.{table}'
            missing=db.execute(f'SELECT COUNT(*) FROM ({expected} EXCEPT {projection})').fetchone()[0]
            extra=db.execute(f'SELECT COUNT(*) FROM ({projection} EXCEPT {expected})').fetchone()[0]
            before=db.execute(f'SELECT COUNT(*) FROM legacy.{table}').fetchone()[0]
            after=db.execute(f'SELECT COUNT(*) FROM ({projection})').fetchone()[0]
            checks[table]={'source_rows':before,'roundtrip_rows':after,'missing_or_changed':missing,'extra_or_changed':extra,
                           'passed':missing==extra==0 and before==after}
        compare('lexeme',"""SELECT lexeme_uid,(SELECT value FROM content_meta WHERE key='legacy/language'),lemma,
            part_of_speech,homonym,level_value,gender,sort_order,provenance_json FROM lexeme""")
        columns=['english','spanish','chinese'] if target=='fr' else ['english','french','chinese']
        languages=['en','es','zh-Hans'] if target=='fr' else ['en','fr','zh-Hans']
        joins=' '.join(f"JOIN sense_translation t{i} ON t{i}.sense_id=s.sense_id AND t{i}.learning_language='{language}'" for i,language in enumerate(languages))
        compare('sense',f"SELECT s.sense_id,s.lexeme_uid,s.sort_order,t0.text,t1.text,t2.text"+(',s.is_core' if target=='fr' else '')+f' FROM sense s {joins}')
        joins=' '.join(f"JOIN example_translation t{i} ON t{i}.example_id=e.example_id AND t{i}.learning_language='{language}'" for i,language in enumerate(languages))
        compare('example',f'SELECT e.example_id,e.sense_id,e.sort_order,e.source_text,t0.text,t1.text,t2.text FROM example e {joins}')
        compare('pronunciation',"SELECT lexeme_uid,locale,reading_text,audio_asset FROM pronunciation WHERE reading_type='IPA'")
        compare('word_form','SELECT lexeme_uid,sort_order,label,form FROM word_form')
        features=['verb_group','auxiliary','past_participle','present_participle'] if target=='fr' else ['verb_group','auxiliary','participle','gerund','is_irregular','is_reflexive']
        pivot=[]
        for name in features:
            value=f"MAX(CASE WHEN name='{name}' THEN value END)"
            pivot.append(f'CAST({value} AS INTEGER)' if name.startswith('is_') else value)
        compare('verb_info','SELECT lexeme_uid,'+','.join(pivot)+' FROM grammar_feature GROUP BY lexeme_uid')
        if target=='fr':
            compare('conjugation_form',"""SELECT f.lexeme_uid,p.mood,p.tense,p.sort_order,CAST(f.subject_key AS INTEGER),f.pronoun,f.form
                FROM conjugation_form f JOIN conjugation_paradigm p ON p.lexeme_uid=f.lexeme_uid AND p.paradigm_id=f.paradigm_id""")
        else:
            compare('conjugation_paradigm','SELECT * FROM conjugation_paradigm')
            compare('conjugation_form','SELECT * FROM conjugation_form')
        compare('vocabulary_book',"""SELECT b.book_id,t.title,t.description,b.sort_order FROM vocabulary_book b
            JOIN book_translation t ON t.book_id=b.book_id AND t.learning_language='zh-Hans'""")
        compare('book_lexeme','SELECT * FROM book_lexeme')
        compare('content_meta',"SELECT substr(key,8),value FROM content_meta WHERE key LIKE 'legacy/%'")
        # Detect normalized rows that could be invisible to a round-trip projection.
        semantic_checks={
            'no_cross_target_uid':db.execute("SELECT COUNT(*) FROM lexeme WHERE target_language<>? OR substr(lexeme_uid,1,length(?)+1)<>?||':'",(target,target,target)).fetchone()[0]==0,
            'all_levels_preserved_as_cefr':db.execute("SELECT COUNT(*) FROM lexeme WHERE level_system<>'CEFR'").fetchone()[0]==0,
            'no_invented_book_levels':db.execute("SELECT COUNT(*) FROM vocabulary_book WHERE level_system<>'' OR level_value<>''").fetchone()[0]==0,
            'no_invented_book_translations':db.execute("SELECT COUNT(*) FROM book_translation WHERE learning_language<>'zh-Hans'").fetchone()[0]==0,
            'only_original_ipa':db.execute("SELECT COUNT(*) FROM pronunciation WHERE reading_type<>'IPA'").fetchone()[0]==0,
            'no_invented_form_features':db.execute("SELECT COUNT(*) FROM word_form WHERE features_json<>'{}'").fetchone()[0]==0,
            'grammar_fields_exact':set(row[0] for row in db.execute('SELECT DISTINCT name FROM grammar_feature'))<=set(features),
        }
        if target=='fr':
            expected_paradigms={}
            for uid,mood,tense,order in db.execute('SELECT DISTINCT lexeme_uid,mood,tense,tense_order FROM legacy.conjugation_form'):
                pid='legacy-fr:'+sha256(json.dumps([mood,tense],ensure_ascii=False,sort_keys=True,separators=(',',':')).encode('utf-8')).hexdigest()[:24]
                expected_paradigms[(uid,pid)]=(mood,tense,tense,'','',order)
            actual_paradigms={(row[0],row[1]):tuple(row[2:]) for row in db.execute('SELECT * FROM conjugation_paradigm')}
            semantic_checks['french_paradigm_identity_and_metadata']=expected_paradigms==actual_paradigms
            semantic_checks['french_person_identity_and_order']=db.execute("SELECT COUNT(*) FROM conjugation_form WHERE subject_key<>CAST(CAST(subject_key AS INTEGER) AS TEXT) OR sort_order<>CAST(subject_key AS INTEGER)").fetchone()[0]==0
        else:
            semantic_checks['spanish_default_core_senses']=db.execute('SELECT COUNT(*) FROM sense WHERE is_core<>1').fetchone()[0]==0
            semantic_checks['spanish_boolean_features_exact']=db.execute("SELECT COUNT(*) FROM grammar_feature WHERE name IN('is_irregular','is_reflexive') AND value NOT IN('0','1')").fetchone()[0]==0
        for table in ['sense_translation','example_translation']:
            ids='sense_id' if table.startswith('sense') else 'example_id'
            base='sense' if table.startswith('sense') else 'example'
            expected_rows=db.execute('SELECT COUNT(*) FROM '+base).fetchone()[0]*len(languages)
            semantic_checks[table+'_records_exact']=db.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]==expected_rows
            semantic_checks[table+'_review_status_preserved_only']=db.execute("SELECT COUNT(*) FROM "+table+" WHERE review_status<>'legacy-preserved-unassessed'").fetchone()[0]==0
            refs_ok=True
            for rid,language,source_json in db.execute(f'SELECT {ids},learning_language,source_json FROM {table}'):
                field=columns[languages.index(language)] if language in languages else None
                if json.loads(source_json)!={'source_db_sha256':source_hash,'legacy_table':base,'legacy_id':rid,'legacy_field':field}:
                    refs_ok=False;break
            semantic_checks[table+'_source_reference']=refs_ok
        attribution=list(db.execute('SELECT source_id,source_json,license_notice FROM attribution'))
        semantic_checks['license_notice_verbatim']=len(attribution)==1 and attribution[0][0]=='legacy-'+target and attribution[0][2]==license_notice
        if attribution:
            source_info=json.loads(attribution[0][1])
            semantic_checks['attribution_source_preserved']=source_info.get('source_db_sha256')==source_hash and source_info.get('legacy_meta')==dict(db.execute('SELECT * FROM legacy.content_meta'))
        search_ok=True
        for uid,lemma_key,full_key in db.execute('SELECT lexeme_uid,lemma_key,search_key FROM lexeme_search'):
            lemma=db.execute('SELECT lemma FROM legacy.lexeme WHERE lexeme_uid=?',(uid,)).fetchone()
            if lemma is None:
                search_ok=False;break
            texts=[lemma[0]]
            # Ordering is part of the search serialization contract; identity checks above are independent.
            legacy_fields={language:column for language,column in zip(languages,columns)}
            for sense in db.execute('SELECT * FROM legacy.sense WHERE lexeme_uid=? ORDER BY sort_order,sense_id',(uid,)):
                names=[row[1] for row in db.execute('PRAGMA legacy.table_info(sense)')]
                values=dict(zip(names,sense))
                texts.extend(values[legacy_fields[language]] for language in sorted(languages))
            if lemma_key!=search_key(lemma[0]) or full_key!=search_key('\n'.join(texts)):
                search_ok=False;break
        semantic_checks['search_records_exact']=search_ok and db.execute('SELECT COUNT(*) FROM lexeme_search').fetchone()[0]==db.execute('SELECT COUNT(*) FROM lexeme').fetchone()[0]
        coverage={}
        total=db.execute('SELECT COUNT(*) FROM lexeme').fetchone()[0]
        for learning in languages:
            missing_senses=db.execute("SELECT COUNT(*) FROM sense s WHERE NOT EXISTS(SELECT 1 FROM sense_translation t WHERE t.sense_id=s.sense_id AND t.learning_language=? AND trim(t.text)<>'')",(learning,)).fetchone()[0]
            missing_examples=db.execute("SELECT COUNT(*) FROM example e WHERE NOT EXISTS(SELECT 1 FROM example_translation t WHERE t.example_id=e.example_id AND t.learning_language=? AND trim(t.text)<>'')",(learning,)).fetchone()[0]
            complete=db.execute("""SELECT COUNT(*) FROM lexeme l WHERE EXISTS(SELECT 1 FROM sense s WHERE s.lexeme_uid=l.lexeme_uid)
                AND NOT EXISTS(SELECT 1 FROM sense s WHERE s.lexeme_uid=l.lexeme_uid AND NOT EXISTS(SELECT 1 FROM sense_translation t WHERE t.sense_id=s.sense_id AND t.learning_language=? AND trim(t.text)<>''))
                AND NOT EXISTS(SELECT 1 FROM example e JOIN sense s ON s.sense_id=e.sense_id WHERE s.lexeme_uid=l.lexeme_uid AND NOT EXISTS(SELECT 1 FROM example_translation t WHERE t.example_id=e.example_id AND t.learning_language=? AND trim(t.text)<>''))""",(learning,learning)).fetchone()[0]
            coverage[learning]={'lexemes':total,'complete_lexemes':complete,'missing_senses':missing_senses,'missing_examples':missing_examples,'passed':complete==total and missing_senses==missing_examples==0}
        counts={name:db.execute('SELECT COUNT(*) FROM '+name).fetchone()[0] for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")}
        passed=all(row['passed'] for row in checks.values()) and all(semantic_checks.values()) and all(row['passed'] for row in coverage.values())
        return {'target':target,'source_sha256':source_hash,'package_sha256':sha256(Path(package).read_bytes()).hexdigest(),
                'roundtrip':checks,'normalized_checks':semantic_checks,'coverage':coverage,'counts':counts,'passed':passed,
                'semantic_review':'not performed; original text and provenance retained'}
    finally:
        db.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--target',choices=['fr','es'],required=True)
    args=parser.parse_args()
    target=args.target
    filename='french_content.db' if target=='fr' else 'spanish_content.db'
    result=verify(target,ROOT/'data/source-snapshots'/filename,ROOT/'data/content-packages'/f'{target}.db',
                  (ROOT/'app/src/main/assets/licenses'/f'{target}-content.md').read_text(encoding='utf-8'))
    path=ROOT/'docs/qa/p4'/f'{target}-content-diff.json'
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=True,indent=2))
    raise SystemExit(0 if result['passed'] else 1)
