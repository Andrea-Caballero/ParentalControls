package com.tudominio.parentalcontrol.data.db

import androidx.room.TypeConverter
import com.tudominio.parentalcontrol.data.model.CategoryLimitEntity
import com.tudominio.parentalcontrol.data.model.ScheduleEntity
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
    fun fromScheduleList(value: List<ScheduleEntity>): String = Json.encodeToString(value)

    @TypeConverter
    fun toScheduleList(value: String): List<ScheduleEntity> = Json.decodeFromString(value)

    @TypeConverter
    fun fromCategoryLimitList(value: List<CategoryLimitEntity>): String = Json.encodeToString(value)

    @TypeConverter
    fun toCategoryLimitList(value: String): List<CategoryLimitEntity> = Json.decodeFromString(value)

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
