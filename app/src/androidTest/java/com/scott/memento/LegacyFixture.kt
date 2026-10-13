package com.scott.memento

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject

/** Independent old schemas, from the read-only production helpers and French v1 migration fixture. */
internal object LegacyFixture {
    const val NOW=1791604800000L // 2026-10-10 04:00 UTC
    fun create(file:File,target:String="fr",version:Int=2,limit:Int=10):File {
        file.parentFile!!.mkdirs();check(!file.exists())
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db->
            db.setForeignKeyConstraintsEnabled(true)
            val other=if(target=="fr") "spanish" else "french"
            val chinese=target=="es" || version==2
            db.execSQL("""CREATE TABLE settings(id INTEGER PRIMARY KEY CHECK(id=1),daily_new_limit INTEGER NOT NULL CHECK(daily_new_limit>=0),
                book_id TEXT NOT NULL,auto_play TEXT NOT NULL,tts_fallback INTEGER NOT NULL,show_ipa INTEGER NOT NULL,
                show_english INTEGER NOT NULL,show_$other INTEGER NOT NULL ${if(chinese) ",show_chinese INTEGER NOT NULL DEFAULT 1" else ""},
                CHECK(show_english=1 OR show_$other=1 ${if(chinese) "OR show_chinese=1" else ""}))""")
            db.execSQL("INSERT INTO settings VALUES(1,?,'book','NEVER',0,0,1,1${if(chinese) ",1" else ""})",arrayOf(limit))
            db.execSQL("""CREATE TABLE learning_card(lexeme_uid TEXT PRIMARY KEY NOT NULL,stability REAL NOT NULL,difficulty REAL NOT NULL,
                due_at INTEGER NOT NULL,last_review_at INTEGER,repetitions INTEGER NOT NULL,lapses INTEGER NOT NULL,state TEXT NOT NULL)""")
            db.execSQL("CREATE INDEX cards_due ON learning_card(due_at)")
            db.execSQL("""CREATE TABLE study_session(id INTEGER PRIMARY KEY AUTOINCREMENT,position INTEGER NOT NULL DEFAULT 0,
                revealed INTEGER NOT NULL DEFAULT 0,started_at INTEGER NOT NULL,completed_at INTEGER,settings_json TEXT NOT NULL)""")
            db.execSQL("CREATE UNIQUE INDEX one_active_session ON study_session((1)) WHERE completed_at IS NULL")
            db.execSQL("""CREATE TABLE study_session_item(session_id INTEGER NOT NULL REFERENCES study_session(id),position INTEGER NOT NULL,
                lexeme_uid TEXT NOT NULL,is_new INTEGER NOT NULL,rating TEXT,PRIMARY KEY(session_id,position),UNIQUE(session_id,lexeme_uid))""")
            db.execSQL("""CREATE TABLE review_log(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id INTEGER NOT NULL,position INTEGER NOT NULL,
                lexeme_uid TEXT NOT NULL,rating TEXT NOT NULL,is_new INTEGER NOT NULL,reviewed_at INTEGER NOT NULL,review_day TEXT NOT NULL,
                previous_stability REAL NOT NULL,previous_difficulty REAL NOT NULL,next_stability REAL NOT NULL,next_difficulty REAL NOT NULL,
                next_due_at INTEGER NOT NULL,elapsed_days INTEGER NOT NULL,scheduler_version TEXT NOT NULL,desired_retention REAL NOT NULL,
                UNIQUE(session_id,position),FOREIGN KEY(session_id,position) REFERENCES study_session_item(session_id,position))""")
            db.execSQL("CREATE INDEX review_log_day ON review_log(review_day,is_new)")
            db.execSQL("CREATE TABLE favorites(lexeme_uid TEXT PRIMARY KEY NOT NULL)")
            val settings=JSONObject().put("dailyNewLimit",limit).put("bookId","book").put("autoPlay","NEVER").put("ttsFallback",false)
                .put("showIpa",false).put("showEnglish",true).put("show${other.replaceFirstChar{it.uppercase()}}",true)
            if(chinese)settings.put("showChinese",true)
            // Started yesterday, rated today, remains incomplete and revealed.
            db.execSQL("INSERT INTO study_session VALUES(1,1,1,?,NULL,?)",arrayOf(NOW-86400000,settings.toString()))
            db.execSQL("INSERT INTO study_session_item VALUES(1,0,?,1,'GOOD')",arrayOf("$target:a:noun:1"))
            db.execSQL("INSERT INTO study_session_item VALUES(1,1,?,1,NULL)",arrayOf("$target:b:noun:1"))
            log(db,1,1,"$target:a:noun:1",NOW,"2026-10-10")
            db.execSQL("INSERT INTO learning_card VALUES(?,3.123456789,5.987654321,?,?,7,2,'REVIEW')",arrayOf("$target:a:noun:1",NOW+86400000,NOW))
            db.execSQL("INSERT INTO learning_card VALUES(?,0,0,0,NULL,0,0,'NEW')",arrayOf("$target:b:noun:1"))
            db.execSQL("INSERT INTO favorites VALUES(?)",arrayOf("$target:a:noun:1"))
            db.version=version
        }
        return file
    }
    fun log(db:SQLiteDatabase,id:Int,session:Int,uid:String,time:Long,date:String) {
        db.execSQL("INSERT INTO review_log VALUES(?,?,0,?,'GOOD',1,?,?,0.2,1.5,3.123456789,5.987654321,?,2,'fsrs-old-fixture',0.9)",arrayOf(id,session,uid,time,date,time+86400000))
    }
}
