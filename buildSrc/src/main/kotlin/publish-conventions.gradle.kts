plugins {
    id("signing")
    `maven-publish`
    id("com.gradleup.nmcp")
}

publishing {
    val publishGit = "resoluteworks/godwit"

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
// publication is signed, including the one published to Maven Local; without one, publishing to Maven Local skips
// the signing tasks.
signing {
    setRequired(provider { gradle.taskGraph.allTasks.any { it.name == "publishAggregationToCentralPortal" } })
    sign(publishing.publications["mavenJava"])
}
