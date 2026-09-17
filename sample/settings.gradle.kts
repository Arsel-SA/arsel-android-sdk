pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The Arsel SDK is resolved from here after you run, in the repo root:
        //   ./gradlew publishToMavenLocal
        // Exclusive, because once a version is on Maven Central an ordinary repository list would
        // silently resolve the released AAR instead of the one just built.
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("sa.arsel") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "arsel-push-sample-app"
include(":app")
