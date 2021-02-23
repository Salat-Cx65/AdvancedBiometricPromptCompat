package me.weishu.reflection

import android.os.Build
import android.os.Build.VERSION
import android.util.Log
import java.lang.reflect.Method

/**
 * @author weishu
 * @date 2020/7/13.
 */
object BootstrapClass {
    private const val TAG = "BootstrapClass"
    private var sVmRuntime: Any? = null
    private var setHiddenApiExemptions: Method? = null

    init {
        if (VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val params = arrayOf<Class<*>>()
                val forName =
                    Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod",
                    String::class.java,
                    params::class.java
                )
                val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
                val getRuntime =
                    getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
                setHiddenApiExemptions = getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(
                        Array<String>::class.java
                    )
                ) as Method
                sVmRuntime = getRuntime.invoke(null)
            } catch (e: Throwable) {
                Log.w(TAG, "reflect bootstrap failed:", e)
            }
        }
    }

    /**
     * make the method exempted from hidden API check.
     *
     * @param method the method signature prefix.
     * @return true if success.
     */
    fun exempt(method: String): Boolean {
        return exempt(*arrayOf(method))
    }

    /**
     * make specific methods exempted from hidden API check.
     *
     * @param methods the method signature prefix, such as "Ldalvik/system", "Landroid" or even "L"
     * @return true if success
     */
    fun exempt(vararg methods: String?): Boolean {
        return if (sVmRuntime == null || setHiddenApiExemptions == null) {
            false
        } else try {
            setHiddenApiExemptions?.invoke(sVmRuntime, *arrayOf<Any>(methods))
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Make all hidden API exempted.
     *
     * @return true if success.
     */
    fun exemptAll(): Boolean {
        return exempt(*arrayOf("L"))
    }
}