package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.EmptyRoom

object EmptyRoomCdnParser {
    fun parse(json: String, campus: String, requestedSections: IntRange): List<EmptyRoom> {
        if (WireGuards.isAuthHtml(json)) {
            error("empty-room CDN JSON expected, got CAS/Safety Verify HTML")
        }
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("empty-room CDN payload must be a JSON object")
        val campusObject = root[campus]?.asObjectOrNull()
            ?: error("暂无 $campus 的空闲教室数据")

        return campusObject.flatMap { (buildingName, buildingJson) ->
            val building = buildingJson.asObjectOrNull() ?: return@flatMap emptyList()
            building.mapNotNull { (roomName, roomJson) ->
                if (roomName.isBlank() || roomName == "null" || roomJson is JsonValue.Null) return@mapNotNull null
                val room = roomJson.asObjectOrNull() ?: return@mapNotNull null
                val status = room["status"]?.asArrayOrNull()
                    ?.mapNotNull { it.asIntOrNull() }
                    ?: return@mapNotNull null
                val freeSections = status.mapIndexedNotNull { index, value ->
                    if (value == 0) index + 1 else null
                }
                if (!requestedSections.all { it in freeSections }) return@mapNotNull null
                EmptyRoom(
                    name = roomName,
                    campus = campus,
                    building = buildingName,
                    availableSections = freeSections,
                    capacity = room["size"]?.asIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            }
        }.sortedWith(compareBy<EmptyRoom> { it.building }.thenBy { it.name })
    }
}
