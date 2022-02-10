package org.miner.dataset

import com.beust.klaxon.Json

enum class MethodUpdateType(@Json val typeName: String) {
    ADD("ADD"),
    MOVE("MOVE"),
    CHANGE("CHANGE")
}

data class RawDatasetSample(
    @Json
    val update: MethodUpdateType,
    @Json
    val code: String,
    @Json
    val comment: String,
    @Json
    val methodName: String,
    @Json
    val commitId: String,
    @Json
    val commitTime: String,
    @Json
    val newFileName: String
)