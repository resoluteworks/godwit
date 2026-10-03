plugins {
    id("signing")
    `maven-publish`
    id("com.gradleup.nmcp")
}

publishing {
    val publishGit = "resoluteworks/godwit"

    repositories {
        // Every version on main goes to this repository's GitHub Packages registry; Maven Central carries only the
        // releases cut with `make release`. The publish-github-packages workflow is the only writer: it runs with the
        // GITHUB_TOKEN of a GitHub Actions run, which is where both variables come from.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$publishGit")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = project.name
                description = provider {
                    project.description ?: error("${project.path} must set description: it becomes the POM description")
                }
                url = "https://github.com/$publishGit"
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://github.com/$publishGit/blob/main/LICENSE"
                        distribution = "repo"
                    }
                }
                scm {
                    url = "https://github.com/$publishGit"
                    connection = "scm:git:git://github.com/$publishGit.git"
                    developerConnection = "scm:git:ssh://git@github.com:$publishGit.git"
                }
                developers {
                    developer {
                        name = "Cosmin Marginean"
                    }
                }
            }
        }
    }
}

// Signing is required only for the Central Portal upload. With a key configured (the maintainer's machine) every
// publication is signed, including the one published to Maven Local; without one (the publish-github-packages
// workflow), publishing to Maven Local or GitHub Packages skips the signing tasks.
signing {
    setRequired(provider { gradle.taskGraph.allTasks.any { it.name == "publishAggregationToCentralPortal" } })
    sign(publishing.publications["mavenJava"])
}
