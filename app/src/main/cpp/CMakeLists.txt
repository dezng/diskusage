cmake_minimum_required(VERSION 3.4.1)

if (ANDROID)

    add_definitions(-DANDROID_JNI_BUILD)



#    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Oz -flto -fvisibility=hidden")
#

#    set(CMAKE_C_VISIBILITY_PRESET hidden)
#    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
endif()


add_library(
        scan
        SHARED
        scan.c
        scan_jni.c
)


find_library(
        log-lib
        log
)


target_link_libraries(
        scan
        ${log-lib}
)


#if(ANDROID)
#    add_custom_command(TARGET scan POST_BUILD
#            COMMAND ${ANDROID_NDK}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip --strip-unneeded $<TARGET_FILE:scan>
#            COMMENT "Stripping debug symbols")
#endif()
