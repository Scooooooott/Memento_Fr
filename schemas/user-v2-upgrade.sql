PRAGMA user_version=2;
ALTER TABLE daily_plan_item ADD COLUMN offered INTEGER NOT NULL DEFAULT 0 CHECK(offered IN(0,1));
CREATE TABLE study_clock(
 id INTEGER PRIMARY KEY CHECK(id=1),max_observed_at INTEGER NOT NULL CHECK(max_observed_at>=0)
);
INSERT INTO study_clock(id,max_observed_at) SELECT 1,MAX(0,COALESCE(MAX(reviewed_at),0)) FROM review_log;
CREATE TABLE plan_review_candidate(
 plan_id INTEGER NOT NULL,target_language TEXT NOT NULL,lexeme_uid TEXT NOT NULL,
 position INTEGER NOT NULL CHECK(position>=0),
 PRIMARY KEY(plan_id,lexeme_uid),UNIQUE(plan_id,position),
 FOREIGN KEY(plan_id,target_language) REFERENCES daily_plan(id,target_language),
 CHECK(substr(lexeme_uid,1,length(target_language)+1)=target_language || ':')
);
