// ---------------------------------------------------------------------------
// Gradle 插件解析的中国境内镜像支持
// 开关与镜像地址来自 gradle.properties（与 build.gradle.kts 共用同一份配置）。
// pluginManagement 必须是 settings 文件里的第一个块，因此配置直接写在这里。
// 依赖仓库仍声明在 build.gradle.kts 中，两者互不影响。
// ---------------------------------------------------------------------------
pluginManagement {
    val useChinaMirrors = providers.gradleProperty("useChinaMirrors").getOrElse("true") != "false"

    repositories {
        if (useChinaMirrors) {
            // 阿里云 Gradle 插件镜像：代理 plugins.gradle.org
            maven(
                providers.gradleProperty("chinaMirror.gradlePlugin")
                    .getOrElse("https://maven.aliyun.com/repository/gradle-plugin")
            ) {
                name = "AliyunGradlePlugin"
            }
            // 阿里云公共仓库：代理 Maven Central
            maven(
                providers.gradleProperty("chinaMirror.public")
                    .getOrElse("https://maven.aliyun.com/repository/public")
            ) {
                name = "AliyunPublic"
            }
        }

        // 官方插件门户兜底：镜像缺件时仍可解析插件
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "fmwar"
