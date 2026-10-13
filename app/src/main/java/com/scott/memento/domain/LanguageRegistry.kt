package com.scott.memento.domain

import java.util.IllformedLocaleException
import java.util.Locale

/** One validation boundary for selection, restoration, import and package registration. */
class LanguageCapabilities(specifications:List<LanguageSpec>) {
    val languages=specifications.map{it.copy(learningLanguages=it.learningLanguages.toSet())}
    init {
        require(languages.isNotEmpty() && languages.map{it.id}.distinct().size==languages.size)
        languages.forEach{spec->
            require(spec.id==canonical(spec.id) && spec.id!="en") { "目标语言标识必须规范且不能是英语" }
            require(spec.label.isNotBlank() && spec.defaultBook.isNotBlank())
            require(spec.asset.isNotBlank() && !spec.asset.startsWith('/') && ".." !in spec.asset && '\\' !in spec.asset)
            require(canonical(spec.locale)==spec.id) { "发音地区与目标语言不一致" }
            require(spec.learningLanguages.isNotEmpty() && spec.learningLanguages.all{it==canonical(it) && it!=spec.id})
        }
    }
    fun requireTargetId(tag:String):String {
        val id=canonical(tag)
        require(id!="en") { "英语不能作为目标语言" }
        require(languages.any{it.id==id}) { "目标语言内容尚不可用：$tag" }
        return id
    }
    fun requireLearningId(target:String,tag:String):String {
        val id=canonical(tag)
        require(id in get(target).learningLanguages) { "该学习语言的内容尚不可用：$tag" }
        return id
    }
    fun get(target:String)=languages.single{it.id==requireTargetId(target)}
    fun requireUid(target:String,uid:String):String {
        val prefix=requireTargetId(target)+":"
        require(uid.startsWith(prefix) && uid.length>prefix.length) { "词条与目标语言不一致" }
        return uid
    }
    companion object {
    private fun canonical(tag: String): String {
        require(tag.isNotBlank() && tag == tag.trim()) { "语言标识不能为空或包含外部空格" }
        val locale = try { Locale.Builder().setLanguageTag(tag.replace('_','-')).build() }
        catch(error: IllformedLocaleException) { throw IllegalArgumentException("无效语言标识：$tag",error) }
        val language = when(locale.language.lowercase(Locale.ROOT)) {
            "eng" -> "en"
            "fra", "fre" -> "fr"
            "spa" -> "es"
            else -> locale.language.lowercase(Locale.ROOT)
        }
        require(language.isNotBlank()) { "语言标识缺少有效语言" }
        return if(language == "zh") {
            if(locale.script == "Hant" || locale.country in setOf("TW","HK","MO")) "zh-Hant" else "zh-Hans"
        } else language
    }
    }
}

/** Production entry points always use the six verified French/Spanish directions. */
object LanguageRegistry {
    val formal:LanguageCapabilities by lazy{LanguageCapabilities(Languages.available)}
    fun requireTargetId(tag:String)=formal.requireTargetId(tag)
    fun requireLearningId(target:String,tag:String)=formal.requireLearningId(target,tag)
    fun requireUid(target:String,uid:String)=formal.requireUid(target,uid)
}

enum class StudyMode { STANDARD, REVIEW_ALL }
data class StudyPolicy(val mode: StudyMode, val dailyTarget: Int?) {
    init {
        require(if(mode==StudyMode.STANDARD) dailyTarget != null && dailyTarget > 0 else dailyTarget == null) {
            "普通模式需要正数总目标；只复习全部模式不设总目标"
        }
    }
}
data class LearningIdentity private constructor(val targetLanguage: String, val lexemeUid: String,val registry:LanguageCapabilities) {
    init {
        require(targetLanguage==registry.requireTargetId(targetLanguage)) { "身份必须使用规范目标语言" }
        registry.requireUid(targetLanguage,lexemeUid)
    }
    companion object {
        fun of(target: String, uid: String,registry:LanguageCapabilities=LanguageRegistry.formal): LearningIdentity {
            val id=registry.requireTargetId(target)
            return LearningIdentity(id,registry.requireUid(id,uid),registry)
        }
    }
}
data class RatingRequest(val identity: LearningIdentity, val planId: Long, val revision: Int,
                         val itemId: Long, val requestId: String) {
    init { require(planId>0 && revision>0 && itemId>0 && requestId.isNotBlank()) }
}
