package com.tumblecam.app

import java.io.InputStream

class CubeLut(
    val title: String,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: FloatArray
)

object CubeLutParser {

    private val whitespace = Regex("\\s+")

    fun parse(input: InputStream): CubeLut {
        var title = "Custom LUT"
        var size = 0
        var dMin = floatArrayOf(0f, 0f, 0f)
        var dMax = floatArrayOf(1f, 1f, 1f)
        var data = FloatArray(0)
        var count = 0

        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(whitespace)
                if (line[0].isLetter()) {
                    when (parts[0].uppercase()) {
                        "TITLE" -> title = line.substringAfter(' ').trim().trim('"')
                        "LUT_3D_SIZE" -> {
                            size = parts[1].toInt()
                            require(size in 2..129) { "Unsupported LUT size $size" }
                            data = FloatArray(size * size * size * 3)
                        }
                        "LUT_1D_SIZE" -> throw IllegalArgumentException("1D .cube LUTs are not supported")
                        "DOMAIN_MIN" -> dMin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        "DOMAIN_MAX" -> dMax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    }
                } else {
                    require(size > 0) { "LUT_3D_SIZE must appear before data" }
                    require(parts.size >= 3 && count + 3 <= data.size) { "Malformed or excess LUT data" }
                    data[count++] = parts[0].toFloat()
                    data[count++] = parts[1].toFloat()
                    data[count++] = parts[2].toFloat()
                }
            }
        }
        require(size > 0 && count == data.size) { "Incomplete .cube file (expected ${data.size / 3} entries)" }
        return CubeLut(title, size, dMin, dMax, data)
    }
}
