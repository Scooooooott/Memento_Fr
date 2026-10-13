package com.scott.memento.data.user

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.scott.memento.domain.Languages
import java.time.ZoneId

/** Unified store. The schema file is also exercised by desktop contract tests. */
class UserDatabase(private val context: Context, name: String = "memento_user.db") :
    SQLiteOpenHelper(context.applicationContext,checkedName(name),null,3) {
    companion object {
        private fun checkedName(name:String):String {
            require(name=="memento_user.db" || (name.startsWith("memento-test-") && name.endsWith(".db") && '/' !in name && '\\' !in name))
            return name
        }
        fun installSchema(db:SQLiteDatabase,script:String) {
            script.split(';').map(String::trim).filter{it.isNotBlank() && !it.startsWith("PRAGMA",ignoreCase=true)}.forEach(db::execSQL)
        }
    }
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        installSchema(db,context.assets.open("user-v1.sql").bufferedReader().use{it.readText()})
        Languages.available.forEach { language ->
            db.insertOrThrow("target_language_registry",null,ContentValues().apply{put("language_id",language.id)})
        }
        db.insertOrThrow("app_settings",null,ContentValues().apply{put("id",1);put("study_zone_id",ZoneId.systemDefault().id)})
        installSchema(db,context.assets.open("user-v2-upgrade.sql").bufferedReader().use{it.readText()})
        installSchema(db,context.assets.open("user-v3-upgrade.sql").bufferedReader().use{it.readText()})
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        check(oldVersion in 1..2 && newVersion==3) { "不支持的用户数据库升级：$oldVersion → $newVersion" }
        if(oldVersion<2) installSchema(db,context.assets.open("user-v2-upgrade.sql").bufferedReader().use{it.readText()})
        installSchema(db,context.assets.open("user-v3-upgrade.sql").bufferedReader().use{it.readText()})
    }
}
