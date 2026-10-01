plugins {
    id("java-library")
}

// ---------------------------------------------------------------------------
// 可选的开发服务器支持：run-paper
//
// 该插件（3.1.0）要求 Gradle >= 9.7，而仓库自带的 wrapper 固定 9.4.0，且本机无法
// 下载新的 Gradle 发行包。因此默认不加载它，只保证构建（compile / jar / test）可用。
// 需要 ./gradlew runServer 时用：gradle -PwithRunPaper=true runServer
// ---------------------------------------------------------------------------
val withRunPaper = providers.gradleProperty("withRunPaper").getOrElse("false") == "true"
if (withRunPaper) {
    apply(plugin = "xyz.jpenilla.run-paper")
}

// ---------------------------------------------------------------------------
// 中国境内镜像支持
// 开关注所在 gradle.properties：useChinaMirrors=false 时完全回退到官方仓库。
// 镜像地址同样在 gradle.properties 中维护，这里只保留兜底默认值。
// ---------------------------------------------------------------------------
val useChinaMirrors = providers.gradleProperty("useChinaMirrors").getOrElse("true") != "false"

fun mirrorUrl(property: String, fallback: String): String =
    providers.gradleProperty(property).getOrElse(fallback)

repositories {
    if (useChinaMirrors) {
        // 阿里云公共仓库：代理 Maven Central，作为首选国内源
        maven(mirrorUrl("chinaMirror.public", "https://maven.aliyun.com/repository/public")) {
            name = "AliyunPublic"
        }
        // 华为云镜像：阿里云缺件时的备用国内源
        maven(mirrorUrl("chinaMirror.huawei", "https://repo.huaweicloud.com/repository/maven/")) {
            name = "HuaweiCloud"
        }
    }

    // 官方仓库兜底：国内镜像缺件时仍可正常解析
    mavenCentral()
    // PaperMC 官方仓库：paper-api 目前没有国内镜像，必须保留
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // ExtraShop 的 API 与插件本体分开提供：编译期依赖 API jar，运行期由已安装的
    // ExtraShop 插件提供实现。绝不 shade 进本插件，否则会出现类型不匹配。
    // 该 jar 只含 cn.mgtown.extrashop.api 包，纯 Java。
    compileOnly(files("libs/ExtraShopApi-1.0.1.jar"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    compileJava {
        options.encoding = "UTF-8"
    }
}

// 说明：run-paper 只在显式打开 -PwithRunPaper=true 时才被应用（它要求 Gradle >= 9.7，
// 而仓库 wrapper 固定 9.4.0）。它自己的 runServer 任务由插件自行注册，
// 这里刻意不做任何配置，避免默认构建因找不到 runServer 访问器而编译失败。
