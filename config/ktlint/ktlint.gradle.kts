// ktlint configuration
// T00: Code style enforcement

plugins {
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    version.set("0.50.0")
    android.set(true)
    outputColorName.set("RED")
    // Pre-existing ktlint violations are captured in the baseline file.
    // The gate only fails on NEW violations. Remove entries as they are
    // fixed in future cleanup PRs.
    baselineFile.set(file("${rootDir}/app/config/ktlint/baseline.xml"))
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.SARIF)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
        include("**/*.kt")
        include("**/*.kts")
    }
    kotlinScript {
        warningsAsErrors.set(false)
        outputColorName.set("RED")
    }
}
