// works.resolute:godwit-test. Test-only kit: the shared MongoDB container, runner-path helpers and
// SessionEscapeDetector. No test framework dependency: helpers fail with AssertionError.
// The implementation adds org.testcontainers:testcontainers-mongodb (implementation scope, not part of the API);
// these stubs compile without it, and its full dependency graph is not in the offline Gradle cache.
dependencies {
    api(project(":godwit-core"))
}
