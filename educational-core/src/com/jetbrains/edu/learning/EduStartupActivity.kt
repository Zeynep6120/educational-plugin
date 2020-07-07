package com.jetbrains.edu.learning

import com.google.common.annotations.VisibleForTesting
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.jetbrains.edu.coursecreator.CCUtils
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.ext.configurator
import com.jetbrains.edu.learning.courseFormat.ext.getDescriptionFile
import com.jetbrains.edu.learning.courseFormat.ext.taskDescriptionHintBlocks
import com.jetbrains.edu.learning.handlers.UserCreatedFileListener
import com.jetbrains.edu.learning.projectView.CourseViewPane
import com.jetbrains.edu.learning.statistics.EduCounterUsageCollector
import com.jetbrains.edu.learning.taskDescription.ui.TaskDescriptionView
import java.io.IOException

class EduStartupActivity : StartupActivity.DumbAware {

  override fun runActivity(project: Project) {
    if (!EduUtils.isEduProject(project)) return

    val manager = StudyTaskManager.getInstance(project)
    val connection = ApplicationManager.getApplication().messageBus.connect(manager)
    if (!isUnitTestMode) {
      if (EduUtils.isStudentProject(project)) {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, UserCreatedFileListener(project))
      }
      EduDocumentListener.setGlobalListener(project, manager)
      selectProjectView(project, true)
    }

    connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
      TaskDescriptionView.updateAllTabs(TaskDescriptionView.getInstance(project))
    })

    StartupManager.getInstance(project).runWhenProjectIsInitialized {
      val course = manager.course
      if (course == null) {
        LOG.warn("Opened project is with null course")
        return@runWhenProjectIsInitialized
      }

      val propertiesComponent = PropertiesComponent.getInstance(project)
      if (CCUtils.isCourseCreator(project) && !propertiesComponent.getBoolean(HINTS_IN_DESCRIPTION_PROPERTY)) {
        moveHintsToTaskDescription(project, course)
        propertiesComponent.setValue(HINTS_IN_DESCRIPTION_PROPERTY, true)
      }

      setupProject(project, course)
      ApplicationManager.getApplication().invokeLater {
        runWriteAction { EduCounterUsageCollector.eduProjectOpened(course) }
      }
    }
  }

  // In general, it's hack to select proper Project View pane for course projects
  // Should be replaced with proper API
  private fun selectProjectView(project: Project, retry: Boolean) {
    ToolWindowManager.getInstance(project).invokeLater(Runnable {
      val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)
      // Since 2020.1 project view tool window can be uninitialized here yet
      if (toolWindow == null) {
        if (retry) {
          selectProjectView(project, false)
        }
        else {
          LOG.warn("Failed to show Course View because Project View is not initialized yet")
        }
        return@Runnable
      }
      val projectView = ProjectView.getInstance(project)
      if (projectView != null) {
        val selectedViewId = ProjectView.getInstance(project).currentViewId
        if (CourseViewPane.ID != selectedViewId) {
          projectView.changeView(CourseViewPane.ID)
        }
      }
      else {
        LOG.warn("Failed to select Project View")
      }
    })
  }

  private fun setupProject(project: Project, course: Course) {
    val configurator = course.configurator
    if (configurator == null) {
      LOG.warn("Failed to refresh gradle project: configurator for `${course.languageID}` is null")
      return
    }

    if (!isUnitTestMode && EduUtils.isNewlyCreated(project)) {
      configurator.courseBuilder.refreshProject(project, RefreshCause.PROJECT_CREATED)
    }

    // Android Studio creates `gradlew` not via VFS so we have to refresh project dir
    VfsUtil.markDirtyAndRefresh(false, true, true, project.courseDir)
  }

  companion object {
    private val LOG = Logger.getInstance(EduStartupActivity::class.java)
    private const val HINTS_IN_DESCRIPTION_PROPERTY = "HINTS_IN_TASK_DESCRIPTION"

    @VisibleForTesting
    fun moveHintsToTaskDescription(project: Project, course: Course) {
      course.visitLessons { lesson ->
        for (task in lesson.taskList) {
          val text = StringBuffer(task.descriptionText)
          val hintBlocks = task.taskDescriptionHintBlocks()
          text.append(hintBlocks)
          task.descriptionText = text.toString()
          val file = task.getDescriptionFile(project)
          if (file != null) {
            runWriteAction {
              try {
                VfsUtil.saveText(file, text.toString())
              }
              catch (e: IOException) {
                LOG.warn(e.message)
              }
            }
          }

          for (value in task.taskFiles.values) {
            for (placeholder in value.answerPlaceholders) {
              placeholder.hints = emptyList()
            }
          }
        }
      }
    }
  }
}
