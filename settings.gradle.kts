pluginManagement {
    repositories {
        // 阿里云镜像：国内可直连，优先使用，避免去连被墙的 Gradle Plugin Portal
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") } // 镜像 Gradle Plugin Portal（KSP 插件标记在这里）
        maven { url = uri("https://maven.aliyun.com/repository/google") }         // 镜像 Google Maven
        maven { url = uri("https://maven.aliyun.com/repository/public") }          // 镜像 Maven Central 等公共仓
        // 兜底（若上面镜像不可用，仍会回退到这里）
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // TurboDL SDK 以 dev.turbodl:*:0.1.0 发布在本地 Maven（~/.m2），优先解析
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "YunGet"

include(":app")

// 手机端的插件只有 JS（L1）一种形态，因此没有独立的插件打包模块 —— 原生（L2）插件的
// 打包模块 :l2-providers 与整套 dex 加载机制已随「原生插件在手机端暂时禁用」一并移除
// （后续在 Windows 端启用原生插件）。
