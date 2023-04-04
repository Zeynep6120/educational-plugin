package com.jetbrains.edu.learning.taskDescription.ui.specificTaskSwingPanels

import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.*
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

class RoundedWrapper(
  component: JComponent,
  private val arcSize: Int = 10,
  layoutManager: LayoutManager = VerticalLayout(0)
) : NonOpaquePanel(layoutManager, component) {

  init {
    background = UIUtil.getPanelBackground()
  }

  override fun paintChildren(g: Graphics?) {
    val g2 = g as Graphics2D
    try {
      g2.clip(getShape())
      super.paintChildren(g)
    } finally {
      g2.dispose()
    }
  }

  override fun paintComponent(g: Graphics?) {
    val g2 = g as Graphics2D
    try {
      GraphicsUtil.setupRoundedBorderAntialiasing(g2)
      g2.clip(getShape())
      super.paintComponent(g)
    } finally {
      g2.dispose()
    }
  }

  private fun getShape(): Shape {
    val rectangle = Rectangle(size)
    JBInsets.removeFrom(rectangle, insets)
    return RoundRectangle2D.Float(
      rectangle.x.toFloat(), rectangle.y.toFloat(),
      rectangle.width.toFloat(), rectangle.height.toFloat(),
      arcSize.toFloat(), arcSize.toFloat()
    )
  }
}