"""Read frozen formal inputs. Legacy field names are confined to this adapter and QA."""
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path
import sqlite3

ROOT=Path(__file__).resolve().parents[1]
INPUTS={
    'fr':{'file':'french_content.db','schema':2,'locale':'fr-FR','source':'french',
          'translations':{'zh-Hans':'chinese','en':'english','es':'spanish'},'default_book':'flelex-a1-high-1000'},
    'es':{'file':'spanish_content.db','schema':1,'locale':'es-ES','source':'spanish',
          'translations':{'zh-Hans':'chinese','en':'english','fr':'french'},'default_book':'elelex-a1-top1200-es'},
}

def readonly(path):
    db=sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro',uri=True)
    db.row_factory=sqlite3.Row
    db.execute('PRAGMA query_only=ON')
    return db

def json_text(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))

def grouped(db,table,key,order):
    result=defaultdict(list)
    for row in db.execute(f'SELECT * FROM {table} ORDER BY {order}'):
        result[row[key]].append(dict(row))
    return result

def french_paradigm_id(mood,tense):
    # French has no old paradigm ID. Stable identity is derived from the original pair.
    return 'legacy-fr:'+sha256(json_text([mood,tense]).encode('utf-8')).hexdigest()[:24]

def adapt(target,source_path,license_notice,source_hash=None):
    config=INPUTS[target]
    source_hash=source_hash or sha256(Path(source_path).read_bytes()).hexdigest()
    db=readonly(source_path)
    try:
        if db.execute('PRAGMA user_version').fetchone()[0]!=config['schema']:
            raise ValueError('Unexpected legacy content schema')
        if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok' or list(db.execute('PRAGMA foreign_key_check')):
            raise ValueError('Invalid legacy database')
        meta=dict(db.execute('SELECT key,value FROM content_meta ORDER BY key'))
        if meta['language']!=config['locale']:
            raise ValueError('Legacy language identity mismatch')
        senses=grouped(db,'sense','lexeme_uid','lexeme_uid,sort_order,sense_id')
        examples=grouped(db,'example','sense_id','sense_id,sort_order,example_id')
        readings=grouped(db,'pronunciation','lexeme_uid','lexeme_uid,locale')
        forms=grouped(db,'word_form','lexeme_uid','lexeme_uid,sort_order')
        verbs={row['lexeme_uid']:dict(row) for row in db.execute('SELECT * FROM verb_info ORDER BY lexeme_uid')}
        conjugations=grouped(db,'conjugation_form','lexeme_uid','lexeme_uid,'+('tense_order,person' if target=='fr' else 'paradigm_id,sort_order'))
        paradigms=grouped(db,'conjugation_paradigm','lexeme_uid','lexeme_uid,sort_order') if target=='es' else {}
        members=grouped(db,'book_lexeme','book_id','book_id,sort_order')
        def translations(row,table,record_id):
            return {language:{'text':row[column],'review_status':'legacy-preserved-unassessed',
                    'source_json':json_text({'source_db_sha256':source_hash,'legacy_table':table,
                                            'legacy_id':record_id,'legacy_field':column})}
                    for language,column in config['translations'].items()}
        lexemes=[]
        for row in db.execute('SELECT * FROM lexeme ORDER BY sort_order,lexeme_uid'):
            uid=row['lexeme_uid']
            if row['language']!=config['locale']:
                raise ValueError('Mixed target-language input')
            word={'uid':uid,'lemma':row['lemma'],'part_of_speech':row['part_of_speech'],'homonym':row['homonym'],
                  'level_system':'CEFR','level_value':row['level'],'gender':row['gender'],'order':row['sort_order'],
                  'provenance_json':row['provenance_json'],'senses':[],
                  'pronunciations':[{'locale':p['locale'],'reading_type':'IPA','reading_text':p['ipa'],'audio_asset':p['audio_asset']} for p in readings[uid]],
                  'forms':[{'order':f['sort_order'],'label':f['label'],'form':f['form'],'features_json':'{}'} for f in forms[uid]],
                  'grammar_features':{},'paradigms':[]}
            for sense in senses[uid]:
                sid=sense['sense_id']
                word['senses'].append({'id':sid,'order':sense['sort_order'],'is_core':bool(sense.get('is_core',1)),
                    'translations':translations(sense,'sense',sid),
                    'examples':[{'id':e['example_id'],'order':e['sort_order'],'source_text':e[config['source']],
                                 'translations':translations(e,'example',e['example_id'])} for e in examples[sid]]})
            if uid in verbs:
                word['grammar_features']={key:str(value) for key,value in verbs[uid].items() if key!='lexeme_uid'}
            if target=='fr':
                by_tense={}
                for form in conjugations[uid]:
                    key=(form['mood'],form['tense'])
                    if key not in by_tense:
                        by_tense[key]={'id':french_paradigm_id(*key),'order':form['tense_order'],'mood':form['mood'],
                                      'tense':form['tense'],'label':form['tense'],'region':'','note':'','forms':[]}
                    if by_tense[key]['order']!=form['tense_order']:
                        raise ValueError('Inconsistent legacy tense order')
                    by_tense[key]['forms'].append({'subject_key':str(form['person']),'pronoun':form['pronoun'],
                                                  'form':form['form'],'order':form['person']})
                word['paradigms']=list(by_tense.values())
            else:
                for paradigm in paradigms.get(uid,[]):
                    word['paradigms'].append({'id':paradigm['paradigm_id'],'order':paradigm['sort_order'],
                        **{key:paradigm[key] for key in ('mood','tense','label','region','note')},
                        'forms':[{'subject_key':f['subject_key'],'pronoun':f['pronoun'],'form':f['form'],'order':f['sort_order']}
                                 for f in conjugations[uid] if f['paradigm_id']==paradigm['paradigm_id']]})
            lexemes.append(word)
        books=[{'id':b['book_id'],'order':b['sort_order'],'level_system':'','level_value':'',
                'translations':{'zh-Hans':{'title':b['title'],'description':b['description']}},
                'members':[{'uid':m['lexeme_uid'],'order':m['sort_order']} for m in members[b['book_id']]]}
               for b in db.execute('SELECT * FROM vocabulary_book ORDER BY sort_order')]
        return {'schema_version':1,'target_language':target,'content_version':source_hash,'legacy_meta':meta,
                'lexemes':lexemes,'books':books,'attribution':[{'source_id':'legacy-'+target,
                'source_json':json_text({'source_db_sha256':source_hash,'legacy_meta':meta,'text_policy':'verbatim; no new semantic review'}),
                'license_notice':license_notice}]}
    finally:
        db.close()
