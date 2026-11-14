# Android 端依赖下载

仓库里不提交二进制依赖，构建前先执行（目录：`android/libs/`）：

```bash
cd android/libs

# WebRTC (io.github.webrtc-sdk:android 144.7559.15)
curl -L -o webrtc.aar https://repo1.maven.org/maven2/io/github/webrtc-sdk/android/144.7559.15/android-144.7559.15.aar
unzip -o webrtc.aar -d webrtc_ext

# WebSocket 客户端
curl -L -o okhttp-3.14.9.jar https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/3.14.9/okhttp-3.14.9.jar
curl -L -o okio-1.17.2.jar  https://repo1.maven.org/maven2/com/squareup/okio/okio/1.17.2/okio-1.17.2.jar
```

然后回到 `android/` 执行构建：

```bash
./build.sh
# 产物：android/out/RingCall.apk（自动生成 ringcall.keystore 并签名）
```

## build.sh 依赖的环境

| 依赖 | 说明 |
|---|---|
| JDK 17 | `JAVA_HOME` 指向 JDK 17 |
| Android SDK | `ANDROID_HOME` 或脚本里的 `SDK` 变量，需含 `platforms/android-34`、`build-tools/34.0.0` |
| zip / keytool | 系统自带 |

脚本会依次跑 `aapt2 → javac → d8 → zipalign → apksigner`，不依赖 Gradle。
