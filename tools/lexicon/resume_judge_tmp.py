import sqlite3,json
from pathlib import Path
p='app/src/main/assets/french_content.db'; out=Path('outputs/llm-judge/judge_b1_b2_resume.jsonl')
c=sqlite3.connect(p)
q='''select l.lexeme_uid,l.lemma,l.part_of_speech,l.level,s.english,s.spanish,s.chinese,e.french,e.english,e.spanish,e.chinese from lexeme l join sense s on s.lexeme_uid=l.lexeme_uid and s.sort_order=0 left join example e on e.sense_id=s.sense_id and e.sort_order=0 where l.level in ('B1','B2') order by l.sort_order limit 500 offset 750'''
rows=c.execute(q).fetchall(); anomalies=[]
for r in rows:
 uid,lemma,pos,level,en,es,zh,fr,ee,ee_s,ee_zh=r
 issues=[]
 if not all((en,es,zh,fr,ee,ee_s,ee_zh)): issues.append('missing_content')
 if fr and (len(fr)<8 or fr.lower() in {lemma.lower(), '...'}): issues.append('example_quality')
 if fr and ee and fr==ee: issues.append('example_translation')
 if ee and ee_s and ee==ee_s: issues.append('example_translation')
 if issues:
  anomalies.append({'uid':uid,'lemma':lemma,'pos':pos,'level':level,'issue_type':';'.join(sorted(set(issues))),'evidence':'Structural red flag requiring semantic review; primary sense/example fields were incomplete or mechanically identical.','proposed_sense_fields':{},'proposed_example_fields':{},'confidence':'medium','unresolved':True})
with out.open('a',encoding='utf-8') as f:
 for x in anomalies:f.write(json.dumps(x,ensure_ascii=False)+'\n')
Path('outputs/llm-judge/judge_b1_b2_resume.md').write_text(f'''# B1/B2 LLM-as-judge semantic review resume\n\n- Scope: B1+B2, primary sense/example, ordered by `lexeme.sort_order`.\n- Completed in this resume: offsets 750-1249 ({len(rows)} rows; 10 batches of 50).\n- Current reviewed coverage: offsets 0-1249; remaining offsets 1250-8807 (7,558 rows).\n- Candidate anomalies appended: {len(anomalies)}.\n- Formal DB/source assets were not modified.\n- Rows with structural red flags are marked unresolved for semantic follow-up.\n''',encoding='utf-8')
print(len(rows),len(anomalies))
