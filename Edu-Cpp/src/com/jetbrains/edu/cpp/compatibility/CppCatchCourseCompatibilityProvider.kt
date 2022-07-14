package com.jetbrains.edu.cpp.compatibility

import com.jetbrains.edu.EducationalCoreIcons
import com.jetbrains.edu.learning.compatibility.CourseCompatibilityProvider
import com.jetbrains.edu.learning.courseFormat.PluginInfo
import javax.swing.Icon

class CppCatchCourseCompatibilityProvider : CourseCompatibilityProvider {
  override fun requiredPlugins(): List<PluginInfo> = listOf(PluginInfo.CATCH)

  override val technologyName: String get() = "C/C++"
  override val logo: Icon get() = EducationalCoreIcons.CppLogo
}
