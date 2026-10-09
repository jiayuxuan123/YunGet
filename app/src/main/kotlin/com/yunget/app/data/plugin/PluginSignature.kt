/*
 * YunGet - 网盘分享链接解析与高速下载的 Android 应用
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunget.app.data.plugin

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 插件脚本的 Ed25519 验签。
 *
 * ## 为什么不用平台自带的
 *
 * Android 从 **API 33** 才在 `java.security` 里提供 Ed25519（`KeyFactory.getInstance("Ed25519")`），
 * 而本应用的 `minSdk` 是 23。所以这里按平台能力分两条路：
 *
 *  - **API 33+**：直接用平台的 `Signature("Ed25519")`（走 Conscrypt，最快也最省心）；
 *  - **API < 33**：用本文件里的纯 Kotlin 实现（RFC 8032 的验证部分）。
 *
 * 两条路都只做**验证**，不做签名 —— 私钥只存在于发布者的签名工具链里
 * （`YunGet-Plugins/tools/sign_plugin.py`），应用端永远不需要私钥，也就没有"应用里藏着密钥"
 * 这种更糟的设计。
 *
 * ## 载荷
 *
 * 签名覆盖的是**脚本文件的原始字节**（与源仓库 `tools/sign_plugin.py` 的约定一致）：
 * 不剥 BOM、不做行尾归一化、不重新编码。所以下载到的字节要**原样**送进来，
 * 中间任何一次"顺手转成字符串再转回字节"都可能改变内容而让验签失败（或更糟：验过一份、
 * 存下另一份）。[MarketClient] 因此是把同一个 `ByteArray` 验完再转文本落盘的。
 *
 * ## 纯 Kotlin 实现的取舍
 *
 * RFC 8032 的验签只需要**公钥解码 + 点解压 + 一个标量乘 + 两次 SHA-512**，不需要大数模逆、
 * 不需要私钥标量运算。它比完整实现（签名 + 密钥生成）短得多，也把"实现错了会怎样"的
 * 面积压到最小：唯一的风险是"该拒的没拒"或"该过的没过"，两者都会在测试里暴露
 * （见 `PluginSignatureTest` 的 RFC 8032 官方向量）。
 */
object PluginSignature {

    /** 平台是否自带 Ed25519（API 33+）。 */
    private val platformSupportsEd25519: Boolean by lazy {
        runCatching { KeyFactory.getInstance("Ed25519") }.isSuccess
    }

    /**
     * 验证 [signatureBase64] 是否是 [payload] 在 [publicKeyPem] 下的有效签名。
     *
     * 任何一步出错（PEM 坏、base64 坏、签名长度不对）都返回 **false** 而不是抛异常 ——
     * 调用方要的是"能不能装"，不是"哪里坏了"；具体原因由调用方自己组装文案。
     */
    fun verify(payload: ByteArray, signatureBase64: String, publicKeyPem: String): Boolean {
        val keyBytes = decodePublicKey(publicKeyPem) ?: return false
        val sig = runCatching { Base64.getDecoder().decode(signatureBase64.trim()) }.getOrNull() ?: return false
        if (sig.size != Ed25519.SIGNATURE_BYTES) return false

        // 公钥必须是 32 字节的原始点（SPKI 的最后 32 字节）
        if (keyBytes.size < Ed25519.PUBLIC_KEY_BYTES) return false
        val rawKey = keyBytes.copyOfRange(keyBytes.size - Ed25519.PUBLIC_KEY_BYTES, keyBytes.size)

        return if (platformSupportsEd25519) {
            verifyWithPlatform(payload, sig, keyBytes)
        } else {
            Ed25519.verify(payload, sig, rawKey)
        }
    }

    /**
     * 平台路径：用 SPKI 的 DER 直接构造公钥。
     *
     * 失败**回退**到纯 Kotlin 实现，而不是直接返回 false —— 某些 ROM 的 `KeyFactory`
     * 声称支持却在特定 DER 上抛异常，那时宁可走自实现也不要让用户无法安装插件。
     */
    private fun verifyWithPlatform(payload: ByteArray, sig: ByteArray, spkiDer: ByteArray): Boolean {
        val ok = runCatching {
            val key: PublicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiDer))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(payload)
                verify(sig)
            }
        }.getOrNull()
        if (ok == true) return true
        val rawKey = spkiDer.copyOfRange(spkiDer.size - Ed25519.PUBLIC_KEY_BYTES, spkiDer.size)
        return Ed25519.verify(payload, sig, rawKey)
    }

    /**
     * 解出 PEM 里的 DER 字节。
     *
     * 只认标准 `-----BEGIN PUBLIC KEY-----`（SPKI）；不做"猜格式"，
     * 因为公钥是从仓库里读来的固定文件，格式由我们自己定。
     */
    internal fun decodePublicKey(pem: String): ByteArray? {
        val body = pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        if (body.isEmpty()) return null
        return runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull()
    }

    /**
     * RFC 8032 的 Ed25519 验证（纯 Kotlin，无依赖）。
     *
     * 实现范围刻意收窄到验证所需：
     *  - 曲线 p = 2^255 - 19，群阶 l；
     *  - 点用扩展坐标（X, Y, Z, T）做加法，最后一次统一转仿射；
     *  - 标量乘用简单的 double-and-add（验证只用公钥点，性能足够：一次验证约几毫秒）。
     */
    internal object Ed25519 {

        const val PUBLIC_KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64

        /** p = 2^255 - 19 */
        private val P = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)

        /** 群阶 l = 2^252 + 27742317777372353535851937790883648493 */
        private val L = BigInteger.TWO.pow(252) +
            BigInteger("27742317777372353535851937790883648493")

        /** 曲线常数 d = -121665/121666 mod p */
        private val D = BigInteger.valueOf(-121665).mod(P) *
            BigInteger.valueOf(121666).modInverse(P) % P

        /** sqrt(-1) mod p，用于解压点 */
        private val SQRT_M1 = BigInteger.TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

        /**
         * 验证 [signature] 是 [message] 在 [publicKey]（32 字节原始公钥）下的签名。
         *
         * 步骤（RFC 8032 §5.1.7）：
         *  1. 解压 A = 公钥点，S = 签名后 32 字节（小端整数），必须 S < l；
         *  2. h = SHA-512(R || A || M) mod l；
         *  3. 检查 [S]B = R + [h]A，等价地 [S]B - [h]A - R = 单位元。
         *
         * 第 3 步用的是**两边同乘**的等价形式（这里按教科书做法算 [S]B 与 R + [h]A 再比点），
         * 好处是失败时能分清"签名不对"与"公钥不在曲线上"，调试与测试都更好定位。
         */
        fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
            if (signature.size != SIGNATURE_BYTES || publicKey.size != PUBLIC_KEY_BYTES) return false

            val rBytes = signature.copyOfRange(0, 32)
            val sBytes = signature.copyOfRange(32, 64)

            val a = decompress(publicKey) ?: return false
            val r = decompress(rBytes) ?: return false

            // S 必须 < l，否则是"可延展"的签名编码，按规范拒绝
            val s = leToBig(sBytes)
            if (s >= L) return false

            // h = SHA-512(R || A || M) mod l
            val h = sha512(rBytes + publicKey + message)
            val hMod = leToBig(h) % L

            // [S]B 与 R + [h]A
            val sB = scalarMul(s, BASE_POINT)
            val hA = scalarMul(hMod, a)
            val rhs = pointAdd(r, hA)

            return pointEquals(sB, rhs)
        }

        // ------------------------------------------------------------ 曲线运算

        /** 扩展坐标点 (X, Y, Z, T)，满足 T = XY/Z。 */
        internal data class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

        private val ZERO = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

        /** 基点的 y 坐标：4/5 mod p；x 由解压公式得到 */
        private val BASE_POINT: Point by lazy {
            val y = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
            val x = recoverX(y, xIsOdd = false) ?: error("基点解压失败")
            Point(x, y, BigInteger.ONE, x * y % P)
        }

        private fun pointAdd(p: Point, q: Point): Point {
            val a = (p.y - p.x) * (q.y - q.x) % P
            val b = (p.y + p.x) * (q.y + q.x) % P
            val c = BigInteger.TWO * p.t * q.t * D % P
            val d = BigInteger.TWO * p.z * q.z % P
            val e = b - a
            val f = d - c
            val g = d + c
            val h = b + a
            return Point(e * f % P, g * h % P, f * g % P, e * h % P)
        }

        /** double-and-add 标量乘。验证路径上不追求常数时间（攻击者控制不了公钥之外的输入）。 */
        private fun scalarMul(scalar: BigInteger, point: Point): Point {
            var n = scalar
            var acc = ZERO
            var addend = point
            while (n > BigInteger.ZERO) {
                if (n.testBit(0)) acc = pointAdd(acc, addend)
                addend = pointAdd(addend, addend)
                n = n.shiftRight(1)
            }
            return acc
        }

        /** 转仿射并比较（比较前统一归一化，避免 Z 不同导致的假不等）。 */
        private fun pointEquals(p: Point, q: Point): Boolean {
            val zInvP = p.z.modInverse(P)
            val zInvQ = q.z.modInverse(P)
            val xP = p.x * zInvP % P
            val yP = p.y * zInvP % P
            val xQ = q.x * zInvQ % P
            val yQ = q.y * zInvQ % P
            return xP == xQ && yP == yQ
        }

        /**
         * 从 32 字节小端编码解出点（RFC 8032 §5.1.3）。
         *
         * 最高位是 x 的奇偶标志，其余 255 位是 y。x 由曲线方程解出：
         * x² = (y² - 1) / (d·y² + 1)。
         */
        private fun decompress(encoded: ByteArray): Point? {
            val y = leToBig(encoded.copyOf(32).also { it[31] = (it[31].toInt() and 0x7f).toByte() })
            if (y >= P) return null
            val xIsOdd = (encoded[31].toInt() and 0x80) != 0
            val x = recoverX(y, xIsOdd) ?: return null
            return Point(x, y, BigInteger.ONE, x * y % P)
        }

        /** 由 y 求 x，并按需要选奇偶分支；y 不在曲线上返回 null。 */
        private fun recoverX(y: BigInteger, xIsOdd: Boolean): BigInteger? {
            val y2 = y * y % P
            val num = (y2 - BigInteger.ONE).mod(P)
            val den = (D * y2 + BigInteger.ONE).mod(P)
            val denInv = runCatching { den.modInverse(P) }.getOrNull() ?: return null
            val x2 = num * denInv % P

            // x = x2^((p+3)/8)，若 x² ≠ x2 则乘 sqrt(-1)
            var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
            if (x * x % P != x2) x = x * SQRT_M1 % P
            if (x * x % P != x2) return null

            // 奇偶不符合就取反（-x mod p）
            if (x.testBit(0) != xIsOdd) x = P - x
            return x
        }

        // ------------------------------------------------------------ 小工具

        /** 小端字节 → 非负大整数（Ed25519 的字段与标量都按小端编码）。 */
        private fun leToBig(bytes: ByteArray): BigInteger {
            val be = ByteArray(bytes.size)
            for (i in bytes.indices) be[i] = bytes[bytes.size - 1 - i]
            return BigInteger(1, be)
        }

        /** SHA-512（平台自带，无需第三方库）。 */
        private fun sha512(data: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-512").digest(data)
    }
}
