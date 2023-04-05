package com.jetbrains.edu.sql.kotlin.courseGeneration

import com.intellij.database.actions.runDataSourceGeneralRefresh
import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.*
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DbPsiFacadeImpl
import com.intellij.database.util.DasUtil
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueueImpl
import com.intellij.sql.SqlFileType
import com.intellij.sql.psi.SqlLanguage
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import com.jetbrains.edu.jvm.courseGeneration.JvmCourseGenerationTestBase
import com.jetbrains.edu.learning.*
import com.jetbrains.edu.learning.actions.NextTaskAction
import com.jetbrains.edu.learning.actions.PreviousTaskAction
import com.jetbrains.edu.learning.courseFormat.CheckStatus
import com.jetbrains.edu.learning.courseFormat.Course
import com.jetbrains.edu.learning.courseFormat.ext.allTasks
import com.jetbrains.edu.learning.courseFormat.tasks.Task
import com.jetbrains.edu.sql.jvm.gradle.SqlGradleCourseBuilderBase
import com.jetbrains.edu.sql.jvm.gradle.findDataSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull

class SqlDatabaseSetupTest : JvmCourseGenerationTestBase() {

  fun `test data source creation`() {
    val course = course(language = SqlLanguage.INSTANCE, environment = "Kotlin") {
      lesson("lesson1") {
        eduTask("task1") {
          taskFile("src/task.sql")
        }
        eduTask("task2") {
          taskFile("src/task.sql")
        }
      }
      frameworkLesson("framework_lesson2") {
        eduTask("task3") {
          taskFile("src/task.sql")
        }
        eduTask("task4") {
          taskFile("src/task.sql")
        }
      }
    }

    createCourseStructure(course)

    val dataSources = LocalDataSourceManager.getInstance(project).dataSources
    val tasks = course.allTasks.toMutableSet()

    for (dataSource in dataSources) {
      val url = dataSource.url ?: error("Unexpected null url for `${dataSource.name}` data source")
      val result = DATA_SOURCE_URL_REGEX.matchEntire(url) ?: error("`$url` of `${dataSource.name}` data source doesn't match `${DATA_SOURCE_URL_REGEX.pattern}` regex")
      val taskPath = result.groups["path"]!!.value
      // It relies on fact that `CourseGenerationTestBase` is heavy test, and it uses real filesystem
      val taskDir = LocalFileSystem.getInstance().findFileByPath(taskPath) ?: error("Can't find `$taskPath`")
      val task = taskDir.getTask(project) ?: error("Can't find task for `${dataSource.name}` data source")
      tasks -= task
    }

    check(tasks.isEmpty()) {
      "Tasks ${tasks.joinToString { "`${it.presentableName}`" }} don't have data sources"
    }
  }

  fun `test attach jdbc console`() {
    val course = course(language = SqlLanguage.INSTANCE, environment = "Kotlin") {
      lesson("lesson1") {
        eduTask("task1") {
          taskFile("src/task.sql")
        }
        eduTask("task2") {
          taskFile("src/task.sql")
        }
      }
    }

    createCourseStructure(course)

    val fileEditorManager = FileEditorManager.getInstance(project)
    fileEditorManager.openFiles.forEach {
      checkJdbcConsoleForFile(it)
    }
    val sqlFile = findFile("lesson1/task2/src/task.sql")
    fileEditorManager.openFile(sqlFile, true)
    checkJdbcConsoleForFile(sqlFile)
  }

  fun `test attach jdbc console for framework tasks`() {
    val course = course(language = SqlLanguage.INSTANCE, environment = "Kotlin") {
      frameworkLesson("lesson1") {
        eduTask("task1") {
          taskFile("src/task.sql")
        }
        eduTask("task2") {
          taskFile("src/task.sql")
          taskFile("src/task2.sql")
        }
      }
    }

    createCourseStructure(course)

    checkJdbcConsoleForFile("lesson1/task/src/task.sql")

    withVirtualFileListener(course) {
      course.findTask("lesson1", "task1").status = CheckStatus.Solved
      testAction(NextTaskAction.ACTION_ID)
    }

    checkJdbcConsoleForFile("lesson1/task/src/task.sql")
    checkJdbcConsoleForFile("lesson1/task/src/task2.sql")

    withVirtualFileListener(course) {
      testAction(PreviousTaskAction.ACTION_ID)
    }

    checkJdbcConsoleForFile("lesson1/task/src/task.sql")
  }

  fun `test database view structure`() {
    val course = course(language = SqlLanguage.INSTANCE, environment = "Kotlin") {
      lesson("lesson1") {
        eduTask("task1") {
          taskFile("src/task.sql")
        }
        eduTask("task2") {
          taskFile("src/task.sql")
        }
      }
      lesson("lesson2") {
        eduTask("task3") {
          taskFile("src/task.sql")
        }
        eduTask("task4") {
          taskFile("src/task.sql")
        }
      }
      section("section1") {
        lesson("lesson3") {
          eduTask("task5") {
            taskFile("src/task.sql")
          }
          eduTask("task6") {
            taskFile("src/task.sql")
          }
        }
        lesson("lesson4") {
          eduTask("task7") {
            taskFile("src/task.sql")
          }
          eduTask("task8") {
            taskFile("src/task.sql")
          }
        }
      }
      // check special symbols,
      section("section2", customPresentableName = "section/2\\") {
        lesson("lesson5", customPresentableName = "les/s/on5") {
          eduTask("task9") {
            taskFile("src/task.sql")
          }
          eduTask("task10", customPresentableName = "/task:1:0/") {
            taskFile("src/task.sql")
          }
        }
      }
    }

    createCourseStructure(course)

    val databaseView = DatabaseView.getDatabaseView(project)
    val tree = databaseView.panel.tree

    PlatformTestUtil.waitWhileBusy(tree)
    PlatformTestUtil.expandAll(tree)

    PlatformTestUtil.assertTreeEqual(tree, """
      -Root Group
       -Group (lesson1) inside Root Group
        task1: DSN
        task2: DSN
       -Group (lesson2) inside Root Group
        task3: DSN
        task4: DSN
       -Group (section1) inside Root Group
        -Group (section1/lesson3) inside Group (section1) inside Root Group
         task5: DSN
         task6: DSN
        -Group (section1/lesson4) inside Group (section1) inside Root Group
         task7: DSN
         task8: DSN
       -Group (section 2\) inside Root Group
        -Group (section 2\/les s on5) inside Group (section 2\) inside Root Group
         /task:1:0/: DSN
         task9: DSN
    """.trimIndent())
  }

  @Suppress("SqlDialectInspection", "SqlNoDataSourceInspection")
  fun `test database initialization`() {
    val course = course(language = SqlLanguage.INSTANCE, environment = "Kotlin") {
      lesson("lesson1") {
        eduTask("task1") {
          taskFile("src/task.sql")
          sqlTaskFile(SqlGradleCourseBuilderBase.INIT_SQL, """
            create table if not exists STUDENTS_1;
          """)
        }
        eduTask("task2") {
          taskFile("src/task.sql")
          sqlTaskFile(SqlGradleCourseBuilderBase.INIT_SQL, """
            create table if not exists STUDENTS_2;
          """)
        }
      }
    }

    createCourseStructure(course)

    checkTable(course.findTask("lesson1", "task1"), "STUDENTS_1")
    checkTable(course.findTask("lesson1", "task2"), "STUDENTS_2")
  }

  @Suppress("SqlDialectInspection", "SqlNoDataSourceInspection")
  fun `test database initialization in framework lessons`() {
    val course = course(language = SqlLanguage.INSTANCE, environment = "Kotlin") {
      frameworkLesson("lesson1") {
        eduTask("task1") {
          taskFile("src/task.sql")
          sqlTaskFile(SqlGradleCourseBuilderBase.INIT_SQL, """
            create table if not exists STUDENTS_1;
          """)
        }
        eduTask("task2") {
          taskFile("src/task.sql")
          sqlTaskFile(SqlGradleCourseBuilderBase.INIT_SQL, """
            create table if not exists STUDENTS_2;
          """)
        }
      }
    }

    createCourseStructure(course)

    val task1 = course.findTask("lesson1", "task1")
    val task2 = course.findTask("lesson1", "task2")

    checkTable(task1, "STUDENTS_1")
    checkTable(task2, "STUDENTS_2", shouldExist = false)

    withVirtualFileListener(course) {
      // Hack to check the plugin doesn't evaluate init.sql script twice
      // If script is evaluated the second time, it will create `STUDENTS_1_1` table that test checks below
      val initSql = findFile("lesson1/task/${SqlGradleCourseBuilderBase.INIT_SQL}")
      runWriteAction {
        // language=SQL
        VfsUtil.saveText(initSql, """
          create table if not exists STUDENTS_1_1;          
        """.trimIndent())
      }

      task1.status = CheckStatus.Solved
      testAction(NextTaskAction.ACTION_ID)
    }

    checkTable(task2, "STUDENTS_2")

    withVirtualFileListener(course) {
      testAction(PreviousTaskAction.ACTION_ID)
    }

    checkTable(task1, "STUDENTS_1_1", shouldExist = false)
  }

  override fun createCourseStructure(course: Course) {
    super.createCourseStructure(course)
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
  }

  private fun checkTable(task: Task, tableName: String, shouldExist: Boolean = true) {
    val dataSource = task.findDataSource(project) ?: error("Can't find data source for `${task.name}`")
    val scope = TreePattern(
      TreePatternUtils.create(
        ObjectName.quoted("DB"),
        ObjectKind.DATABASE,
        TreePatternUtils.create(ObjectName.quoted("PUBLIC"), ObjectKind.SCHEMA)
      )
    )
    dataSource.introspectionScope = scope

    refreshDataSource(dataSource)

    val tables = DasUtil.getTables(dataSource as DasDataSource).toList()
    val table = tables.find { it.name.equals(tableName, ignoreCase = true) }

    if (shouldExist) {
      assertNotNull("Failed to find `$tableName` table for `${task.name}` task ", table)
    }
    else {
      assertNull("`${task.name}`'s data source shouldn't contain `$tableName` table", table)
    }
  }

  // Approach is taken from tests of database plugin
  private fun refreshDataSource(dataSource: LocalDataSource) {
    val task = runDataSourceGeneralRefresh(project, dataSource) ?: error("Can't create refresh task")
    PlatformTestUtil.waitForFuture(task.toFuture(), TimeUnit.MINUTES.toMillis(2))
    flushDataSources(project)
  }

  // Copied from `com.intellij.database.DatabaseTestUtil`, since `DatabaseTestUtil` is not a part of IDE distribution
  private fun flushDataSources(project: Project) {
    (DbPsiFacade.getInstance(project) as DbPsiFacadeImpl).flushUpdates()
    UIUtil.dispatchAllInvocationEvents()
    waitFsSynchronizationFinished()
    UIUtil.dispatchAllInvocationEvents()
  }

  // Copied from `com.intellij.database.DatabaseTestUtil`, since `DatabaseTestUtil` is not a part of IDE distribution
  private fun waitFsSynchronizationFinished() {
    ApplicationManager.getApplication().assertIsDispatchThread()
    UIUtil.dispatchAllInvocationEvents()
    while (RefreshQueueImpl.isRefreshInProgress()) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
  }

  private inline fun withVirtualFileListener(course: Course, action: () -> Unit) {
    withVirtualFileListener(project, course, testRootDisposable, action)
  }

  private fun checkJdbcConsoleForFile(path: String) {
    val fileEditorManager = FileEditorManager.getInstance(project)
    val sqlFile = findFile(path)
    fileEditorManager.openFile(sqlFile, true)
    checkJdbcConsoleForFile(sqlFile)
  }

  private fun checkJdbcConsoleForFile(file: VirtualFile) {
    if (file.fileType != SqlFileType.INSTANCE) return
    // `SqlGradleStartupActivity` attaches console using `invokeLater` so we have to dispatch events in EDT
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    val console = JdbcConsoleProvider.getValidConsole(project, file)

    assertNotNull(console, "Can't find jdbc console for `$file`")

    val task = file.getTaskFile(project)?.task ?: error("Can't find task for $file")
    val taskDataSource = task.findDataSource(project) ?: error("Can't find data source for `${task.name}`")

    assertEquals("Wrong data source url", taskDataSource.url, console.dataSource.url)
  }

  companion object {
    /**
     * Heavily depends on [com.jetbrains.edu.sql.jvm.gradle.SqlGradleStartupActivity.databaseUrl]
     */
    private val DATA_SOURCE_URL_REGEX = "jdbc:h2:file:(?<path>.*)/db".toRegex()
  }
}
