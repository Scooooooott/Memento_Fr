package com.scott.memento

import androidx.test.platform.app.InstrumentationRegistry
import com.scott.memento.data.content.ContentCatalog
import com.scott.memento.data.content.ContentPackageDescription
import com.scott.memento.domain.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object ExtensionFixture {
    val manifest:JSONObject get()=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("manifest.json").bufferedReader().use{it.readText()})
    fun specifications():List<LanguageSpec> {
        val rows=manifest.getJSONObject("packages")
        return rows.keys().asSequence().sorted().map{target->val row=rows.getJSONObject(target)
            LanguageSpec(target,row.getString("label"),row.getString("asset"),row.getString("pronunciation_locale"),
                row.getJSONArray("learning_languages").let{array->(0 until array.length()).map{array.getString(it)}.toSet()},row.getString("default_book"))
        }.toList()
    }
    fun description(target:String)=ContentPackageDescription.from(manifest.getJSONObject("packages").getJSONObject(target),manifest.getString("normalization_version"))
    fun copy(target:String):File {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"p7-fixtures").apply{check(isDirectory || mkdirs())}
        return File(directory,"$target-${UUID.randomUUID()}.db").also{file->InstrumentationRegistry.getInstrumentation().context.assets.open("$target.db").use{input->file.outputStream().use{output->input.copyTo(output)}}}
    }
    fun <T> read(target:String,block:(ContentCatalog,LanguageCapabilities)->T):T {
        val file=copy(target);val registry=LanguageCapabilities(specifications())
        try{return ContentCatalog.openVerifiedPackage(file,registry.get(target),description(target),registry).use{block(it,registry)}}
        finally{file.delete()}
    }
}
