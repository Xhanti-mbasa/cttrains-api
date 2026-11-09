plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { systemProperty("cttrains.assets", rootProject.file("app/src/main/assets").absolutePath) }
