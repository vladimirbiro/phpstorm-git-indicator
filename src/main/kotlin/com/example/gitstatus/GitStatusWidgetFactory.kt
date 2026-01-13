package com.example.gitstatus

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import git4idea.repo.GitRepositoryManager

class GitStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = GitStatusWidget.ID

    override fun getDisplayName(): String = "Git Status Indicator"

    override fun isAvailable(project: Project): Boolean {
        val repositoryManager = GitRepositoryManager.getInstance(project)
        return repositoryManager.repositories.isNotEmpty()
    }

    override fun createWidget(project: Project): StatusBarWidget = GitStatusWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
