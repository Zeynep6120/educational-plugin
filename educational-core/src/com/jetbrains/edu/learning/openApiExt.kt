package com.jetbrains.edu.learning

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.edu.learning.courseFormat.Course

val isUnitTestMode: Boolean get() = ApplicationManager.getApplication().isUnitTestMode

val isReadAccessAllowed: Boolean get() = ApplicationManager.getApplication().isReadAccessAllowed

fun checkIsBackgroundThread() {
  check(!ApplicationManager.getApplication().isDispatchThread) {
    "Long running operation invoked on UI thread"
  }
}

val Project.courseDir: VirtualFile
  get() {
    return guessProjectDir() ?: error("Failed to find course dir for $this")
  }

val Project.course: Course? get() = StudyTaskManager.getInstance(this).course

inline fun <T> runReadActionInSmartMode(project: Project, crossinline runnable: () -> T): T {
  return DumbService.getInstance(project).runReadActionInSmartMode(Computable { runnable() })
}
