package za.co.cttrains.core
import org.junit.Assert.*
import org.junit.Test
class DeveloperControlTest {
 @Test fun normalNoticeIsPerVersion() { assertFalse(UpdatePrompts.showNormal(56,setOf(56)));assertTrue(UpdatePrompts.showNormal(57,setOf(56))) }
 @Test fun securityNoticeMustMatchAffectedBuildAndTarget() {
  val p=ControlPolicy("p","security",54,56,"Update",2000,"android-v1.14.0",false)
  assertTrue(UpdatePrompts.showSecurity(p,56,1000,"android-v1.14.0"))
  assertFalse(UpdatePrompts.showSecurity(p,57,1000,"android-v1.14.0"))
  assertFalse(UpdatePrompts.showSecurity(p,56,2000,"android-v1.14.0"))
  assertFalse(UpdatePrompts.showSecurity(p,56,1000,"android-v1.13.0"))
 }
 @Test fun staleOrUnboundedControlCannotBlockApp() {
  assertFalse(ControlPolicy("p",expires=100000,maintenance=true).applies(56,1000))
  assertFalse(ControlPolicy().applies(56,1000))
 }
 @Test fun sessionsExpireAndRejectUnknownRoles() {
  assertTrue(DeveloperSession("token","id","tester",200).valid(100))
  assertFalse(DeveloperSession("token","id","owner",200).valid(200))
  assertFalse(DeveloperSession("token","id","admin",200).valid(100))
 }
}
