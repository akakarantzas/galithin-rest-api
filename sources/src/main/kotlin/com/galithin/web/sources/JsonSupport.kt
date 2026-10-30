package com.galithin.web.sources

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.databind.module.SimpleModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal val objectMapper: ObjectMapper =
    jacksonObjectMapper()
        .registerKotlinModule()
        .registerKotlinxJsonElementSupport()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

/** Bridges the kotlinx JSON values used by domain models into Jackson HTTP/database/catalog boundaries. */
fun ObjectMapper.registerKotlinxJsonElementSupport(): ObjectMapper =
    registerModule(
        SimpleModule("kotlinx-json-element")
            .addSerializer(JsonElement::class.java, KotlinxJsonElementSerializer)
            .addDeserializer(JsonElement::class.java, KotlinxJsonElementDeserializer),
    )

private object KotlinxJsonElementSerializer : JsonSerializer<JsonElement>() {
    override fun serialize(value: JsonElement, generator: JsonGenerator, serializers: SerializerProvider) {
        generator.writeJsonElement(value)
    }

    private fun JsonGenerator.writeJsonElement(value: JsonElement) {
        when (value) {
            JsonNull -> writeNull()
            is JsonObject -> {
                writeStartObject()
                value.forEach { (key, child) ->
                    writeFieldName(key)
                    writeJsonElement(child)
                }
                writeEndObject()
            }
            is JsonArray -> {
                writeStartArray()
                value.forEach { child -> writeJsonElement(child) }
                writeEndArray()
            }
            is JsonPrimitive -> when {
                value.isString -> writeString(value.content)
                value.booleanOrNull != null -> writeBoolean(value.booleanOrNull!!)
                value.longOrNull != null -> writeNumber(value.longOrNull!!)
                value.doubleOrNull != null -> writeNumber(value.doubleOrNull!!)
                else -> writeNumber(value.content)
            }
        }
    }
}

private object KotlinxJsonElementDeserializer : JsonDeserializer<JsonElement>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): JsonElement =
        Json.parseToJsonElement(parser.codec.readTree<com.fasterxml.jackson.databind.JsonNode>(parser).toString())
}
