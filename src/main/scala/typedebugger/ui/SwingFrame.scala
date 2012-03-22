package scala.typedebugger
package ui

import java.awt.BorderLayout
import java.awt.event.{WindowAdapter, WindowEvent}
import javax.swing.{Action => swingAction, _}

import scala.concurrent.Lock
import scala.tools.nsc.io.{File => ScalaFile, AbstractFile}

class SwingFrame(val prefuseComponent: PrefuseComponent, val frameName: String, val srcs: List[AbstractFile]) {

  val frame = new JFrame(frameName)
  val topPane = new JPanel(new BorderLayout())

  val ASTViewer = new JTextArea(30, 90)
  val sCodeViewer = new JTextArea(30, 30)

  def createFrame(lock: Lock): Unit = {
    lock.acquire // keep the lock until the user closes the window
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE)
    frame.addWindowListener(new WindowAdapter() {
      override def windowClosed(e: WindowEvent): Unit = lock.release
    })

    val tabFolder = new JTabbedPane()
    // Split right part even further
    val topSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, prefuseComponent, new JScrollPane(tabFolder))
    topSplitPane.setResizeWeight(0.7)
    
    topPane.add(topSplitPane)
    tabFolder.addTab("Tree", null, new JScrollPane(sCodeViewer))
    sCodeViewer.setEditable(false)
    //sCodeViewer.setEnabled(false)
    tabFolder.addTab("Transformed tree", null, new JScrollPane(ASTViewer))

    frame.getContentPane().add(topPane)
    frame.pack()
    frame.setVisible(true)
  }
  
}