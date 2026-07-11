# Drop your RLottie aar here

Put the aar in this folder, named exactly:

    rlottie.aar

`core/build.gradle` wires it up as `api` (not `implementation`), so it's
visible transitively to every module that depends on :core — both :app
(calls `AXrLottie.init()` once at startup) and :feature-chat (renders the
animation) need it:

    api files('libs/rlottie.aar')

If your aar's package/class names differ from `com.aghajari.rlottie.*`
(AXrLottieImageView / AXrLottieDrawable), you only need to edit ONE file:

    feature-chat/src/main/java/com/callx/app/conversation/emptystate/RLottieViewWrapper.java

Everything else in the empty-chat feature (EmptyChatLottieController, the
WorkManager download worker, the disk cache) talks to that wrapper class
only — never to the raw library — so the rest of the codebase never needs
to change no matter which RLottie binding you end up using.

## About the cpp/ source folder you sent

Your `AXrLottie-release.aar` already ships **prebuilt** `.so` files for all
4 ABIs (armeabi-v7a, arm64-v8a, x86, x86_64) — `librlottie.so`,
`libjlottie.so`, `libjlz4.so`, `librlottie2gif.so`,
`librlottie-image-loader.so`, all inside `jni/<abi>/` in the aar itself.
**No NDK/CMake build is needed to use it as-is.**

The `cpp/` source is only needed if you want to *modify* the native rlottie
code and rebuild the aar yourself. See `rlottie-native-src/README.md` at
the project root for that path — it has to run on your machine (Android
Studio with NDK + CMake installed via SDK Manager), it can't be built here.
