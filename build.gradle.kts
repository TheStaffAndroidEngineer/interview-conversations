plugins { alias(libs.plugins.kotlinJvm); application }
kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) } }
java { sourceCompatibility = JavaVersion.VERSION_26; targetCompatibility = JavaVersion.VERSION_26 }
tasks.withType<JavaCompile>().configureEach { options.release.set(26) }
val implementationChoice = providers.gradleProperty("implementation").orElse("reference").get()
require(implementationChoice in setOf("reference", "learner"))
kotlin.sourceSets.named("main") { kotlin.srcDir("src/$implementationChoice/kotlin") }
dependencies {
    implementation(libs.coroutinesCore)
    testImplementation(libs.coroutinesTest)
    testImplementation(libs.junitJupiter)
    testRuntimeOnly(libs.junitPlatformLauncher)
}
tasks.withType<Test>().configureEach { useJUnitPlatform(); testLogging { events("passed", "failed", "skipped") } }
application { mainClass.set("staff.android.conversations.InvestigationKt") }
