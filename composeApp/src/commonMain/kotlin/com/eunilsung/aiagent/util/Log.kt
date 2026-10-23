package com.eunilsung.aiagent.util

import com.eunilsung.aiagent.Config

object Log{
    fun message(message: String) {
        if (Config.Log.IS_SHOW_LOG) println("### [Debug] $message")
    }
}