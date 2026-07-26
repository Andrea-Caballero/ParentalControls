package com.tudominio.parentalcontrol.data.db

import androidx.room.TypeConverter
import com.tudominio.parentalcontrol.data.model.WindowEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object Converters {

    @TypeConverter
    fun fromMap(value: Map<String, String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toMap(value: String): Map<String, String> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromWindowList(value: List<WindowEntity>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toWindowList(value: String): List<WindowEntity> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return Json.decodeFromString(value)
    }

    @TypeConverter
    fun fromUUID(value: UUID?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun toUUID(value: String?): UUID? {
        return value?.let { UUID.fromString(it) }
    }
}
