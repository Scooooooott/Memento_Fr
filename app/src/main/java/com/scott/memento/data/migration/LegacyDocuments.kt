package com.scott.memento.data.migration

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/** SAF grants read access only. The immutable working copy belongs to this app's cache. */
object LegacyDocuments {
    fun copy(context:Context,uri:Uri):File {
        require(uri.scheme=="content") { "请通过系统文件选择器选择备份" }
        val directory=File(context.cacheDir,"legacy-import").apply{check(isDirectory || mkdirs())}
        val file=File(directory,"${UUID.randomUUID()}.db")
        try {
            val input=checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选备份" }
            input.use{source->file.outputStream().use{output->
                val buffer=ByteArray(64*1024);var total=0L
                while(true){val count=source.read(buffer);if(count<0)break;total+=count;require(total<=LegacySource.MAX_BYTES){"备份超过 128 MiB"};output.write(buffer,0,count)}
            }}
            return file
        } catch(e:Exception){file.delete();throw e}
    }
}
