package com.scott.memento

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.user.UserDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A malformed named fixture exercises real initialization failure, never a production database. */
class LoadingRecoveryInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val name="memento-test-ui6-loading.db"
    private fun property(key:String)=ProcessBuilder("/system/bin/getprop",key).start().let {process->
        process.inputStream.bufferedReader().use {it.readText().trim()}.also {check(process.waitFor()==0)}
    }
    private fun guard() {
        check(context.packageName=="com.scott.memento")
        check(Build.HARDWARE in setOf("ranchu","goldfish") || Build.FINGERPRINT.contains("generic"))
        check(property("ro.kernel.qemu")=="1")
        check(listOf("ro.boot.qemu.avd_name","ro.kernel.qemu.avd_name").any{property(it)=="MementoApi35"})
        check(name=="memento-test-ui6-loading.db")
    }
    private fun await(tag:String)=compose.waitUntil(30000){compose.onAllNodesWithTag(tag,useUnmergedTree=true)
        .fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    @Test fun dismissingInitializationErrorKeepsManualRetryAndRecoveryCreatesNoQuota() {
        guard();context.deleteDatabase(name)
        try {
            UserDatabase(context,name).use{it.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Not/AZone'")}
            val intent=Intent(context,MainActivity::class.java).putExtra("qa_user_database",name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                await("app_error");await("app_retry")
                compose.onNodeWithTag("error_dismiss").assertIsDisplayed().performClick()
                compose.onNodeWithTag("app_error",useUnmergedTree=true).assertDoesNotExist()
                compose.onNodeWithTag("app_retry").assertIsDisplayed().assertIsEnabled()
                // Repair only the deliberately malformed fixture, then exercise the actual retry action.
                UserDatabase(context,name).use{it.writableDatabase.execSQL("UPDATE app_settings SET study_zone_id='Asia/Shanghai'")}
                compose.onNodeWithTag("app_retry").performClick();await("onboarding")
                compose.onNodeWithTag("app_retry").assertDoesNotExist()
                compose.onNodeWithTag("study_save").assertIsNotEnabled()
                UserDatabase(context,name).use{store->
                    listOf("daily_plan","daily_plan_item","review_log","learning_card","language_settings").forEach{table->
                        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use{cursor->
                            assertTrue(cursor.moveToFirst());assertEquals("Recovery must not configure or grade",0,cursor.getInt(0))
                        }
                    }
                }
            }
        } finally {context.deleteDatabase(name)}
    }
}
