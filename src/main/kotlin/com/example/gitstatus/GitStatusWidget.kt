package com.example.gitstatus

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
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
    private var hasUnpushedCommits = false
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

        val newUncommittedStatus = ReadAction.compute<Boolean, Exception> {
            if (project.isDisposed) return@compute false
            val changeListManager = ChangeListManager.getInstance(project)
            changeListManager.allChanges.isNotEmpty() ||
                    changeListManager.unversionedFilesPaths.isNotEmpty()
        }

        val newUnpushedStatus = checkForUnpushedCommits()

        if (newUncommittedStatus != hasUncommittedChanges || newUnpushedStatus != hasUnpushedCommits) {
            hasUncommittedChanges = newUncommittedStatus
            hasUnpushedCommits = newUnpushedStatus
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    myStatusBar?.updateWidget(ID)
                }
            }
        }
    }

    private fun checkForUnpushedCommits(): Boolean {
        if (project.isDisposed) return false

        return try {
            val repositoryManager = GitRepositoryManager.getInstance(project)
            for (repository in repositoryManager.repositories) {
                val currentBranch = repository.currentBranch ?: continue
                val trackInfo = repository.getBranchTrackInfo(currentBranch.name) ?: continue
                val remoteBranch = trackInfo.remoteBranch

                val aheadCount = GitHistoryUtils.getNumberOfCommitsBetween(
                    repository,
                    remoteBranch.nameForLocalOperations,
                    currentBranch.name
                )?.toIntOrNull() ?: 0

                if (aheadCount > 0) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    override fun getIcon(): Icon = StatusIcon(hasUncommittedChanges, hasUnpushedCommits)

    override fun getTooltipText(): String {
        val uncommittedText = if (hasUncommittedChanges) "Uncommitted changes" else "No uncommitted changes"
        val unpushedText = if (hasUnpushedCommits) "Unpushed commits" else "No unpushed commits"
        return "$uncommittedText | $unpushedText"
    }

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? = com.intellij.util.Consumer { e ->
        if (!project.isDisposed) {
            val action = ActionManager.getInstance().getAction("CheckinProject")
            ActionUtil.invokeAction(action, e.component, ActionPlaces.STATUS_BAR_PLACE, null, null)
        }
    }

    private class StatusIcon(
        private val hasUncommittedChanges: Boolean,
        private val hasUnpushedCommits: Boolean
    ) : Icon {
        private val circleSize = 12
        private val spacing = 4
        private val totalWidth = circleSize * 2 + spacing

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // First circle: uncommitted changes (green if has changes, gray otherwise)
            val uncommittedColor = if (hasUncommittedChanges) Color(0x2F, 0x97, 0x30) else Color(0x44, 0x47, 0x4D)
            g2d.color = uncommittedColor
            g2d.fillOval(x, y, circleSize, circleSize)

            // Second circle: unpushed commits (blue if has unpushed, gray otherwise)
            val unpushedColor = if (hasUnpushedCommits) Color(0x1E, 0x90, 0xFF) else Color(0x44, 0x47, 0x4D)
            g2d.color = unpushedColor
            g2d.fillOval(x + circleSize + spacing, y, circleSize, circleSize)

            g2d.dispose()
        }

        override fun getIconWidth(): Int = totalWidth
        override fun getIconHeight(): Int = circleSize
    }
}
