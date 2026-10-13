"""Author and package tiny P7 fixtures. Never add them to formal assets or the production manifest."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
import sqlite3
import uuid
from content_packager import package
from content_search import search_key, VERSION

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'data/fixtures/p7/generated'

def translation(text):return {'text':text,'review_status':'fixture-only','source_json':'{"authored_test":true}'}

def word(target,lemma,order,system,level,answers,readings,forms):
    uid=f'{target}:fixture:{order}:1';sid=f'{uid}/sense';eid=f'{uid}/example'
    return {'uid':uid,'lemma':lemma,'part_of_speech':'fixture','homonym':1,'level_system':system,'level_value':level,
            'gender':'','order':order,'provenance_json':'{"fixture_only":true}',
            'senses':[{'id':sid,'order':0,'is_core':True,'translations':{key:translation(value) for key,value in answers.items()},
                       'examples':[{'id':eid,'order':0,'source_text':f'测试原句：{lemma}',
                                    'translations':{key:translation(f'{value} / 示例 {order}') for key,value in answers.items()}}]}],
            'pronunciations':[{'locale':locale,'reading_type':kind,'reading_text':text,'audio_asset':None} for locale,kind,text in readings],
            'forms':[{'order':0,'label':'测试词形','form':lemma,'features_json':'{"fixture":true}'}],
            'grammar_features':{'fixture_grammar':'测试能力','register':'测试语体'},
            'paradigms':[{'id':'fixture-paradigm','order':0,'mood':'fixture-capability','tense':'','label':'测试可变范式',
                          'region':'测试地区','note':'仅用于验证可变行数，不是正式教学内容',
                          'forms':[{'subject_key':key,'pronoun':label,'form':value,'order':n} for n,(key,label,value) in enumerate(forms)]}]}

def fixtures():
    definitions=[
        ('de','德语','de-DE','CEFR','A1','Haus',{'zh-Hans':'房屋','en':'house','es':'casa'},[('de-DE','IPA','haʊs')],
         [('nom','主格','das Haus'),('acc','宾格','das Haus'),('dat','与格','dem Haus'),('gen','属格','des Hauses')]),
        ('nl','荷兰语','nl-NL','CEFR','A1','huis',{'zh-Hans':'房屋','en':'house','de':'Haus'},[('nl-NL','IPA','ɦœy̯s')],
         [('singular','单数','huis'),('plural','复数','huizen')]),
        ('ja','日语','ja-JP','JLPT','N5','食べる',{'zh-Hans':'吃','en':'eat','de':'essen'},[('ja-JP','KANA','たべる'),('ja-JP','ROMAJI','taberu')],
         [('plain','普通体','食べる'),('polite','礼貌体','食べます')]),
        ('ko','韩语','ko-KR','TOPIK','1','먹다',{'zh-Hans':'吃','en':'eat','de':'essen'},[('ko-KR','HANGUL','먹다'),('ko-KR','ROMANIZATION','meokda')],
         [('plain','普通体','먹어'),('polite','礼貌体','먹어요'),('formal','正式体','먹습니다'),('honorific','敬语','드시다')]),
    ]
    for target,label,locale,system,level,lemma,answers,readings,forms in definitions:
        words=[word(target,lemma,0,system,level,answers,readings,forms)]
        if target=='ja':
            words += [word(target,kana,n,system,level,{'zh-Hans':f'假名 {kana}','en':f'kana {kana}'},[('ja-JP','KANA',kana)],[]) for n,kana in [(1,'か'),(2,'が')]]
        if target=='ko':words.append(word(target,'한글',1,'TOPIK','1',{'zh-Hans':'韩文','en':'Hangul','de':'koreanische Schrift'},[('ko-KR','HANGUL','한글')],[]))
        book=f'p7-{target}'
        content={'schema_version':1,'target_language':target,'content_version':f'p7-fixture-{target}-1','legacy_meta':{'fixture':'not formal content'},
                 'lexemes':words,'books':[{'id':book,'order':0,'level_system':system,'level_value':level,
                     'translations':{key:{'title':f'{label}测试词书 / {key}','description':'独立测试内容，不进入正式包'} for key in answers},
                     'members':[{'uid':w['uid'],'order':n} for n,w in enumerate(words)]}],
                 'attribution':[{'source_id':'authored-test','source_json':'{"fixture_only":true}','license_notice':'Authored P7 test fixture. Not official learning content.'}]}
        yield {'target_language':target,'label':label,'pronunciation_locale':locale,'default_book':book,'learning_languages':list(answers)},content

def main():
    OUT.mkdir(parents=True,exist_ok=True)
    candidate=ROOT/'.tools/tmp'/f'p7-fixtures-{uuid.uuid4()}'
    candidate.mkdir(parents=True)
    manifest={'schema_version':1,'normalization_version':VERSION,'fixture_only':True,'packages':{}}
    for definition,content in fixtures():
        target=definition['target_language'];file=candidate/f'{target}.db';package(content,file)
        row=dict(definition,asset=f'p7/{target}.db',schema_version=1,content_version=content['content_version'],sha256=sha256(file.read_bytes()).hexdigest(),bytes=file.stat().st_size)
        manifest['packages'][target]=row
        (candidate/f'{target}.json').write_text(json.dumps(content,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        file.replace(OUT/file.name)
        (candidate/f'{target}.json').replace(OUT/f'{target}.json')
    vectors=[]
    inputs=[' ÊTRE ','negociación','ＡＢＣ','100%_\\','Straße','huis','が','か','か\u3099','ｶﾞ','カ','ガ','かー','がく','食べる','日本語',
            '한글','한글','먹다','مُدَرِّس','مدرس','हिन्दी','क','कि','😀中文','𝔸ＢＣ','İ','École','각','가','한','한\u0301','ΟΣ','ός','ος','ё','е','שָׁלוֹם','שׁ','กิ','กา']
    for text in inputs:vectors.append({'input':text,'expected':search_key(text)})
    (OUT/'search-vectors.json').write_text(json.dumps(vectors,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    candidate.rmdir()
    print(json.dumps({'fixture_only':True,'targets':list(manifest['packages']),'words':{key:sqlite_count(OUT/f'{key}.db') for key in manifest['packages']},'search_vectors':len(vectors)},ensure_ascii=False))

def sqlite_count(path):
    db=sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)
    try:return db.execute('SELECT COUNT(*) FROM lexeme').fetchone()[0]
    finally:db.close()

if __name__=='__main__':main()
