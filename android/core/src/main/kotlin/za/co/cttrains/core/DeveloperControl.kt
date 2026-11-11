package za.co.cttrains.core

import kotlinx.serialization.Serializable

@Serializable data class DeveloperSession(val token: String="",val id: String="",val role: String="tester",val expires: Long=0) {
    fun valid(now: Long)=token.isNotBlank() && role in setOf("tester","owner") && expires>now
}
@Serializable data class ControlPolicy(val id: String="",val kind: String="normal",val minCode: Long=1,val maxCode: Long=Long.MAX_VALUE,
    val message: String="",val expires: Long=0,val releaseTag: String="",val maintenance: Boolean=false) {
    fun applies(code: Long,now: Long)=id.isNotBlank() && minCode>0 && maxCode>=minCode && code in minCode..maxCode && expires>now && expires<=now+86400 && kind in setOf("normal","security")
}
object UpdatePrompts {
    fun showNormal(code: Long,seen: Set<Long>)=code !in seen
    fun showSecurity(policy: ControlPolicy,currentCode: Long,now: Long,candidateTag: String?)=
        policy.applies(currentCode,now) && policy.kind=="security" && (policy.releaseTag.isBlank() || policy.releaseTag==candidateTag)
}
