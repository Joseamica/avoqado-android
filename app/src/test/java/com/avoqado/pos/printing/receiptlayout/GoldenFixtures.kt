package com.avoqado.pos.printing.receiptlayout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class GoldenCase(
    val name: String,
    val width: Int,
    val blocksJson: JsonElement?,
    val input: ReceiptInput,
    val lines: List<LogicalLine>,
)

/** Lee los casos dorados que generó el servidor y repartió `scripts/sync-receipt-golden.sh`. */
object GoldenFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun files(): List<File> {
        val dir = File(GoldenFixtures::class.java.classLoader!!.getResource("receipt-layout/golden")!!.toURI())
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    }

    fun read(file: File): GoldenCase {
        val root = json.parseToJsonElement(file.readText()).jsonObject
        return GoldenCase(
            name = file.name.removeSuffix(".json"),
            width = root["width"]!!.jsonPrimitive.int,
            blocksJson = root["blocks"],
            input = json.decodeFromJsonElement(ReceiptInput.serializer(), root["input"]!!),
            lines = root["lines"]!!.jsonArray.map(::line),
        )
    }

    private fun line(element: JsonElement): LogicalLine {
        val o = element.jsonObject
        fun s(key: String) = o[key]!!.jsonPrimitive.content
        return when (val kind = s("kind")) {
            "text" -> LogicalLine.Text(s("text"), Align.of(s("align"))!!, o["bold"]!!.jsonPrimitive.boolean, o["double"]!!.jsonPrimitive.boolean)
            "image" -> LogicalLine.Image(ImageRef.of(s("ref"))!!, o["widthPct"]!!.jsonPrimitive.int, Align.of(s("align"))!!)
            "qr" -> LogicalLine.Qr(s("data"))
            "barcode" -> LogicalLine.Barcode(s("data"))
            "feed" -> LogicalLine.Feed(o["lines"]!!.jsonPrimitive.int)
            "cut" -> LogicalLine.Cut
            else -> error("kind desconocido en el golden: $kind")
        }
    }
}
