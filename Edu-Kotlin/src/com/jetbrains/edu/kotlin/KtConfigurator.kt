package com.jetbrains.edu.kotlin

import com.jetbrains.edu.jvm.gradle.GradleConfiguratorBase
import com.jetbrains.edu.kotlin.checker.KtTaskCheckerProvider
import com.jetbrains.edu.learning.courseGeneration.GeneratorUtils.getInternalTemplateText
import org.jetbrains.kotlin.idea.KotlinIcons
import javax.swing.Icon

open class KtConfigurator : GradleConfiguratorBase() {

  private val myCourseBuilder = KtCourseBuilder()

  override fun getCourseBuilder() = myCourseBuilder

  override fun getTestFileName(): String = TESTS_KT

  override fun getMockFileName(text: String): String = TASK_KT

  override fun getTaskCheckerProvider() = KtTaskCheckerProvider()

  override fun getMockTemplate(): String = getInternalTemplateText(MOCK_KT)

  override fun getLogo(): Icon = KotlinIcons.SMALL_LOGO

  companion object {
    const val TESTS_KT = "Tests.kt"
    const val TASK_KT = "Task.kt"
    const val MOCK_KT = "Mock.kt"
  }
}
