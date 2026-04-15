import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

dependencyLocking {
    lockAllConfigurations()
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }

    tasks.withType<Test>().configureEach {
        testLogging {
            events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

tasks.register("verify") {
    group = "verification"
    description = "Run strict static analysis and unit tests."
    dependsOn(
        ":app:ktlintCheck",
        ":app:detekt",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
    )
}
