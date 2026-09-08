pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "LagFixer"

include("plugin")

include("nms:v1_21_R7")
include("nms:v26_1")

include("support:paper")
include("support:spigot")
include("support:common")