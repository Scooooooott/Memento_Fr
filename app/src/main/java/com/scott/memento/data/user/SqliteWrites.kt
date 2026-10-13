package com.scott.memento.data.user

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase

/** Keeps row identity and constraint failures without requiring SQLite 3.24 UPSERT syntax. */
internal object SqliteWrites {
    fun updateOrInsert(
        db:SQLiteDatabase,
        table:String,
        insertValues:ContentValues,
        updateSql:String,
        updateArgs:Array<out Any?>,
    ) {
        check(db.inTransaction()) { "UPDATE/INSERT must run inside the caller's write transaction" }
        val updated=db.compileStatement(updateSql).use { statement ->
            updateArgs.forEachIndexed { index,value -> DatabaseUtils.bindObjectToProgram(statement,index+1,value) }
            statement.executeUpdateDelete()
        }
        check(updated in 0..1) { "UPDATE/INSERT requires a unique row key" }
        if(updated==0) db.insertOrThrow(table,null,insertValues)
    }
}
