package com.inkwell.mode

/**
 * 生成注入 SurfaceFlinger 的 4x4 色彩矩阵。
 *
 * ⚠️ 关键：AOSP 的 DisplayTransformManager 使用「列主序（column-major）」存储，
 *   即 float[16] 中 [0..3] 是矩阵的第 0 列，而非第 0 行。
 *
 *   依据：AOSP ColorDisplayService 里的 MATRIX_GRAYSCALE 定义为
 *     { .2126 .2126 .2126 0,
 *       .7152 .7152 .7152 0,
 *       .0722 .0722 .0722 0,
 *       0     0     0     1 }
 *   而灰度的数学形式（行主序，out = M·in）应是
 *     { .2126 .7152 .0722 0,
 *       .2126 .7152 .0722 0,
 *       .2126 .7152 .0722 0,
 *       0     0     0     1 }
 *   两者互为转置 —— 证明存储为列主序。
 *   传错布局会导致颜色通道串扰（天空变紫、肤色发绿）。
 */
object ColorMatrixBuilder {

    /** Rec.709 亮度权重，保证降饱和时中性灰不变 */
    private val W = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    /**
     * 构建列主序矩阵。
     *
     * 数学形式（行主序，out = M·in）：
     *   降饱和：M[i][j] = s·δ(i,j) + (1−s)·w_j
     *   白黑位：out = in × (W − K) + K
     *   合并：  M = M_desat × (W − K)，第 4 列为 K
     */
    fun build(p: EinkParams, gamma: Float = 1.0f): FloatArray {
        val scale = p.white - p.black
        val m = Array(4) { FloatArray(4) }   // m[row][col]

        for (i in 0..2) {
            for (j in 0..2) {
                m[i][j] = ((if (i == j) p.saturation else 0f) + (1f - p.saturation) * W[j]) * scale
            }
            m[i][3] = p.black * gamma
        }
        m[3][0] = 0f; m[3][1] = 0f; m[3][2] = 0f; m[3][3] = 1f

        // 转置为列主序：out[col * 4 + row] = m[row][col]
        val out = FloatArray(16)
        for (c in 0..3) {
            for (r in 0..3) {
                out[c * 4 + r] = m[r][c]
            }
        }
        return out
    }

    /** 单位矩阵（关闭效果），同样是列主序 */
    fun identity(): FloatArray {
        val out = FloatArray(16)
        for (i in 0..3) out[i * 4 + i] = 1f
        return out
    }

    /** 调试用：打印成 4x4（按数学行主序显示，便于人眼检查） */
    fun dumpForDebug(p: EinkParams): String {
        val cm = build(p)
        val sb = StringBuilder("存储序列(列主序):\n")
        for (r in 0..3) {
            sb.append("  ").append(
                (0..3).joinToString("  ") { c -> "%7.4f".format(cm[c * 4 + r]) }
            ).append('\n')
        }
        sb.append("（以上已按数学行主序展示）")
        return sb.toString()
    }
}
