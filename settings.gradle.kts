pluginManagement {
    repositories {
        val mirror = providers.gradleProperty("mementoMirror").orNull
        if (mirror != null) maven { url = uri(mirror); isAllowInsecureProtocol = true }
        google(); mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val mirror = providers.gradleProperty("mementoMirror").orNull
        if (mirror != null) maven { url = uri(mirror); isAllowInsecureProtocol = true }
        google(); mavenCentral()
    }
}
rootProject.name = "Memento"
include(":app")
