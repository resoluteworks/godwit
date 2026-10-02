// works.resolute:godwit-core. Runtime dependencies: the MongoDB Kotlin sync driver (api: its types are in godwit's
// public API) and slf4j-api. Nothing else.
dependencies {
    api("org.mongodb:mongodb-driver-kotlin-sync:5.7.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
}
