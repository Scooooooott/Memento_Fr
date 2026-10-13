package com.scott.memento.data.migration

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.scott.memento.domain.Languages
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

data class LegacyPreview(val file:File,val target:String,val version:Int,val inputSha:String,val logicalSha:String,
    val counts:Map<String,Int>,val settings:JSONObject,val unavailable:Int)

/** A standalone SQLite snapshot, opened read-only. No checkpoint or repair of the input. */
class LegacySource private constructor(val db:SQLiteDatabase,val target:String):Closeable {
    companion object {
        const val MAX_BYTES=128L*1024*1024
        const val MAX_RECORDS=250_000
        private val columns=linkedMapOf(
            "settings" to "id daily_new_limit book_id auto_play tts_fallback show_ipa show_english",
            "learning_card" to "lexeme_uid stability difficulty due_at last_review_at repetitions lapses state",
            "favorites" to "lexeme_uid",
            "study_session" to "id position revealed started_at completed_at settings_json",
            "study_session_item" to "session_id position lexeme_uid is_new rating",
            "review_log" to "id session_id position lexeme_uid rating is_new reviewed_at review_day previous_stability previous_difficulty next_stability next_difficulty next_due_at elapsed_days scheduler_version desired_retention"
        )
        fun open(file:File,target:String):LegacySource {
            Languages.get(target)
            require(file.isFile && file.length() in 100..MAX_BYTES) { "请选择完整的旧用户 SQLite 快照（最大 128 MiB）" }
            require(listOf("-wal","-journal").none{File(file.path+it).let{side->side.exists() && side.length()>0}}) { "输入含活动日志，请先取得一致的独立备份" }
            val db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY)
            try {
                require((target=="fr" && db.version in 1..2) || (target=="es" && db.version==1)) { "不支持的旧库版本或目标语言" }
                db.rawQuery("PRAGMA integrity_check",null).use {require(it.moveToFirst() && it.getString(0)=="ok" && !it.moveToNext()) { "SQLite 完整性检查失败" }}
                db.rawQuery("PRAGMA foreign_key_check",null).use {require(!it.moveToFirst()) { "旧库存在外键损坏" }}
                val objects=mutableSetOf<String>()
                db.rawQuery("SELECT type,name FROM sqlite_master WHERE type IN ('table','view','trigger') AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata'",null).use {c->
                    while(c.moveToNext()){require(c.getString(0)=="table") { "旧库包含未知视图或触发器" };objects+=c.getString(1)}
                }
                require(objects==columns.keys) { "这不是受支持的旧用户库（不能导入内容库）" }
                columns.forEach{(table,base)->
                    val expected=base.split(' ').toMutableSet()
                    if(table=="settings") {
                        expected+=if(target=="fr") "show_spanish" else "show_french"
                        if(target=="es" || db.version==2) expected+="show_chinese"
                    }
                    val actual=mutableSetOf<String>()
                    db.rawQuery("PRAGMA table_info($table)",null).use{while(it.moveToNext())actual+=it.getString(1)}
                    require(actual==expected) { "旧库字段不匹配：$table" }
                }
                val source=LegacySource(db,target)
                source.validate()
                return source
            } catch(e:Exception){db.close();throw e}
        }
        fun sha(file:File):String=file.inputStream().use{input->
            val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(64*1024)
            while(true){val read=input.read(bytes);if(read<0)break;digest.update(bytes,0,read)}
            digest.digest().hex()
        }
        fun hash(payload:JSONObject):String=MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(Charsets.UTF_8)).hex()
        private fun ByteArray.hex()=joinToString(""){"%02x".format(it)}
        fun preview(file:File,target:String,available:Set<String>):LegacyPreview {
            val before=sha(file)
            open(file,target).use{source->
                val digest=MessageDigest.getInstance("SHA-256")
                digest.update("$target:${source.db.version}\n".toByteArray())
                var settings=JSONObject();var missing=0
                source.records {type,_,row->
                    digest.update((type+":"+row.toString()+"\n").toByteArray(Charsets.UTF_8))
                    if(type=="settings")settings=row
                    if(type=="learning_card" && row.getString("lexeme_uid") !in available)missing++
                }
                require(before==sha(file)) { "预览期间输入文件发生变化" }
                return LegacyPreview(file,target,source.db.version,before,digest.digest().hex(),source.counts(),settings,missing)
            }
        }
    }
    override fun close()=db.close()
    fun counts():Map<String,Int> = columns.keys.associateWith{table->number("SELECT COUNT(*) FROM $table")}
    private fun number(sql:String):Int=db.rawQuery(sql,null).use{it.moveToFirst();it.getInt(0)}
    private fun assertNone(sql:String,message:String) {db.rawQuery(sql,null).use{require(!it.moveToFirst()){message}}}
    fun records(block:(String,String,JSONObject)->Unit) {
        for(table in listOf("settings","learning_card","favorites","study_session","review_log")) {
            val order=if(table in setOf("learning_card","favorites")) "lexeme_uid" else "id"
            db.rawQuery("SELECT * FROM $table ORDER BY $order",null).use{c->while(c.moveToNext()) {
                val row=c.json()
                if(table=="study_session") {
                    val items=JSONArray()
                    db.rawQuery("SELECT * FROM study_session_item WHERE session_id=? ORDER BY position",arrayOf(row.getLong("id").toString())).use{while(it.moveToNext())items.put(it.json())}
                    row.put("items",items)
                }
                block(table,if(order=="id") row.getLong("id").toString() else row.getString(order),row)
            }}
        }
    }
    private fun validate() {
        require(counts().values.sum().toLong()<=MAX_RECORDS) { "旧库记录超过导入上限" }
        require(number("SELECT COUNT(*) FROM settings WHERE id=1")==1 && number("SELECT COUNT(*) FROM settings")==1) { "旧库缺少唯一设置" }
        fun rowValidate(table:String,row:JSONObject) {
            fun integer(key:String,nullable:Boolean=false):Long? {
                val value=row.get(key)
                if(nullable && value===JSONObject.NULL)return null
                require(value is Long && value>=0) { "$table.$key 必须是非负整数" };return value
            }
            fun real(key:String,upper:Double?=null) {
                val value=row.get(key);require(value is Number && value.toDouble().isFinite() && value.toDouble()>=0 && (upper==null || value.toDouble()<upper)) { "$table.$key 数值无效" }
            }
            fun text(key:String) {require(row.get(key) is String && row.getString(key).isNotBlank()){ "$table.$key 不能为空" }}
            fun flag(key:String) {require(integer(key) in 0L..1L) { "$table.$key 布尔值无效" }}
            if(row.has("lexeme_uid")){text("lexeme_uid");require(row.getString("lexeme_uid").startsWith("$target:")){"旧词条不属于所选目标语言"}}
            when(table) {
                "settings"->{integer("id");require(integer("daily_new_limit")!!<=Int.MAX_VALUE);text("book_id");require(row.getString("auto_play") in setOf("NEVER","NEW_ONLY","ALL"));
                    row.keys().asSequence().filter{it.startsWith("show_") || it=="tts_fallback"}.forEach(::flag)
                    require(row.keys().asSequence().filter{it.startsWith("show_") && it!="show_ipa"}.any{row.getLong(it)==1L}){"旧库所有翻译开关均关闭"}}
                "learning_card"->{real("stability");real("difficulty");integer("due_at");integer("last_review_at",true);require(integer("repetitions")!!<=Int.MAX_VALUE);require(integer("lapses")!!<=Int.MAX_VALUE);text("state")}
                "study_session"->{require(integer("id")!!>0);integer("position");flag("revealed");integer("started_at");integer("completed_at",true);text("settings_json");JSONObject(row.getString("settings_json"))}
                "study_session_item"->{require(integer("session_id")!!>0);integer("position");flag("is_new");if(!row.isNull("rating"))require(row.getString("rating") in setOf("AGAIN","HARD","GOOD","EASY"))}
                "review_log"->{require(integer("id")!!>0);require(integer("session_id")!!>0);integer("position");flag("is_new");require(row.getString("rating") in setOf("AGAIN","HARD","GOOD","EASY"));integer("reviewed_at");text("review_day");require(LocalDate.parse(row.getString("review_day")).toString()==row.getString("review_day"));
                    for(key in listOf("previous_stability","previous_difficulty","next_stability","next_difficulty"))real(key)
                    integer("next_due_at");require(integer("elapsed_days")!!<=Int.MAX_VALUE);text("scheduler_version");real("desired_retention",1.0);require(row.getDouble("desired_retention")>0)}
            }
        }
        columns.keys.forEach{table->db.rawQuery("SELECT * FROM $table",null).use{c->while(c.moveToNext())rowValidate(table,c.json())}}
        for((table,key) in mapOf("settings" to "id","learning_card" to "lexeme_uid","favorites" to "lexeme_uid","study_session" to "id","study_session_item" to "session_id,position","review_log" to "id"))
            assertNone("SELECT 1 FROM $table GROUP BY $key HAVING COUNT(*)<>1","旧库记录身份重复：$table")
        assertNone("SELECT 1 FROM study_session_item GROUP BY session_id,lexeme_uid HAVING COUNT(*)<>1","旧会话词条重复")
        assertNone("SELECT 1 FROM review_log GROUP BY session_id,position HAVING COUNT(*)<>1","旧日志条目身份重复")
        assertNone("""SELECT 1 FROM study_session_item i LEFT JOIN study_session s ON i.session_id=s.id WHERE s.id IS NULL""","会话条目缺少所属会话")
        assertNone("""SELECT 1 FROM study_session s WHERE s.position>(SELECT COUNT(*) FROM study_session_item WHERE session_id=s.id)
            OR (SELECT COUNT(*) FROM study_session_item WHERE session_id=s.id)<>(SELECT COALESCE(MAX(position),-1)+1 FROM study_session_item WHERE session_id=s.id)
            OR (s.completed_at IS NOT NULL AND s.position<>(SELECT COUNT(*) FROM study_session_item WHERE session_id=s.id))""","旧会话位置不连续或完成状态损坏")
        assertNone("""SELECT 1 FROM study_session_item i JOIN study_session s ON i.session_id=s.id WHERE
            (i.position<s.position AND i.rating IS NULL) OR (i.position>=s.position AND i.rating IS NOT NULL)""","旧会话评分与位置不一致")
        assertNone("""SELECT 1 FROM review_log l LEFT JOIN study_session_item i ON l.session_id=i.session_id AND l.position=i.position
            WHERE i.session_id IS NULL OR i.lexeme_uid<>l.lexeme_uid OR i.is_new<>l.is_new OR i.rating IS NULL OR i.rating<>l.rating""","旧评分日志与条目不一致")
        assertNone("""SELECT 1 FROM study_session_item i WHERE i.rating IS NOT NULL AND
            (SELECT COUNT(*) FROM review_log l WHERE l.session_id=i.session_id AND l.position=i.position)<>1""","旧已评分条目缺少唯一日志")
        require(number("SELECT COUNT(*) FROM study_session WHERE completed_at IS NULL")<=1) { "旧库包含多个活动会话" }
        assertNone("SELECT 1 FROM study_session_item GROUP BY session_id HAVING COUNT(*)>50000","单个旧会话超过 50000 条目")
    }
    private fun Cursor.json()=JSONObject().also{row->for(name in columnNames.sorted()) {
        val i=getColumnIndexOrThrow(name)
        val value:Any=when(getType(i)){Cursor.FIELD_TYPE_NULL->JSONObject.NULL;Cursor.FIELD_TYPE_INTEGER->getLong(i);Cursor.FIELD_TYPE_FLOAT->getDouble(i);Cursor.FIELD_TYPE_STRING->getString(i);else->error("旧库包含不支持的二进制字段")}
        row.put(getColumnName(i),value)
    }}
}
