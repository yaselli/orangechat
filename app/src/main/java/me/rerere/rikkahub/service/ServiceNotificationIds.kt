package me.rerere.rikkahub.service

/** Fixed IDs for independently owned service/status notifications. Never derive IDs with +1. */
object ServiceNotificationIds {
    const val MUSIC = 2_001
    const val POMODORO = 3_001
    const val PROACTIVE = 20_001
    const val DAILY_CRON = 20_003
    const val DEVICE_EVENT = 20_005
    const val FLOATING_BUBBLE = 20_006
    const val WEB_SERVER = 20_007
    const val WEIXIN = 20_010
    const val QQ = 20_011
    const val WEIXIN_ERROR = 20_020
    const val QQ_ERROR = 20_021
    const val KEEP_ALIVE = 30_001
    const val VOICE_CALL = 40_001
}
