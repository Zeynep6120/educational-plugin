package com.jetbrains.edu.learning.stepik.hyperskill.courseGeneration

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AppIcon
import com.jetbrains.edu.learning.*
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.Lesson
import com.jetbrains.edu.learning.courseFormat.ext.configurator
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils
import com.jetbrains.edu.learning.stepik.builtInServer.EduBuiltInServerUtils
import com.jetbrains.edu.learning.stepik.hyperskill.*
import com.jetbrains.edu.learning.stepik.hyperskill.api.HyperskillConnector
import com.jetbrains.edu.learning.stepik.hyperskill.courseFormat.HyperskillCourse
import com.jetbrains.edu.learning.yaml.YamlFormatSynchronizer

object HyperskillProjectOpener {

  fun open(request: HyperskillOpenInProjectRequest): Result<Unit, String> {
    runInEdt {
      requestFocus()
    }
    if (openInOpenedProject(request)) return Ok(Unit)
    if (openInRecentProject(request)) return Ok(Unit)
    return openInNewProject(request)
  }

  private fun openInExistingProject(request: HyperskillOpenInProjectRequest,
                                    findProject: ((Course) -> Boolean) -> Pair<Project, Course>?): Boolean {
    val (project, course) = findProject { it is HyperskillCourse && it.hyperskillProject?.id == request.projectId }
                            ?: return false
    val hyperskillCourse = course as HyperskillCourse
    when (request) {
      is HyperskillOpenStepRequest -> {
        val stepId = request.stepId
        hyperskillCourse.addProblemWithFiles(project, stepId)
        hyperskillCourse.putUserData(HYPERSKILL_SELECTED_PROBLEM, request.stepId)
        runInEdt {
          requestFocus()
          EduUtils.navigateToStep(project, hyperskillCourse, stepId)
        }
      }
      is HyperskillOpenStageRequest -> {
        if (hyperskillCourse.getProjectLesson() == null) {
          computeUnderProgress(project, LOADING_PROJECT_STAGES) {
            val hyperskillProject = hyperskillCourse.hyperskillProject!!
            HyperskillConnector.getInstance().loadStages(hyperskillCourse, hyperskillProject.id, hyperskillProject)
          }
          hyperskillCourse.init(null, null, false)
          val projectLesson = hyperskillCourse.getProjectLesson()!!
          GeneratorUtils.createLesson(projectLesson, hyperskillCourse.getDir(project))
          YamlFormatSynchronizer.saveAll(project)
          HyperskillProjectComponent.synchronizeHyperskillProject(project)
        }
        hyperskillCourse.putUserData(HYPERSKILL_SELECTED_STAGE, request.stageId)
        runInEdt { openSelectedStage(hyperskillCourse, project) }
      }
    }
    return true
  }

  private fun openInOpenedProject(request: HyperskillOpenInProjectRequest): Boolean =
    openInExistingProject(request, EduBuiltInServerUtils::focusOpenProject)

  private fun openInRecentProject(request: HyperskillOpenInProjectRequest): Boolean =
    openInExistingProject(request, EduBuiltInServerUtils::openRecentProject)


  private fun openInNewProject(request: HyperskillOpenInProjectRequest): Result<Unit, String> {
    return getHyperskillCourseUnderProgress(request).map { hyperskillCourse ->
      runInEdt {
        requestFocus()
        HyperskillJoinCourseDialog(hyperskillCourse).show()
      }
    }
  }

  private fun getHyperskillCourseUnderProgress(request: HyperskillOpenInProjectRequest): Result<HyperskillCourse, String> {
    return computeUnderProgress(title = "Loading ${EduNames.JBA} Project") { indicator ->
      val hyperskillProject = HyperskillConnector.getInstance().getProject(request.projectId)
                              ?: return@computeUnderProgress Err(FAILED_TO_CREATE_PROJECT)

      if (!hyperskillProject.useIde) {
        return@computeUnderProgress Err(HYPERSKILL_PROJECT_NOT_SUPPORTED)
      }
      val languageId = HYPERSKILL_LANGUAGES[hyperskillProject.language]
      if (languageId == null) {
        return@computeUnderProgress Err("Unsupported language ${hyperskillProject.language}")
      }
      val hyperskillCourse = HyperskillCourse(hyperskillProject, languageId)
      if (hyperskillCourse.configurator == null) {
        return@computeUnderProgress Err("The project isn't supported (language: ${hyperskillProject.language}). " +
                                        "Check if all needed plugins are installed and enabled")
      }
      when (request) {
        is HyperskillOpenStepRequest -> {
          hyperskillCourse.addProblem(request.stepId)
          hyperskillCourse.putUserData(HYPERSKILL_SELECTED_PROBLEM, request.stepId)
        }
        is HyperskillOpenStageRequest -> {
          indicator.text2 = LOADING_PROJECT_STAGES
          HyperskillConnector.getInstance().loadStages(hyperskillCourse, request.projectId, hyperskillProject)
          hyperskillCourse.putUserData(HYPERSKILL_SELECTED_STAGE, request.stageId)
        }
      }
      Ok(hyperskillCourse)
    }
  }

  private fun HyperskillCourse.addProblem(stepId: Int): Pair<Lesson, Task> {
    fun Lesson.addProblem(): Task {
      var task = getTask(stepId)
      if (task == null) {
        val stepSource = computeUnderProgress(title = "Loading ${EduNames.JBA} Code Challenge") {
          HyperskillConnector.getInstance().getStepSource(stepId)
        } ?: error("Failed to load problem: id = $stepId")
        task = HyperskillConnector.getInstance().getTasks(course, this, listOf(stepSource)).first().apply {
          index = taskList.size + 1
        }
        addTask(task)
      }
      return task
    }

    val lesson = findOrCreateProblemsLesson()
    return lesson to lesson.addProblem()
  }

  private fun HyperskillCourse.addProblemWithFiles(project: Project, stepId: Int) {
    val (lesson, task) = addProblem(stepId)
    lesson.init(course, null, false)
    val lessonDir = lesson.getDir(project)
    if (lessonDir == null) {
      GeneratorUtils.createLesson(lesson, course.getDir(project))
      YamlFormatSynchronizer.saveAll(project)
    }
    else if (task.getDir(project) == null) {
      GeneratorUtils.createTask(task, lessonDir)
      YamlFormatSynchronizer.saveItem(lesson)
      YamlFormatSynchronizer.saveItem(task)
      YamlFormatSynchronizer.saveRemoteInfo(task)
      course.configurator?.courseBuilder?.refreshProject(project, RefreshCause.STRUCTURE_MODIFIED)
    }
  }

  // We have to use visible frame here because project is not yet created
  // See `com.intellij.ide.impl.ProjectUtil.focusProjectWindow` implementation for more details
  private fun requestFocus() {
    val frame = WindowManager.getInstance().findVisibleFrame()
    if (frame is IdeFrame) {
      AppIcon.getInstance().requestFocus(frame)
    }
    frame.toFront()
  }
}