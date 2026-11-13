dependencies {
    api(project(":tern-map"))
    implementation(project(":tern-stdlib"))
    testImplementation(project(":tern-sim"))
    testImplementation(project(":tern-stdlib"))
}
tasks.test { useJUnitPlatform { excludeTags("slow") }; maxHeapSize = "8g" }
