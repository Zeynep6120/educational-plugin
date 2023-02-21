package com.jetbrains.edu.learning.marketplace.api

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBAccountInfoService
import com.jetbrains.edu.learning.*
import com.jetbrains.edu.learning.api.ConnectorUtils
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholder
import com.jetbrains.edu.learning.courseFormat.AnswerPlaceholderDependency
import com.jetbrains.edu.learning.courseFormat.ext.getDir
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.learning.courseFormat.tasks.TheoryTask
import com.jetbrains.edu.learning.json.mixins.AnswerPlaceholderDependencyMixin
import com.jetbrains.edu.learning.json.mixins.AnswerPlaceholderWithAnswerMixin
import com.jetbrains.edu.learning.json.mixins.TaskFileMixin
import com.jetbrains.edu.learning.marketplace.SUBMISSIONS_SERVICE_STAGING_URL
import com.jetbrains.edu.learning.messages.EduCoreBundle
import com.jetbrains.edu.learning.submissions.SolutionFile
import com.jetbrains.edu.learning.submissions.checkNotEmpty
import com.jetbrains.edu.learning.submissions.findTaskFileInDirWithSizeCheck
import okhttp3.ConnectionPool
import org.jetbrains.annotations.VisibleForTesting
import retrofit2.converter.jackson.JacksonConverterFactory
import java.io.BufferedInputStream
import java.net.URL

class MarketplaceSubmissionsConnector {
  private val connectionPool: ConnectionPool = ConnectionPool()
  private val converterFactory: JacksonConverterFactory
  val objectMapper: ObjectMapper by lazy {
    val objectMapper = ConnectorUtils.createRegisteredMapper(SimpleModule())
    objectMapper.addMixIn(SolutionFile::class.java, TaskFileMixin::class.java)
    objectMapper.addMixIn(AnswerPlaceholder::class.java, AnswerPlaceholderWithAnswerMixin::class.java)
    objectMapper.addMixIn(AnswerPlaceholderDependency::class.java, AnswerPlaceholderDependencyMixin::class.java)
    objectMapper
  }

  private val submissionsServiceUrl: String = SUBMISSIONS_SERVICE_STAGING_URL

  init {
    converterFactory = JacksonConverterFactory.create(objectMapper)
  }

  private val submissionsService: SubmissionsService
    get() = submissionsService()

  // should be called only from background thread because of jbAccessToken
  private fun submissionsService(): SubmissionsService {
    if (!isUnitTestMode)
      checkIsBackgroundThread()

    val jbAuthService = JBAccountInfoService.getInstance() ?: error("Nullable JBAccountInfoService")
    val marketplaceConnector = MarketplaceConnector.getInstance()
    val jbAccessToken = marketplaceConnector.getJBAccessToken(jbAuthService) ?: error("Nullable JB account access token")

    val retrofit = createRetrofitBuilder(submissionsServiceUrl, connectionPool, jbAccessToken)
      .addConverterFactory(converterFactory)
      .build()

    return retrofit.create(SubmissionsService::class.java)
  }

  fun getAllSubmissions(courseId: Int): List<MarketplaceSubmission> {
    var currentPage = 1
    val allSubmissions = mutableListOf<MarketplaceSubmission>()
    do {
      val submissionsList = submissionsService.getAllSubmissionsForCourse(courseId, currentPage).executeHandlingExceptions()?.body() ?: break
      val submissions = submissionsList.submissions
      allSubmissions.addAll(submissions)
      currentPage += 1
    }
    while (submissions.isNotEmpty() && submissionsList.hasNext)
    return allSubmissions
  }

  fun markTheoryTaskAsCompleted(task: TheoryTask) {
    val emptySubmission = MarketplaceSubmission(task)
    LOG.info("Marking theory task ${task.name} as completed")
    doPostSubmission(task.course.id, task.id, emptySubmission)
  }

  fun loadSolutionFiles(solutionKey: String): List<SolutionFile> {
    if (!isUnitTestMode)
      checkIsBackgroundThread()

    val solutionsDownloadLink = submissionsService.getSolutionDownloadLink(solutionKey).executeParsingErrors().onError {
      error("failed to obtain download link for solution key $solutionKey")
    }.body()?.string() ?: error("Nullable solutionsDownloadLink")

    val solutions: String = loadSolutionByLink(solutionsDownloadLink)

    return objectMapper.readValue(solutions, object : TypeReference<List<SolutionFile>>() {})
           ?: error("Failed to load solution files for solution key $solutionKey")
  }

  fun postSubmission(project: Project, task: Task): MarketplaceSubmission {
    val solutionFiles = solutionFilesList(project, task)
    val solutionText = objectMapper.writeValueAsString(solutionFiles).trimIndent()

    val course = task.course

    val submission = MarketplaceSubmission(task.id, task.status, solutionText, solutionFiles, course.marketplaceCourseVersion)

    val postedSubmission = doPostSubmission(course.id, task.id, submission).onError { error("failed to post submission") }
    submission.id = postedSubmission.id
    submission.time = postedSubmission.time
    return submission
  }

  private fun doPostSubmission(courseId: Int, taskId: Int, submission: MarketplaceSubmission): Result<MarketplaceSubmission, String>{
    LOG.info("Posting submission for task $taskId")
    return submissionsService.postSubmission(courseId, submission.courseVersion, taskId, submission).executeParsingErrors().flatMap {
      val result = it.body()
      if (result == null) Err(EduCoreBundle.message("error.failed.to.post.solution")) else Ok(result)
    }
  }

  private fun solutionFilesList(project: Project, task: Task): List<SolutionFile> {
    val files = mutableListOf<SolutionFile>()
    val taskDir = task.getDir(project.courseDir) ?: error("Failed to find task directory ${task.name}")

    for (taskFile in task.taskFiles.values) {
      val virtualFile = findTaskFileInDirWithSizeCheck(taskFile, taskDir) ?: continue

      runReadAction {
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@runReadAction
        files.add(SolutionFile(taskFile.name, document.text, taskFile.isVisible, taskFile.answerPlaceholders))
      }
    }

    return files.checkNotEmpty()
  }

  companion object {
    private val LOG = logger<MarketplaceConnector>()

    @VisibleForTesting
    fun loadSolutionByLink(solutionsDownloadLink: String): String {
      return URL(solutionsDownloadLink).openConnection().getInputStream().use {
        val inputStream = BufferedInputStream(it)
        String(inputStream.readAllBytes())
      }
    }

    @JvmStatic
    fun getInstance(): MarketplaceSubmissionsConnector = service()
  }
}