plugins { `java-test-fixtures` }
dependencies {
    api(project(":tern-core"))
    testImplementation(project(":tern-sim"))
    testImplementation(testFixtures(project(":tern-sim")))
    testFixturesApi(project(":tern-core"))
    testFixturesImplementation(project(":tern-sim"))
    testFixturesImplementation(testFixtures(project(":tern-sim")))
}
tasks.test { useJUnitPlatform { excludeTags("slow") } }
tasks.register<Test>("slowTest") {
    useJUnitPlatform { includeTags("slow") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "8g"
    testLogging { showStandardStreams = true }
}
