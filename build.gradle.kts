plugins {
    id("java")
    id("application")
}

// `./gradlew installDist` puts a launcher at build/install/cshell/bin/cshell;
// run that on a terminal for line editing, history and completion. The
// `cshell` task below works for pipes, but Gradle gives it no terminal.
application {
    mainClass.set("org.jbm.repl.CShell")
    applicationName = "cshell"
}

group = "org.jbm"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains:annotations:26.0.2")
    implementation("org.jline:jline:3.26.3")
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")
    testCompileOnly("org.projectlombok:lombok:1.18.36")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // -Dtyped.update=true / -Dtac.update=true regenerate the corpus goldens.
    for (flag in listOf("typed.update", "tac.update")) {
        System.getProperty(flag)?.let { systemProperty(flag, it) }
    }
}
// The interactive shell: ./gradlew -q cshell --console=plain
tasks.register<JavaExec>("cshell") {
    group = "application"
    description = "Runs the C shell on the terminal"
    mainClass.set("org.jbm.repl.CShell")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
