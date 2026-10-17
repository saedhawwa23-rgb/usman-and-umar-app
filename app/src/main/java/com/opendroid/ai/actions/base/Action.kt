package com.opendroid.ai.actions.base

import android.content.Context

interface Action {
    val name: String
    suspend fun execute(params: Map<String, String>, context: Context): ActionResult
}
