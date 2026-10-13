package com.scott.memento.data.migration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.scott.memento.data.user.StudyRepository
import com.scott.memento.data.user.SqliteWrites
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.StudyContentIndex
import com.scott.memento.domain.StudyMode
import com.scott.memento.domain.StudyPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

data class LegacyPreferenceChoice(val policy:StudyPolicy,val book:String)
data class LegacyImportOptions(val sourceName:String,val oldZone:String,val preferences:LegacyPreferenceChoice?=null) {
    fun identity(target:String):String {
        val name=Normalizer.normalize(sourceName.trim(),Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        require(name.length in 1..64 && name.none{it.isISOControl()}) { "请填写 1–64 字的稳定备份来源名称" }
        return "legacy:$target:$name"
    }
}
data class LegacyImportResult(val batchId:Long,val repeated:Boolean,val report:JSONObject)
class LegacyImportFailure(val report:JSONObject,cause:Exception):IllegalArgumentException(cause.message,cause)

/** Whole-batch transaction. Conflicting incoming versions are archived, never silently discarded. */
class LegacyImporter(private val store:UserDatabase,private val clock:Clock=Clock.systemUTC()) {
    fun import(preview:LegacyPreview,index:StudyContentIndex,options:LegacyImportOptions):LegacyImportResult {
        try {return perform(preview,index,options)}
        catch(e:LegacyImportFailure){throw e}
        catch(e:Exception){throw LegacyImportFailure(failureReport(preview,options,"validation",null,e),e)}
    }
    private fun failureReport(preview:LegacyPreview,options:LegacyImportOptions,phase:String,id:String?,error:Exception)=JSONObject()
        .put("status","FAILED").put("rolledBack",true).put("target",preview.target).put("sourceName",options.sourceName)
        .put("inputSha256",preview.inputSha).put("inputSchemaVersion",preview.version).put("phase",phase).put("sourceRecordId",id ?: JSONObject.NULL)
        .put("errorType",error.javaClass.simpleName).put("error",error.message ?: "导入失败")
    private fun perform(preview:LegacyPreview,index:StudyContentIndex,options:LegacyImportOptions):LegacyImportResult {
        require(preview.target==index.target) { "导入目标与内容索引不一致" }
        val sourceId=options.identity(index.target)
        val zone=ZoneId.of(options.oldZone).id
        val fresh=LegacySource.preview(preview.file,preview.target,index.available)
        require(fresh.inputSha==preview.inputSha && fresh.logicalSha==preview.logicalSha) { "输入与预览不一致，请重新选择文件" }
        options.preferences?.let {choice->
            require(choice.book in index.books) { "请选择可用的导入词书" }
            if(fresh.settings.getInt("daily_new_limit")==0) require(choice.policy.mode==StudyMode.REVIEW_ALL) { "旧零新词设置必须导入为全部复习模式" }
        }
        if(fresh.settings.getInt("daily_new_limit")==0) require(options.preferences?.policy?.mode==StudyMode.REVIEW_ALL) { "旧零新词设置必须导入为全部复习模式" }
        LegacySource.open(preview.file,preview.target).use { source ->
            val db=store.writableDatabase
            db.beginTransaction()
            var phase="batch";var currentId:String?=null
            try {
                val previousBatch=db.rawQuery("SELECT b.id,b.report_json FROM migration_snapshot s JOIN migration_batch b ON b.id=s.batch_id WHERE s.source_identity=? AND s.logical_sha256=?",arrayOf(sourceId,preview.logicalSha)).use{c->
                    if(c.moveToFirst()) LegacyImportResult(c.getLong(0),true,JSONObject(c.getString(1))) else null
                }
                if(previousBatch!=null){db.setTransactionSuccessful();return previousBatch}
                val now=clock.millis();require(now>=0)
                val report=JSONObject().put("sourceIdentity",sourceId).put("target",index.target).put("inputSha256",preview.inputSha)
                    .put("logicalSha256",preview.logicalSha).put("schemaVersion",preview.version).put("importedAt",now)
                    .put("legacyZoneAnnotation",zone).put("legacyZoneWasNotStored",true).put("counts",JSONObject(preview.counts))
                    .put("missingContentCards",preview.unavailable).put("settingsRestored",options.preferences!=null)
                val conflicts=JSONArray();val totals=linkedMapOf("inserted" to 0,"updated" to 0,"duplicates" to 0,"conflicts" to 0)
                val batch=db.insertOrThrow("migration_batch",null,ContentValues().apply {
                    put("source_identity",sourceId);put("input_sha256",preview.inputSha);put("input_schema_version",preview.version)
                    put("imported_at",now);put("report_json","{}")
                })
                var high=0L
                source.records {type,id,row->
                    phase=type;currentId=id
                    val hash=LegacySource.hash(row)
                    val accepted=db.rawQuery("SELECT record_sha256,target_record_id FROM migration_ledger WHERE source_identity=? AND record_type=? AND source_record_id=?",arrayOf(sourceId,type,id)).use{c->if(c.moveToFirst())c.getString(0) to c.getString(1) else null}
                    var disposition="duplicates";var reason:String?=null;var targetId=accepted?.second ?: "$sourceId:$type:$id"
                    if(accepted?.first!=hash) {
                        disposition=when(type) {
                            "learning_card"->{
                                val current=db.rawQuery("SELECT lexeme_uid,stability,difficulty,due_at,last_review_at,repetitions,lapses,state FROM learning_card WHERE target_language=? AND lexeme_uid=?",arrayOf(index.target,id)).use{c->if(c.moveToFirst())cardJson(c) else null}
                                when {
                                    current==null->{writeCard(db,index,row);"inserted"}
                                    LegacySource.hash(current)==hash->"duplicates"
                                    accepted!=null && LegacySource.hash(current)==accepted.first && noRegression(current,row)->{writeCard(db,index,row);"updated"}
                                    else->{reason="保留当前 FSRS 状态，旧版本完整归档";"conflicts"}
                                }
                            }
                            "favorites"->{
                                if(accepted==null){val result=db.insertWithOnConflict("favorites",null,ContentValues().apply{put("target_language",index.target);put("lexeme_uid",id)},SQLiteDatabase.CONFLICT_IGNORE);if(result==-1L) "duplicates" else "inserted"}
                                else "duplicates"
                            }
                            "review_log"->{
                                if(accepted==null) {
                                    targetId=db.insertOrThrow("review_log",null,values(row,setOf("id","session_id","position","review_day")).apply {
                                        put("target_language",index.target);put("kind","LEGACY");put("business_date",row.getString("review_day"))
                                        put("legacy_review_day",row.getString("review_day"));put("zone_id",zone);put("source_identity",sourceId)
                                        put("source_record_id",id);put("migration_batch_id",batch)
                                    }).toString();"inserted"
                                } else {reason="同一旧日志 ID 的内容改变，保留已导入日志";"conflicts"}
                            }
                            "study_session"->{
                                val current=db.rawQuery("SELECT payload_json FROM legacy_session_history WHERE source_identity=? AND source_session_id=?",arrayOf(sourceId,id)).use{c->if(c.moveToFirst())JSONObject(c.getString(0)) else null}
                                if(current==null || sameSessionProgress(current,row)) {
                                    SqliteWrites.updateOrInsert(db,"legacy_session_history",ContentValues().apply {
                                        put("source_identity",sourceId);put("target_language",index.target);put("source_session_id",id)
                                        put("payload_json",row.toString());put("batch_id",batch)
                                    },"UPDATE legacy_session_history SET payload_json=?,batch_id=? WHERE source_identity=? AND source_session_id=?",
                                        arrayOf(row.toString(),batch,sourceId,id))
                                    if(current==null) "inserted" else "updated"
                                } else {reason="旧会话 ID 重用或进度倒退，保留此前归档";"conflicts"}
                            }
                            "settings"->if(accepted==null) "inserted" else "updated"
                            else->error("不支持的旧记录")
                        }
                    }
                    if(type=="learning_card" && !row.isNull("last_review_at"))high=maxOf(high,row.getLong("last_review_at"))
                    if(type=="review_log")high=maxOf(high,row.getLong("reviewed_at"))
                    totals[disposition]=totals.getValue(disposition)+1
                    if(reason!=null)conflicts.put(JSONObject().put("type",type).put("id",id).put("reason",reason))
                    db.insertWithOnConflict("legacy_record_history",null,ContentValues().apply {
                        put("source_identity",sourceId);put("record_type",type);put("source_record_id",id);put("record_sha256",hash)
                        put("payload_json",row.toString());put("disposition",disposition);put("reason",reason);put("batch_id",batch)
                    },SQLiteDatabase.CONFLICT_IGNORE)
                    if(disposition!="conflicts")SqliteWrites.updateOrInsert(db,"migration_ledger",ContentValues().apply {
                        put("source_identity",sourceId);put("record_type",type);put("source_record_id",id)
                        put("target_record_id",targetId);put("record_sha256",hash);put("batch_id",batch)
                    },"""UPDATE migration_ledger SET target_record_id=?,record_sha256=?,batch_id=?
                        WHERE source_identity=? AND record_type=? AND source_record_id=?""",arrayOf(targetId,hash,batch,sourceId,type,id))
                }
                phase="preferences_and_plan";currentId=null
                options.preferences?.let{choice->
                    SqliteWrites.updateOrInsert(db,"language_settings",ContentValues().apply {
                        put("target_language",index.target);put("study_mode",choice.policy.mode.name);put("daily_target",choice.policy.dailyTarget)
                        put("book_id",choice.book);put("auto_play",fresh.settings.getString("auto_play"))
                        put("tts_fallback",fresh.settings.getInt("tts_fallback"));put("show_reading",fresh.settings.getInt("show_ipa"))
                    },"""UPDATE language_settings SET study_mode=?,daily_target=?,book_id=?,auto_play=?,tts_fallback=?,
                        show_reading=?,revision=revision+1 WHERE target_language=?""",
                        arrayOf(choice.policy.mode.name,choice.policy.dailyTarget,choice.book,fresh.settings.getString("auto_play"),
                            fresh.settings.getInt("tts_fallback"),fresh.settings.getInt("show_ipa"),index.target))
                }
                SqliteWrites.updateOrInsert(db,"plan_refresh_request",ContentValues().apply {
                    put("target_language",index.target);put("requested_at",now)
                },"UPDATE plan_refresh_request SET requested_at=? WHERE target_language=?",arrayOf(now,index.target))
                db.execSQL("UPDATE study_clock SET max_observed_at=MAX(max_observed_at,?) WHERE id=1",arrayOf(high))
                val snapshot=StudyRepository(store,clock).ensureDailyPlan(index)
                report.put("planGuard",snapshot.guard.name).put("businessDate",snapshot.businessDate ?: JSONObject.NULL)
                    .put("remainingNormal",snapshot.normalRemaining).put("totals",JSONObject(totals)).put("conflicts",conflicts)
                    .put("batchId",batch)
                require(preview.inputSha==LegacySource.sha(preview.file)) { "导入期间输入文件发生变化，已撤销本批次" }
                db.execSQL("INSERT INTO migration_snapshot VALUES(?,?,?)",arrayOf(sourceId,preview.logicalSha,batch))
                db.execSQL("UPDATE migration_batch SET report_json=? WHERE id=?",arrayOf(report.toString(),batch))
                db.setTransactionSuccessful()
                return LegacyImportResult(batch,false,report)
            } catch(e:Exception) {
                throw LegacyImportFailure(failureReport(preview,options,phase,currentId,e),e)
            } finally {db.endTransaction()}
        }
    }
    private fun noRegression(current:JSONObject,incoming:JSONObject):Boolean =
        (if(incoming.isNull("last_review_at")) 0 else incoming.getLong("last_review_at")) >= (if(current.isNull("last_review_at")) 0 else current.getLong("last_review_at")) &&
        incoming.getLong("repetitions")>=current.getLong("repetitions") && incoming.getLong("lapses")>=current.getLong("lapses")
    private fun sameSessionProgress(current:JSONObject,incoming:JSONObject):Boolean {
        if(current.getLong("started_at")!=incoming.getLong("started_at") || current.getInt("position")>incoming.getInt("position") || (!current.isNull("completed_at") && current.opt("completed_at")!=incoming.opt("completed_at")))return false
        val old=current.getJSONArray("items");val next=incoming.getJSONArray("items")
        return (0 until current.getInt("position")).all{old.getJSONObject(it).toString()==next.getJSONObject(it).toString()}
    }
    private fun values(row:JSONObject,exclude:Set<String> = emptySet())=ContentValues().apply {
        row.keys().forEach{key->if(key !in exclude)when(val value=row.get(key)) {
            JSONObject.NULL->putNull(key);is Long->put(key,value);is Int->put(key,value);is Double->put(key,value);is String->put(key,value);else->error("不支持的旧数据类型")
        }}
    }
    private fun writeCard(db:SQLiteDatabase,index:StudyContentIndex,row:JSONObject) {
        val uid=row.getString("lexeme_uid")
        val value=values(row).apply{put("target_language",index.target);put("content_available",if(uid in index.available) 1 else 0)}
        if(db.update("learning_card",value,"target_language=? AND lexeme_uid=?",arrayOf(index.target,uid))==0)db.insertOrThrow("learning_card",null,value)
    }
    private fun cardJson(c:android.database.Cursor)=JSONObject().apply {c.columnNames.sorted().forEach{key->
        val i=c.getColumnIndexOrThrow(key)
        put(key,when(c.getType(i)){android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL;android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i);android.database.Cursor.FIELD_TYPE_FLOAT->c.getDouble(i);else->c.getString(i)})
    }}
}
