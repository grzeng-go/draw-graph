package com.github.linwancen.plugin.graph.listeners

import com.github.linwancen.plugin.graph.settings.DrawGraphProjectState
import com.github.linwancen.plugin.graph.ui.GraphWindowFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.wm.ToolWindowManager

object TabListener : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val project = event.manager.project
        if (DumbService.isDumb(project)) {
            return
        }
        val graphToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Graph")
        if (DrawGraphProjectState.of(project).autoLoad && graphToolWindow?.isVisible == true) {
            GraphWindowFactory.openFileTab(project, event.newFile ?: return)
        }
    }
}