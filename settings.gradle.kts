pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}")
        }
    }
    repositories {
        file(".local-maven-root").takeIf { it.isFile }?.readText()?.trim()?.let { local ->
            maven { url = uri("$local/repo-local") }
        }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        file(".local-maven-root").takeIf { it.isFile }?.readText()?.trim()?.let { local ->
            maven {
                url = uri("$local/repo-shadow")
                content { excludeGroup("com.android.tools.lint") }
            }
        }
        google(); mavenCentral()
    }
}
rootProject.name = "CarBridge-HeadSwap"
include(":app", ":sdk")
