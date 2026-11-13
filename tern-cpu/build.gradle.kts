dependencies {
    api(project(":tern-core"))
    api(project(":tern-isa"))
    implementation(project(":tern-stdlib"))
    testImplementation(project(":tern-sim"))
    testImplementation(project(":tern-encode"))
    testImplementation(project(":tern-fpga"))
    testImplementation(project(":tern-formal"))
}
tasks.test { maxHeapSize = "8g" }
