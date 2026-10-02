// Compiles exactly one directory, given by -PnegSnippet, against the public API of godwit-core and godwit-test.
dependencies {
    implementation("works.resolute:godwit-test")
    implementation("org.mongodb:mongodb-driver-kotlin-sync:5.7.0")
}

kotlin {
    sourceSets.named("main") {
        kotlin.setSrcDirs(listOf(providers.gradleProperty("negSnippet").get()))
    }
}
