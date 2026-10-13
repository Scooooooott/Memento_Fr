package com.scott.memento

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.migration.LegacySource
import com.scott.memento.data.user.UserDatabase
import com.scott.memento.domain.Languages
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LegacyImportUiInstrumentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val name="memento-test-import-ui.db"
    private fun launch()=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("qa_user_database",name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    // DocumentsUI can temporarily own the resumed window while the app has no Compose root.
    private fun await(tag:String){compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
    private fun click(tag:String){await(tag);compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}};compose.onNodeWithTag(tag).performClick()}
    private fun number(sql:String)=UserDatabase(context,name).use{it.readableDatabase.rawQuery(sql,null).use{c->c.moveToFirst();c.getInt(0)}}
    private fun onboard(){await("study_target");compose.waitUntil(30000){compose.onAllNodesWithTag("study_target").fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}};compose.onNodeWithTag("study_target").performTextReplacement("3");compose.onNodeWithTag("study_target").performImeAction();click("study_save");await("open_study");click("nav_settings");await("open_import");compose.onNodeWithTag("open_import").performScrollTo().assertIsDisplayed();click("open_import");await("legacy_import")}
    private fun fixture():File {
        val file=LegacyFixture.create(File(context.cacheDir,"memento-qa-import/${UUID.randomUUID()}.db"))
        val uids=ContentCatalog(context,Languages.get("fr")).use{it.studyIndex("zh-Hans").books.getValue("flelex-a1-high-1000").take(2)}
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            for((old,new) in listOf("fr:a:noun:1" to uids[0],"fr:b:noun:1" to uids[1]))for(table in listOf("learning_card","favorites","study_session_item","review_log"))db.execSQL("UPDATE $table SET lexeme_uid=? WHERE lexeme_uid=?",arrayOf(new,old))
            db.execSQL("UPDATE settings SET book_id='flelex-a1-high-1000'")
        };return file
    }
    private fun screenshot(label:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(350)
        val directory=File(context.getExternalFilesDir(null),"qa").apply{mkdirs()};val bitmap=instrument.uiAutomation.takeScreenshot()
        FileOutputStream(File(directory,label+".png")).use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun safResultPreviewsWithoutWritesThenImportsOnceAndSurvivesRecreation() {
        context.deleteDatabase(name);val file=fixture();val hash=LegacySource.sha(file)
        val uri=FileProvider.getUriForFile(context,"com.scott.memento.qa.importfiles",file)
        val filter=IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);addDataType("*/*")}
        val monitor=instrument.addMonitor(filter,Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri)),true)
        try {launch().use{scenario->
            onboard();click("import_choose_file");await("import_preview")
            assertEquals(0,number("SELECT COUNT(*) FROM migration_batch"));assertEquals(0,number("SELECT COUNT(*) FROM learning_card"))
            compose.onNodeWithTag("import_source").performScrollTo().performTextReplacement("主手机")
            scenario.recreate();await("import_preview");compose.onNodeWithTag("import_source").performScrollTo().assertTextContains("主手机")
            compose.onNodeWithTag("import_confirm_snapshot").performScrollTo().performClick();click("import_commit");await("import_result")
            assertEquals(1,number("SELECT COUNT(*) FROM migration_batch"));assertEquals(1,number("SELECT COUNT(*) FROM review_log"));assertEquals(2,number("SELECT COUNT(*) FROM learning_card"))
            screenshot("p6-import-result")
            scenario.recreate();await("import_result");compose.onNodeWithTag("import_choose_file").performScrollTo();click("import_choose_file");await("import_commit")
            compose.onNodeWithTag("import_confirm_snapshot").performScrollTo().assertIsOff()
            compose.onNodeWithTag("import_confirm_snapshot").performScrollTo().performClick();click("import_commit");await("import_result")
            compose.onNodeWithTag("import_result").performScrollTo().assertTextContains("未重复写入",substring=true)
            assertEquals(1,number("SELECT COUNT(*) FROM migration_batch"));assertEquals(hash,LegacySource.sha(file))
        }} finally {instrument.removeMonitor(monitor);file.delete();context.deleteDatabase(name)}
    }
    @Test fun actualSystemDocumentPickerOpensAndCancelLeavesDataUntouched() {
        context.deleteDatabase(name)
        try {launch().use {scenario->
            onboard();click("import_choose_file")
            val deadline=android.os.SystemClock.uptimeMillis()+15000
            var opened=false
            while(android.os.SystemClock.uptimeMillis()<deadline) {
                val root=instrument.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()?.contains("documentsui")==true){opened=true;break}
                android.os.SystemClock.sleep(100)
            }
            assertTrue("System document picker did not open",opened)
            screenshot("p6-system-file-picker")
            // DocumentsUI remembers the last selected folder; Back may first navigate its parent.
            val cancelDeadline=android.os.SystemClock.uptimeMillis()+10000
            var returned=false
            while(android.os.SystemClock.uptimeMillis()<cancelDeadline) {
                val active=instrument.uiAutomation.rootInActiveWindow?.packageName?.toString()
                if(active==context.packageName){returned=true;break}
                // A stale picker window must not cause Back to be sent into the resumed app.
                if(active?.contains("documentsui")==true && scenario.state!=androidx.lifecycle.Lifecycle.State.RESUMED)
                    instrument.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                android.os.SystemClock.sleep(350)
            }
            assertTrue("System picker did not cancel back to Memento",returned)
            await("legacy_import");compose.onNodeWithTag("import_preview").assertDoesNotExist();assertEquals(0,number("SELECT COUNT(*) FROM migration_batch"))
        }} finally {context.deleteDatabase(name)}
    }
    /** No ActivityResult monitor: DocumentsUI returns the selected Downloads content URI. */
    @Test fun actualSystemPickerSelectsBackupPreviewsAndCommitsWithoutChangingSource() {
        context.deleteDatabase(name)
        val file=fixture();val hash=LegacySource.sha(file)
        val filename="memento-qa-${UUID.randomUUID()}.db"
        val resolver=context.contentResolver
        val uri=checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,filename)
            put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/memento-qa")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use {output->file.inputStream().use{it.copyTo(output)}}
            resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            launch().use {
                onboard();click("import_choose_file")
                val deadline=android.os.SystemClock.uptimeMillis()+20000
                var selected=false
                var drawerOpened=false;var downloadsOpened=false;var folderOpened=false
                fun find(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
                    if(node==null)return null
                    if(predicate(node))return node
                    for(i in 0 until node.childCount)find(node.getChild(i),predicate)?.let{return it}
                    return null
                }
                fun activate(node:AccessibilityNodeInfo?):Boolean {
                    var action=node
                    while(action!=null && !action.isClickable)action=action.parent
                    return action?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                }
                while(android.os.SystemClock.uptimeMillis()<deadline) {
                    val root=instrument.uiAutomation.rootInActiveWindow
                    if(root?.packageName?.toString()?.contains("documentsui")==true) {
                        val document=find(root){it.text?.toString()==filename}
                        if(document!=null) {
                            screenshot("p8-system-file-selected")
                            selected=activate(document)
                            if(selected)break
                        } else if(!drawerOpened) {
                            drawerOpened=activate(find(root){it.contentDescription?.toString()=="Show roots"})
                        } else if(!downloadsOpened) {
                            downloadsOpened=activate(find(root){it.text?.toString()=="Downloads"})
                        } else if(!folderOpened) {
                            folderOpened=activate(find(root){it.text?.toString()=="memento-qa"})
                        }
                    }
                    android.os.SystemClock.sleep(100)
                }
                if(!selected) {
                    fun tree(node:AccessibilityNodeInfo?):String=if(node==null)"null" else buildString {
                        appendLine("${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable}")
                        for(i in 0 until node.childCount)append(tree(node.getChild(i)))
                    }
                    File(context.getExternalFilesDir(null),"qa/p8-picker-failure-tree.txt").writeText(tree(instrument.uiAutomation.rootInActiveWindow))
                    screenshot("p8-picker-failure")
                }
                assertTrue("The actual system picker did not display/select the test backup",selected)
                await("import_preview")
                assertEquals(0,number("SELECT COUNT(*) FROM migration_batch"))
                assertEquals(0,number("SELECT COUNT(*) FROM learning_card"))
                compose.onNodeWithTag("import_source").performScrollTo().performTextReplacement("系统选择夹具")
                compose.onNodeWithTag("import_confirm_snapshot").performScrollTo().performClick()
                click("import_commit");await("import_result")
                assertEquals(1,number("SELECT COUNT(*) FROM migration_batch"))
                assertEquals(1,number("SELECT COUNT(*) FROM review_log"))
                assertEquals(2,number("SELECT COUNT(*) FROM learning_card"))
                screenshot("p8-system-import-result")
                assertEquals(hash,LegacySource.sha(file))
                val copied=File(context.cacheDir,"p8-selected-copy.db")
                try {
                    checkNotNull(resolver.openInputStream(uri)).use{input->copied.outputStream().use{input.copyTo(it)}}
                    assertEquals(hash,LegacySource.sha(copied))
                } finally {copied.delete()}
            }
        } finally {resolver.delete(uri,null,null);file.delete();context.deleteDatabase(name)}
    }
}
