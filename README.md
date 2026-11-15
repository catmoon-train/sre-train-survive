# Habi Train Survive

独立的 Fabric 模组基础项目，与 StarRailExpress 工作区使用相同的核心构建版本，不依赖其业务代码。

- Minecraft：1.21.1
- 映射：Mojang 官方映射（`loom.officialMojangMappings()`），不是 Yarn
- Fabric Loom：1.17.13
- Fabric Loader：0.19.3
- Fabric API：0.116.13+1.21.1
- Java：21
- Gradle：9.6.1（Wrapper 使用与现有工作区相同的腾讯云镜像）
- Mod ID：`habi_train_survive`
- Java 包名：`io.wifi.habitrainsurvive`

## 开发

使用 JDK 21，在 IntelliJ IDEA 中打开此目录并导入 Gradle 项目。

```bat
gradlew.bat build
gradlew.bat runClient
gradlew.bat runServer
gradlew.bat genSources
```

Git Bash/Linux 使用 `./gradlew` 替代 `gradlew.bat`。
首次启动服务端时需要自行阅读并同意 Minecraft EULA。

构建产物：`build/libs/habi-train-survive-1.0.0.jar`。
源码包：`build/libs/habi-train-survive-1.0.0-sources.jar`。

公共入口：`src/main/java/io/wifi/habitrainsurvive/HabiTrainSurvive.java`。
客户端入口：`src/main/java/io/wifi/habitrainsurvive/client/HabiTrainSurviveClient.java`。
Mixin 配置已预留，暂未包含注入。

版本与发布坐标在 `gradle.properties` 中配置。许可证暂为保留所有权利，发布前按实际授权修改 `fabric.mod.json`。
