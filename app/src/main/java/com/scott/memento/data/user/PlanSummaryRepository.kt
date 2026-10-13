package com.scott.memento.data.user

import android.database.Cursor
import com.scott.memento.domain.*

/** Read-only projection. One SELECT captures plan, queue counts, logs and focus IDs consistently. */
class PlanSummaryRepository(private val store:UserDatabase) {
    fun current(target:String,planId:Long):PlanSummary? = read(
        "target_language=? AND id=?",arrayOf(target,planId.toString()),
    )

    fun latestCompleted(target:String):PlanSummary? = read(
        """target_language=? AND status='COMPLETED' AND completed_at IS NOT NULL
           AND EXISTS(SELECT 1 FROM review_log l WHERE l.plan_id=daily_plan.id
               AND l.target_language=daily_plan.target_language AND l.kind IN('NORMAL','EXTRA'))
           AND NOT EXISTS(SELECT 1 FROM daily_plan_item i WHERE i.plan_id=daily_plan.id
               AND i.target_language=daily_plan.target_language AND i.queue_kind='NORMAL' AND i.state='PENDING')
           AND NOT EXISTS(SELECT 1 FROM extra_review_batch b JOIN daily_plan_item i ON i.plan_id=b.plan_id
               AND i.target_language=b.target_language AND i.queue_kind='EXTRA'
               WHERE b.plan_id=daily_plan.id AND b.target_language=daily_plan.target_language
               AND ((b.decision='ACCEPTED' AND i.state='PENDING')
                   OR (b.decision='PENDING' AND i.state='REMOVED' AND i.offered=1)))
           ORDER BY business_date DESC,id DESC LIMIT 1""",arrayOf(target),
    )

    private fun read(where:String,args:Array<String>):PlanSummary? {
        val sql="""
            WITH selected AS (SELECT * FROM daily_plan WHERE $where),
            items AS (
                SELECT
                    COALESCE(SUM(CASE WHEN i.queue_kind='NORMAL' AND i.state='GRADED' THEN 1 ELSE 0 END),0) AS normal_graded,
                    COALESCE(SUM(CASE WHEN i.queue_kind='NORMAL' AND i.state='PENDING' THEN 1 ELSE 0 END),0) AS normal_pending,
                    COALESCE(SUM(CASE WHEN i.queue_kind='EXTRA' AND i.state='GRADED' THEN 1 ELSE 0 END),0) AS extra_graded,
                    COALESCE(SUM(CASE WHEN i.queue_kind='EXTRA' AND i.state='PENDING' THEN 1 ELSE 0 END),0) AS extra_pending,
                    COALESCE(SUM(CASE WHEN i.queue_kind='EXTRA' AND i.state='REMOVED' AND i.offered=1 THEN 1 ELSE 0 END),0) AS extra_offered
                FROM daily_plan_item i JOIN selected p ON p.id=i.plan_id AND p.target_language=i.target_language
            ),
            logs AS (SELECT l.* FROM review_log l JOIN selected p ON p.id=l.plan_id
                AND p.target_language=l.target_language AND p.business_date=l.business_date
                WHERE l.kind IN('NORMAL','EXTRA')),
            grades AS (
                SELECT
                    COUNT(DISTINCT CASE WHEN kind='NORMAL' THEN lexeme_uid END) AS normal_words,
                    COUNT(DISTINCT CASE WHEN kind='NORMAL' AND is_new=1 THEN lexeme_uid END) AS normal_new,
                    COUNT(DISTINCT CASE WHEN kind='EXTRA' THEN lexeme_uid END) AS extra_words,
                    COUNT(DISTINCT CASE WHEN kind='EXTRA' AND is_new=1 THEN lexeme_uid END) AS extra_new,
                    COALESCE(SUM(CASE WHEN kind='NORMAL' AND rating='AGAIN' THEN 1 ELSE 0 END),0) AS normal_again,
                    COALESCE(SUM(CASE WHEN kind='NORMAL' AND rating='HARD' THEN 1 ELSE 0 END),0) AS normal_hard,
                    COALESCE(SUM(CASE WHEN kind='NORMAL' AND rating='GOOD' THEN 1 ELSE 0 END),0) AS normal_good,
                    COALESCE(SUM(CASE WHEN kind='NORMAL' AND rating='EASY' THEN 1 ELSE 0 END),0) AS normal_easy,
                    COALESCE(SUM(CASE WHEN kind='EXTRA' AND rating='AGAIN' THEN 1 ELSE 0 END),0) AS extra_again,
                    COALESCE(SUM(CASE WHEN kind='EXTRA' AND rating='HARD' THEN 1 ELSE 0 END),0) AS extra_hard,
                    COALESCE(SUM(CASE WHEN kind='EXTRA' AND rating='GOOD' THEN 1 ELSE 0 END),0) AS extra_good,
                    COALESCE(SUM(CASE WHEN kind='EXTRA' AND rating='EASY' THEN 1 ELSE 0 END),0) AS extra_easy
                FROM logs
            ),
            legacy AS (
                SELECT COUNT(DISTINCT l.lexeme_uid) AS legacy_words,
                    COUNT(DISTINCT CASE WHEN l.is_new=1 THEN l.lexeme_uid END) AS legacy_new,COUNT(*) AS legacy_events
                FROM review_log l JOIN selected p ON p.target_language=l.target_language AND p.business_date=l.business_date
                WHERE l.kind='LEGACY'
            ),
            focus AS (SELECT DISTINCT lexeme_uid FROM logs WHERE rating IN('AGAIN','HARD'))
            SELECT p.*,b.decision AS extra_decision,items.*,grades.*,legacy.*,f.lexeme_uid AS focus_uid
            FROM selected p CROSS JOIN items CROSS JOIN grades CROSS JOIN legacy
            LEFT JOIN extra_review_batch b ON b.plan_id=p.id AND b.target_language=p.target_language
            LEFT JOIN focus f ON 1=1 ORDER BY f.lexeme_uid
        """.trimIndent()
        return store.readableDatabase.rawQuery(sql,args).use { cursor ->
            if(!cursor.moveToFirst()) return@use null
            val base=cursor.summary()
            val focus=buildList {
                do { cursor.nullableString("focus_uid")?.let(::add) } while(cursor.moveToNext())
            }
            base.copy(focusUids=focus)
        }
    }

    private fun Cursor.summary()=PlanSummary(
        target=string("target_language"),planId=getLong(getColumnIndexOrThrow("id")),businessDate=string("business_date"),
        zone=string("zone_id"),revision=int("revision"),planStatus=string("status"),
        completedAt=getColumnIndexOrThrow("completed_at").let{if(isNull(it)) null else getLong(it)},
        closeReason=nullableString("close_reason"),
        normal=SummaryQueueCounts(int("normal_graded"),int("normal_pending"),0,int("normal_new"),
            int("normal_words")-int("normal_new"),distribution("normal")),
        extra=SummaryQueueCounts(int("extra_graded"),int("extra_pending"),int("extra_offered"),int("extra_new"),
            int("extra_words")-int("extra_new"),distribution("extra")),
        extraDecision=nullableString("extra_decision"),focusUids=emptyList(),
        legacy=LegacyDaySummary(int("legacy_words"),int("legacy_new"),int("legacy_words")-int("legacy_new"),int("legacy_events")),
    )
    private fun Cursor.distribution(prefix:String)=RatingDistribution(int("${prefix}_again"),int("${prefix}_hard"),int("${prefix}_good"),int("${prefix}_easy"))
    private fun Cursor.int(name:String)=getInt(getColumnIndexOrThrow(name))
    private fun Cursor.string(name:String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullableString(name:String)=getColumnIndexOrThrow(name).let{if(isNull(it)) null else getString(it)}
}
