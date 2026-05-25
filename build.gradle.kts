import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import org.gradle.kotlin.dsl.configure

plugins {
    id("java")
    alias(libs.plugins.shadow)
    alias(libs.plugins.bukkitPluginYml)
}

group = "io.shantek"
version = "2.0.0"

val identifier = "DurabilityAlertReloaded"
val location = "io.shantek"

dependencies {
    compileOnly(libs.paper)
}

tasks {
    jar {
        enabled = false
    }

    build {
        dependsOn(shadowJar)
    }
}

val targetJavaVersion = 21
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
}

tasks.shadowJar {
    archiveBaseName.set(identifier)
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

configure<BukkitPluginDescription> {
    name = identifier
    apiVersion = "1.13"
    version = project.version.toString()
    main = "$location.DurabilityAlertContinued"

    commands.register("durabilityalert") {
        usage = "/<command>"
        aliases = listOf("da")
        description = "the base durabilityalert command"
        permission = "shantek.durabilityalert.use"
        permissionMessage = "You do not have permission!"
    }

    permissions.register("shantek.durabilityalert.use") {
        description = "allows the user to get durability alerts"
        default = BukkitPluginDescription.Permission.Default.OP
    }
    permissions.register("shantek.durabilityalert.reload") {
        description = "ability to reload the plugin"
        default = BukkitPluginDescription.Permission.Default.OP
    }
}
