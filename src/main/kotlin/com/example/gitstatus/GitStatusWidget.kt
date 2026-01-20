package com.example.gitstatus

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
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

    private var hasStagedChanges = false
    private var hasUnstagedChanges = false
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

        val (newStagedStatus, newUnstagedStatus) = checkStagedAndUnstagedChanges()
        val newUnpushedStatus = checkForUnpushedCommits()

        if (newStagedStatus != hasStagedChanges || newUnstagedStatus != hasUnstagedChanges || newUnpushedStatus != hasUnpushedCommits) {
            hasStagedChanges = newStagedStatus
            hasUnstagedChanges = newUnstagedStatus
            hasUnpushedCommits = newUnpushedStatus
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    myStatusBar?.updateWidget(ID)
                }
            }
        }
    }

    private fun checkStagedAndUnstagedChanges(): Pair<Boolean, Boolean> {
        if (project.isDisposed) return Pair(false, false)

        var hasStaged = false
        var hasUnstaged = false

        try {
            val repositoryManager = GitRepositoryManager.getInstance(project)
            for (repository in repositoryManager.repositories) {
                val root = repository.root

                // Use git status --porcelain to check staged/unstaged
                val handler = GitLineHandler(project, root, GitCommand.STATUS)
                handler.addParameters("--porcelain")
                val result = Git.getInstance().runCommand(handler)

                if (result.success()) {
                    for (line in result.output) {
                        if (line.length < 2) continue
                        val indexStatus = line[0]  // Status in staging area
                        val workTreeStatus = line[1]  // Status in working tree

                        // If index status is not space and not '?', file is staged
                        if (indexStatus != ' ' && indexStatus != '?') {
                            hasStaged = true
                        }

                        // If work tree status is not space, file has unstaged changes
                        // Also '?' means untracked file (unstaged)
                        if (workTreeStatus != ' ' || indexStatus == '?') {
                            hasUnstaged = true
                        }

                        if (hasStaged && hasUnstaged) break
                    }
                }

                if (hasStaged && hasUnstaged) break
            }
        } catch (e: Exception) {
            // Fallback: if git commands fail, return false
        }

        return Pair(hasStaged, hasUnstaged)
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

    override fun getIcon(): Icon = StatusIcon(hasStagedChanges, hasUnstagedChanges, hasUnpushedCommits)

    override fun getTooltipText(): String {
        val stagedText = when {
            hasStagedChanges && hasUnstagedChanges -> "Staged + Unstaged changes"
            hasStagedChanges -> "Staged changes (ready to commit)"
            hasUnstagedChanges -> "Unstaged changes (need git add)"
            else -> "No changes"
        }
        val unpushedText = if (hasUnpushedCommits) "Unpushed commits" else "All pushed"
        return "$stagedText | $unpushedText"
    }

    override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent>? = com.intellij.util.Consumer { e ->
        if (!project.isDisposed) {
            val action = ActionManager.getInstance().getAction("CheckinProject")
            ActionUtil.invokeAction(action, e.component, ActionPlaces.STATUS_BAR_PLACE, null, null)
        }
    }

    private class StatusIcon(
        private val hasStagedChanges: Boolean,
        private val hasUnstagedChanges: Boolean,
        private val hasUnpushedCommits: Boolean
    ) : Icon {
        private val circleSize = 12
        private val innerCircleSize = 6
        private val spacing = 4
        private val totalWidth = circleSize * 2 + spacing

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // First circle: staged changes (green if staged, gray otherwise)
            val stagedColor = if (hasStagedChanges) Color(0x2F, 0x97, 0x30) else Color(0x44, 0x47, 0x4D)
            g2d.color = stagedColor
            g2d.fillOval(x, y, circleSize, circleSize)

            // Inner red circle if has unstaged changes
            if (hasUnstagedChanges) {
                val innerX = x + (circleSize - innerCircleSize) / 2
                val innerY = y + (circleSize - innerCircleSize) / 2
                g2d.color = Color(0xE5, 0x3E, 0x3E) // Red color
                g2d.fillOval(innerX, innerY, innerCircleSize, innerCircleSize)
            }

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
