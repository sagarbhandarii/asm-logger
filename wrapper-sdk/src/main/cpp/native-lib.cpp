#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_wrapper_sdk_Watchdog_nativeHeartbeat(JNIEnv* env, jobject /* thiz */) {
    std::string heartbeat = "native-watchdog-ok";
    return env->NewStringUTF(heartbeat.c_str());
}
