package com.inkwell.mode

import android.content.Context
import java.io.DataOutputStream

/**
 * App 端通过 root 写 SystemProperties。
 *
 * 为什么是 setprop 而不是 service call：
 *   service call 直接 transact 给 SurfaceFlinger，绕过 DisplayTransformManager 的
 *   SparseArray 缓存，之后任何系统色彩变化（切换护眼、改色彩模式）都会把它覆盖掉。
 *   写入 persist.inkwell.* 后由模块统一注入，才能长期稳定 + 开机自动生效。
 */
object PropWriter {

    fun apply(ctx: Context, p: EinkParams, enabled: Boolean): Result {
        val cmds = mutableListOf<String>()
        cmds += "setprop ${PropConfig.P_ENABLED} ${if (enabled) 1 else 0}"
        if (enabled) {
            cmds += "setprop ${PropConfig.P_SAT} %.4f".format(p.saturation)
            cmds += "setprop ${PropConfig.P_BLACK} %.4f".format(p.black)
            cmds += "setprop ${PropConfig.P_WHITE} %.4f".format(p.white)
        }
        return run(cmds)
    }

    /**
     * 切预设。
     *
     * ⚠️ 引号必须用单引号，不能用 \"\"：
     *   shell 里 \"\" 会变成字面的两个引号字符，导致属性值真的带上引号；
     *   清空也必须用 '' 而不是 \"\"，否则写入的是 "" 而非空串。
     * 预设名含中文和空格，更要靠单引号包住。
     */
    fun applyPreset(ctx: Context, name: String): Result {
        return run(
            listOf(
                "setprop ${PropConfig.P_PRESET} '$name'",
                "setprop ${PropConfig.P_ENABLED} 1",
                "setprop ${PropConfig.P_SAT} ''",
                "setprop ${PropConfig.P_BLACK} ''",
                "setprop ${PropConfig.P_WHITE} ''"
            )
        )
    }

    /** 直接下发矩阵给 SurfaceFlinger，用于验证 transaction code 是否有效 */
    fun testRawMatrix(): Result {
        val m = ColorMatrixBuilder.build(EinkParams.KALEIDO3)
        val sb = StringBuilder("service call SurfaceFlinger 1015 i32 1")
        for (v in m) sb.append(" f ").append("%.4f".format(v))
        return run(listOf(sb.toString()))
    }

    fun resetRawMatrix(): Result = run(listOf("service call SurfaceFlinger 1015 i32 0"))

    /** 测试 1022（setGlobalSaturation）是否可用 */
    fun testSaturation(): Result = run(listOf("service call SurfaceFlinger 1022 f 0.5"))

    fun writeBwApps(pkgs: Set<String>): Result {
        val body = pkgs.joinToString("\n")
        return run(
            listOf(
                "printf '%s\\n' ${pkgs.joinToString(" ")} > ${PropConfig.BW_APPS_FILE}",
                "chmod 644 ${PropConfig.BW_APPS_FILE}"
            )
        )
    }

    fun run(cmds: List<String>): Result {
        return try {
            val proc = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(proc.outputStream)
            for (c in cmds) {
                os.writeBytes(c + "\n"); os.flush()
            }
            os.writeBytes("exit\n"); os.flush()
            val code = proc.waitFor()
            Result(code == 0, cmds.joinToString("\n"))
        } catch (t: Throwable) {
            Result(false, t.message ?: "exception")
        }
    }

    fun hasRoot(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    data class Result(val ok: Boolean, val cmd: String)
}
