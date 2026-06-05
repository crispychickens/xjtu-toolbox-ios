package com.xjtu.toolbox.shared.parsing

internal sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal fun JsonValue.asObjectOrNull(): Map<String, JsonValue>? =
    (this as? JsonValue.Object)?.values

internal fun JsonValue.asArrayOrNull(): List<JsonValue>? =
    (this as? JsonValue.Array)?.values

internal fun JsonValue.asStringOrNull(): String? =
    when (this) {
        is JsonValue.StringValue -> value
        is JsonValue.NumberValue -> raw
        is JsonValue.BooleanValue -> value.toString()
        else -> null
    }

internal fun JsonValue.asIntOrNull(): Int? =
    when (this) {
        is JsonValue.NumberValue -> raw.toIntOrNull()
        is JsonValue.StringValue -> value.toIntOrNull()
        else -> null
    }

internal fun JsonValue.asLongOrNull(): Long? =
    when (this) {
        is JsonValue.NumberValue -> raw.toLongOrNull()
        is JsonValue.StringValue -> value.toLongOrNull()
        else -> null
    }

internal fun JsonValue.asDoubleOrNull(): Double? =
    when (this) {
        is JsonValue.NumberValue -> raw.toDoubleOrNull()
        is JsonValue.StringValue -> value.toDoubleOrNull()
        else -> null
    }

internal fun JsonValue.asBooleanOrNull(): Boolean? =
    when (this) {
        is JsonValue.BooleanValue -> value
        is JsonValue.StringValue -> value.toBooleanStrictOrNull()
        else -> null
    }

internal class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        require(index == input.length) { "unexpected trailing JSON at $index" }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.StringValue(parseString())
            't' -> {
                expectLiteral("true")
                JsonValue.BooleanValue(true)
            }
            'f' -> {
                expectLiteral("false")
                JsonValue.BooleanValue(false)
            }
            'n' -> {
                expectLiteral("null")
                JsonValue.Null
            }
            '-', in '0'..'9' -> parseNumber()
            else -> error("unexpected JSON token at $index")
        }
    }

    private fun parseObject(): JsonValue.Object {
        expect('{')
        skipWhitespace()
        val values = linkedMapOf<String, JsonValue>()
        if (consumeIf('}')) return JsonValue.Object(values)
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            if (consumeIf('}')) break
            expect(',')
        }
        return JsonValue.Object(values)
    }

    private fun parseArray(): JsonValue.Array {
        expect('[')
        skipWhitespace()
        val values = mutableListOf<JsonValue>()
        if (consumeIf(']')) return JsonValue.Array(values)
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consumeIf(']')) break
            expect(',')
        }
        return JsonValue.Array(values)
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < input.length) {
            when (val char = input[index++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(char)
            }
        }
        error("unterminated JSON string")
    }

    private fun parseEscape(): Char {
        require(index < input.length) { "unterminated JSON escape" }
        return when (val escaped = input[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> error("unsupported JSON escape: $escaped")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(index + 4 <= input.length) { "unterminated unicode escape" }
        val hex = input.substring(index, index + 4)
        index += 4
        return hex.toInt(16).toChar()
    }

    private fun parseNumber(): JsonValue.NumberValue {
        val start = index
        consumeIf('-')
        consumeDigits()
        if (consumeIf('.')) consumeDigits()
        if (peekOrNull() == 'e' || peekOrNull() == 'E') {
            index++
            if (peekOrNull() == '+' || peekOrNull() == '-') index++
            consumeDigits()
        }
        return JsonValue.NumberValue(input.substring(start, index))
    }

    private fun consumeDigits() {
        val start = index
        while (peekOrNull() in '0'..'9') index++
        require(index > start) { "expected JSON digit at $index" }
    }

    private fun expectLiteral(literal: String) {
        require(input.startsWith(literal, index)) { "expected $literal at $index" }
        index += literal.length
    }

    private fun expect(char: Char) {
        skipWhitespace()
        require(peek() == char) { "expected '$char' at $index" }
        index++
    }

    private fun consumeIf(char: Char): Boolean {
        skipWhitespace()
        if (peekOrNull() != char) return false
        index++
        return true
    }

    private fun skipWhitespace() {
        while (peekOrNull()?.isWhitespace() == true) index++
    }

    private fun peek(): Char =
        peekOrNull() ?: error("unexpected end of JSON")

    private fun peekOrNull(): Char? =
        input.getOrNull(index)
}
