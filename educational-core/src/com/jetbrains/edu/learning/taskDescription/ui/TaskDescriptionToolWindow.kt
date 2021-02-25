/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.edu.learning.taskDescription.ui

import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import com.jetbrains.edu.learning.EduUtils
import com.jetbrains.edu.learning.StudyTaskManager
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseFormat.tasks.VideoTask
import com.jetbrains.edu.learning.messages.EduCoreBundle
import com.jetbrains.edu.learning.stepik.hyperskill.courseFormat.HyperskillCourse
import com.jetbrains.edu.learning.taskDescription.processImagesAndLinks
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.swing.JComponent


abstract class TaskDescriptionToolWindow(protected val project: Project) : Disposable {
  private val HINT_BLOCK_TEMPLATE = "<div class='" + HINT_HEADER + "'>Hint %s</div>" +
                                    "  <div class='hint_content'>" +
                                    " %s" +
                                    "  </div>"
  private val HINT_EXPANDED_BLOCK_TEMPLATE = "<div class='" + HINT_HEADER_EXPANDED + "'>Hint %s</div>" +
                                             "  <div class='hint_content'>" +
                                             " %s" +
                                             "  </div>"

  //default value of merging time span is 300 milliseconds, can be set in educational-core.xml
  @Suppress("LeakingThis")
  private val updateQueue = MergingUpdateQueue(TASK_DESCRIPTION_UPDATE,
                                               Registry.intValue(TASK_DESCRIPTION_UPDATE_DELAY_REGISTRY_KEY),
                                               true,
                                               null,
                                               this)

  abstract fun createTaskInfoPanel(): JComponent

  abstract fun createTaskSpecificPanel(): JComponent

  open fun updateTaskSpecificPanel(task: Task?) {}

  protected fun wrapHints(text: String, task: Task?): String {
    if (task is VideoTask) return text
    val document = Jsoup.parse(text)
    val hints = document.getElementsByClass("hint")
    if (hints.size == 1) {
      val hint = hints[0]
      val hintText = wrapHint(hint, "")
      hint.html(hintText)
      return document.html()
    }
    for (i in hints.indices) {
      val hint = hints[i]
      val hintText = wrapHint(hint, (i + 1).toString())
      hint.html(hintText)
    }
    return document.html()
  }

  protected open fun wrapHint(hintElement: Element, displayedHintNumber: String): String {
    val course = StudyTaskManager.getInstance(project).course
    val hintText: String = hintElement.html()
    if (course == null) {
      return String.format(HINT_BLOCK_TEMPLATE, displayedHintNumber, hintText)
    }

    val study = course.isStudy
    return if (study) {
      String.format(HINT_BLOCK_TEMPLATE, displayedHintNumber, hintText)
    }
    else {
      String.format(HINT_EXPANDED_BLOCK_TEMPLATE, displayedHintNumber, hintText)
    }
  }

  fun setTaskText(project: Project, task: Task?) {
    updateQueue.queue(Update.create(TASK_DESCRIPTION_UPDATE) {
      setText(getTaskDescriptionWithCodeHighlighting(project, task), task)
    })
  }

  protected abstract fun setText(text: String, task: Task?)

  override fun dispose() {}

  companion object {
    const val HINT_HEADER: String = "hint_header"
    const val HINT_HEADER_EXPANDED: String = "${HINT_HEADER} checked"
    private const val TASK_DESCRIPTION_UPDATE = "Task Description Update"
    private const val TASK_DESCRIPTION_UPDATE_DELAY_REGISTRY_KEY = "edu.task.description.update.delay"

    @VisibleForTesting
    fun getTaskDescriptionWithCodeHighlighting(project: Project, task: Task?): String {
      if (task != null) {
        val taskText = EduUtils.getTaskTextFromTask(project, task)
        if (taskText != null) {
          if (task is VideoTask) {
            return taskText
          }

          val processedText = processImagesAndLinks(project, task, taskText)

          val course = task.course
          val language = if (course is HyperskillCourse) PlainTextLanguage.INSTANCE else course.languageById ?: return processedText
          return EduCodeHighlighter.highlightCodeFragments(project, processedText, language)
        }
      }
      return EduCoreBundle.message("label.open.task")
    }
  }
}
