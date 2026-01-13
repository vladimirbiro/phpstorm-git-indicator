package com.example.gitstatus

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.Icon

class GitStatusWidget(project: Project) : EditorBasedWidget(project), StatusBarWidget.IconPresentation {

    companion object {
        const val ID = "GitStatusWidget"
        private const val CHECK_INTERVAL_SECONDS = 5L
    }

    private var hasUncommittedChanges = false
    private var scheduler: ScheduledExecutorService? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        startPeriodicCheck()
    }

    override fun dispose() {
        stopPeriodicCheck()
        super.dispose()
    }

    private fun startPeriodicCheck() {
        scheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay(
                { checkGitStatus() },
                0,
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun stopPeriodicCheck() {
        scheduler?.shutdown()
        scheduler = null
    }

    private fun checkGitStatus() {
        if (project.isDisposed) return

        val changeListManager = ChangeListManager.getInstance(project)
        val newStatus = changeListManager.allChanges.isNotEmpty() ||
                changeListManager.unversionedFilesPaths.isNotEmpty()

        if (newStatus != hasUncommittedChanges) {
            hasUncommittedChanges = newStatus
            myStatusBar?.updateWidget(ID)
        }
    }

    override fun getIcon(): Icon = StatusIcon(hasUncommittedChanges)

    override fun getTooltipText(): String =
        if (hasUncommittedChanges) "Uncommitted changes present" else "No uncommitted changes"

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? = com.intellij.util.Consumer { e ->
        if (!project.isDisposed) {
            val action = ActionManager.getInstance().getAction("CheckinProject")
            ActionUtil.invokeAction(action, e.component, ActionPlaces.STATUS_BAR_PLACE, null, null)
        }
    }

    private class StatusIcon(private val hasChanges: Boolean) : Icon {
        private val size = 12

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val color = if (hasChanges) Color(0x2F, 0x97, 0x30) else Color(0x44, 0x47, 0x4D)
            g.color = color
            g.fillOval(x, y, size, size)
        }

        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size
    }
}
