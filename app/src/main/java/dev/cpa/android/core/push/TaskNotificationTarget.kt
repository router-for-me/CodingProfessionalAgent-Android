package dev.cpa.android.core.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import dev.cpa.android.MainActivity
import dev.cpa.android.R

private const val OPEN_TASK = "dev.cpa.android.OPEN_TASK"

data class TaskNotificationTarget(val route: String, val session: String) {
    fun toBundle() = Bundle().apply { putString("route", route); putString("session", session) }

    fun intent(context: Context) = Intent(context, MainActivity::class.java).apply {
        action = OPEN_TASK
        data = Uri.Builder().scheme("cpa").authority("notification").appendPath(route).appendPath(session).build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    companion object {
        fun from(bundle: Bundle?): TaskNotificationTarget? = valid(bundle?.getString("route"), bundle?.getString("session"))

        fun from(intent: Intent): TaskNotificationTarget? {
            val uri = intent.data ?: return null
            if (intent.action != OPEN_TASK || uri.scheme != "cpa" || uri.authority != "notification" || uri.pathSegments.size != 2) return null
            return valid(uri.pathSegments[0], uri.pathSegments[1])
        }

        fun valid(route: String?, session: String?): TaskNotificationTarget? {
            if (route == null || session == null || !validId(route) || !validId(session)) return null
            return TaskNotificationTarget(route, session)
        }
        private fun validId(id: String): Boolean =
            id.isNotBlank() && id.toByteArray().size <= 1024 && id.none(Char::isISOControl)
    }
}

internal data class TaskPushMessage(val target: TaskNotificationTarget, val type: String) {
    val route get() = target.route

    companion object {
        fun from(data: Map<String, String>): TaskPushMessage? {
            val type = data["notificationType"] ?: return null
            if (type != "ask" && type != "taskCompleted") return null
            val target = TaskNotificationTarget.valid(data["connectionId"], data["sessionId"]) ?: return null
            return TaskPushMessage(target, type)
        }
    }
}

internal fun notificationGroup(route: String) = "cpa.tasks.$route"

internal fun createTaskChannel(context: Context) {
    val channel = NotificationChannel(CHANNEL, context.getString(R.string.push_title), NotificationManager.IMPORTANCE_DEFAULT)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

internal fun postTaskNotification(context: Context, message: TaskPushMessage) {
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    createTaskChannel(context)
    val intent = message.target.intent(context)
    val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val body = if (message.type == "ask") R.string.cpa_push_ask_body else R.string.cpa_push_completed_body
    val notification = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_task_notification)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(body))
        .setGroup(notificationGroup(message.route))
        .setContentIntent(tap)
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)
        .build()
    context.getSystemService(NotificationManager::class.java).notify(intent.dataString, 1, notification)
}
