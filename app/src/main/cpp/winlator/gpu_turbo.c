/*
 * Adreno "turbo" (java: com.winlator.cmod.core.GpuTurbo).
 *
 * adrenotools_set_turbo() opens /dev/kgsl-3d0 itself and issues
 * IOCTL_KGSL_SETPROPERTY(KGSL_PROP_PWRCTRL): turbo on switches the driver's power scaling off so the
 * GPU stays at its top clock, off gives the scaling back. The node is one the app may open (the
 * Vulkan driver uses it too), so no root is involved. The call returns nothing, so success cannot be
 * observed here; on a device without the node it is a silent no-op.
 *
 * The property lives in the kernel, not in the fd, so it outlives this process: the Java side clears
 * it whenever the game is left.
 */
#include <jni.h>

#include "../adrenotools/include/adrenotools/driver.h"

JNIEXPORT void JNICALL
Java_com_winlator_cmod_core_GpuTurbo_nativeSetTurbo(JNIEnv *env, jclass clazz, jboolean on)
{
    adrenotools_set_turbo(on == JNI_TRUE);
}
