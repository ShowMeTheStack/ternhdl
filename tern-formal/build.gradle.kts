dependencies {
    api(project(":tern-encode"))
    testImplementation(testFixtures(project(":tern-encode")))
    testImplementation(testFixtures(project(":tern-sim")))
}
tasks.test { useJUnitPlatform { excludeTags("slow") } }
tasks.register<Test>("slowTest") {
    useJUnitPlatform { includeTags("slow") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "8g"
    testLogging { showStandardStreams = true }
    for (k in listOf("tern.ffuzz.solver", "tern.ffuzz.designs", "tern.ffuzz.parallel")) System.getProperty(k)?.let { systemProperty(k, it) }
}
