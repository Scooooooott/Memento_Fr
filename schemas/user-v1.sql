PRAGMA foreign_keys=ON;
PRAGMA user_version=1;
CREATE TABLE target_language_registry(
 language_id TEXT PRIMARY KEY NOT NULL,
 CHECK(language_id=lower(language_id)),
 CHECK(language_id NOT IN ('en','eng') AND language_id NOT LIKE 'en-%' AND language_id NOT LIKE 'en\_%' ESCAPE '\' AND language_id NOT LIKE 'eng-%' AND language_id NOT LIKE 'eng\_%' ESCAPE '\')
);
CREATE TABLE app_settings(
 id INTEGER PRIMARY KEY CHECK(id=1),onboarding_completed INTEGER NOT NULL DEFAULT 0 CHECK(onboarding_completed IN(0,1)),
 target_language TEXT REFERENCES target_language_registry(language_id),learning_language TEXT,
 study_zone_id TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
 CHECK(onboarding_completed=0 OR (target_language IS NOT NULL AND learning_language IS NOT NULL))
);
CREATE TABLE language_settings(
 target_language TEXT PRIMARY KEY REFERENCES target_language_registry(language_id),
 study_mode TEXT NOT NULL CHECK(study_mode IN('STANDARD','REVIEW_ALL')),daily_target INTEGER,
 book_id TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
 auto_play TEXT NOT NULL DEFAULT 'NEW_ONLY',tts_fallback INTEGER NOT NULL DEFAULT 1 CHECK(tts_fallback IN(0,1)),
 show_reading INTEGER NOT NULL DEFAULT 1 CHECK(show_reading IN(0,1)),
 CHECK((study_mode='STANDARD' AND daily_target IS NOT NULL AND daily_target>0) OR (study_mode='REVIEW_ALL' AND daily_target IS NULL))
);
CREATE TABLE learning_card(
 target_language TEXT NOT NULL REFERENCES target_language_registry(language_id),lexeme_uid TEXT NOT NULL,
 stability REAL NOT NULL CHECK(stability>=0),difficulty REAL NOT NULL CHECK(difficulty>=0),
 due_at INTEGER NOT NULL,last_review_at INTEGER,repetitions INTEGER NOT NULL CHECK(repetitions>=0),
 lapses INTEGER NOT NULL CHECK(lapses>=0),state TEXT NOT NULL,content_available INTEGER NOT NULL DEFAULT 1 CHECK(content_available IN(0,1)),
 PRIMARY KEY(target_language,lexeme_uid),CHECK(substr(lexeme_uid,1,length(target_language)+1)=target_language || ':')
);
CREATE INDEX cards_due ON learning_card(target_language,due_at,last_review_at);
CREATE TABLE favorites(
 target_language TEXT NOT NULL REFERENCES target_language_registry(language_id),lexeme_uid TEXT NOT NULL,
 PRIMARY KEY(target_language,lexeme_uid),CHECK(substr(lexeme_uid,1,length(target_language)+1)=target_language || ':')
);
CREATE TABLE daily_plan(
 id INTEGER PRIMARY KEY AUTOINCREMENT,target_language TEXT NOT NULL REFERENCES target_language_registry(language_id),
 business_date TEXT NOT NULL,zone_id TEXT NOT NULL,created_at INTEGER NOT NULL,due_cutoff_at INTEGER NOT NULL,
 study_mode TEXT NOT NULL CHECK(study_mode IN('STANDARD','REVIEW_ALL')),target_snapshot INTEGER,
 book_snapshot TEXT NOT NULL,content_version TEXT NOT NULL,policy_version INTEGER NOT NULL,
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),eligible_review_count INTEGER NOT NULL CHECK(eligible_review_count>=0),
 status TEXT NOT NULL CHECK(status IN('ACTIVE','COMPLETED','CLOSED_INCOMPLETE')),
 completed_at INTEGER,closed_at INTEGER,close_reason TEXT,
 UNIQUE(target_language,business_date),UNIQUE(id,target_language),UNIQUE(id,target_language,business_date),
 CHECK((study_mode='STANDARD' AND target_snapshot IS NOT NULL AND target_snapshot>0) OR (study_mode='REVIEW_ALL' AND target_snapshot IS NULL)),
 CHECK((status='ACTIVE' AND completed_at IS NULL AND closed_at IS NULL) OR
       (status='COMPLETED' AND completed_at IS NOT NULL AND closed_at IS NOT NULL) OR
       (status='CLOSED_INCOMPLETE' AND completed_at IS NULL AND closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX one_active_plan_per_language ON daily_plan(target_language) WHERE status='ACTIVE';
CREATE TABLE plan_revision(
 plan_id INTEGER NOT NULL REFERENCES daily_plan(id),revision INTEGER NOT NULL,changed_at INTEGER NOT NULL,
 reason TEXT NOT NULL,settings_json TEXT NOT NULL,PRIMARY KEY(plan_id,revision)
);
CREATE TABLE extra_review_batch(
 id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER NOT NULL UNIQUE,target_language TEXT NOT NULL,
 decision TEXT NOT NULL CHECK(decision IN('PENDING','ACCEPTED','DECLINED')),created_at INTEGER NOT NULL,
 completed_at INTEGER,UNIQUE(id,plan_id,target_language),
 FOREIGN KEY(plan_id,target_language) REFERENCES daily_plan(id,target_language)
);
CREATE TABLE daily_plan_item(
 id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER NOT NULL,target_language TEXT NOT NULL,
 lexeme_uid TEXT NOT NULL,queue_kind TEXT NOT NULL CHECK(queue_kind IN('NORMAL','EXTRA')),
 extra_batch_id INTEGER,position INTEGER NOT NULL CHECK(position>=0),is_new INTEGER NOT NULL CHECK(is_new IN(0,1)),
 state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','GRADED','UNAVAILABLE','REMOVED')),
 revealed INTEGER NOT NULL DEFAULT 0 CHECK(revealed IN(0,1)),rating TEXT CHECK(rating IN('AGAIN','HARD','GOOD','EASY')),
 UNIQUE(plan_id,lexeme_uid),UNIQUE(plan_id,queue_kind,position),UNIQUE(id,plan_id,target_language,lexeme_uid,queue_kind),
 FOREIGN KEY(plan_id,target_language) REFERENCES daily_plan(id,target_language),
 FOREIGN KEY(extra_batch_id,plan_id,target_language) REFERENCES extra_review_batch(id,plan_id,target_language),
 CHECK(substr(lexeme_uid,1,length(target_language)+1)=target_language || ':'),
 CHECK((queue_kind='NORMAL' AND extra_batch_id IS NULL) OR (queue_kind='EXTRA' AND extra_batch_id IS NOT NULL AND is_new=0)),
 CHECK((state='GRADED' AND rating IS NOT NULL) OR (state<>'GRADED' AND rating IS NULL))
);
CREATE INDEX plan_queue ON daily_plan_item(plan_id,queue_kind,state,position);
CREATE TABLE rollover_decision(
 plan_id INTEGER PRIMARY KEY,target_language TEXT NOT NULL,choice TEXT NOT NULL CHECK(choice IN('IMMEDIATE','DEFER')),
 decided_at INTEGER NOT NULL,defer_until INTEGER,zone_id TEXT NOT NULL,
 FOREIGN KEY(plan_id,target_language) REFERENCES daily_plan(id,target_language),
 CHECK((choice='IMMEDIATE' AND defer_until IS NULL) OR (choice='DEFER' AND defer_until IS NOT NULL AND defer_until>decided_at))
);
CREATE TABLE migration_batch(
 id INTEGER PRIMARY KEY AUTOINCREMENT,source_identity TEXT NOT NULL,input_sha256 TEXT NOT NULL,
 input_schema_version INTEGER NOT NULL,imported_at INTEGER NOT NULL,report_json TEXT NOT NULL,
 UNIQUE(source_identity,input_sha256)
);
CREATE TABLE review_log(
 id INTEGER PRIMARY KEY AUTOINCREMENT,target_language TEXT NOT NULL REFERENCES target_language_registry(language_id),
 lexeme_uid TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN('NORMAL','EXTRA','LEGACY')),
 plan_id INTEGER,item_id INTEGER UNIQUE,request_id TEXT UNIQUE,
 rating TEXT NOT NULL CHECK(rating IN('AGAIN','HARD','GOOD','EASY')),is_new INTEGER NOT NULL CHECK(is_new IN(0,1)),
 reviewed_at INTEGER NOT NULL,business_date TEXT NOT NULL,zone_id TEXT NOT NULL,legacy_review_day TEXT,
 previous_stability REAL NOT NULL,previous_difficulty REAL NOT NULL,next_stability REAL NOT NULL,next_difficulty REAL NOT NULL,
 next_due_at INTEGER NOT NULL,elapsed_days INTEGER NOT NULL,scheduler_version TEXT NOT NULL,desired_retention REAL NOT NULL,
 source_identity TEXT,source_record_id TEXT,migration_batch_id INTEGER REFERENCES migration_batch(id),
 UNIQUE(source_identity,source_record_id),
 FOREIGN KEY(item_id,plan_id,target_language,lexeme_uid,kind) REFERENCES daily_plan_item(id,plan_id,target_language,lexeme_uid,queue_kind),
 FOREIGN KEY(plan_id,target_language,business_date) REFERENCES daily_plan(id,target_language,business_date),
 CHECK(substr(lexeme_uid,1,length(target_language)+1)=target_language || ':'),
 CHECK((kind='LEGACY' AND item_id IS NULL AND plan_id IS NULL AND source_identity IS NOT NULL AND source_record_id IS NOT NULL) OR
       (kind IN('NORMAL','EXTRA') AND item_id IS NOT NULL AND plan_id IS NOT NULL AND request_id IS NOT NULL))
);
CREATE INDEX log_language_day ON review_log(target_language,business_date,lexeme_uid);
CREATE TABLE migration_ledger(
 source_identity TEXT NOT NULL,record_type TEXT NOT NULL,source_record_id TEXT NOT NULL,
 target_record_id TEXT NOT NULL,record_sha256 TEXT NOT NULL,batch_id INTEGER NOT NULL REFERENCES migration_batch(id),
 PRIMARY KEY(source_identity,record_type,source_record_id)
);
CREATE TABLE legacy_session_history(
 source_identity TEXT NOT NULL,target_language TEXT NOT NULL REFERENCES target_language_registry(language_id),
 source_session_id TEXT NOT NULL,payload_json TEXT NOT NULL,batch_id INTEGER NOT NULL REFERENCES migration_batch(id),
 PRIMARY KEY(source_identity,source_session_id)
);
