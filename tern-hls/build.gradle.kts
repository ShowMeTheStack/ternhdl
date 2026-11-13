dependencies {
    api(project(":tern-core"))
    testImplementation(project(":tern-sim"))
    testImplementation(project(":tern-encode"))
    testImplementation(testFixtures(project(":tern-encode")))
    testImplementation(project(":tern-fpga"))
    testImplementation(project(":tern-formal"))
    testImplementation(project(":tern-stdlib"))
}
tasks.test { useJUnitPlatform { excludeTags("slow") }; maxHeapSize = "8g" }
tasks.register<Test>("slowTest") {
    useJUnitPlatform { includeTags("slow") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "8g"
}
