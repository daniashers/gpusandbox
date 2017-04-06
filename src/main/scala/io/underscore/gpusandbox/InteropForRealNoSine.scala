package io.underscore.gpusandbox

import javax.swing.{JFrame, SwingUtilities}

import com.jogamp.opencl.CLMemory.Mem
import com.jogamp.opencl._
import com.jogamp.opencl.gl.{CLGLBuffer, CLGLContext}
import com.jogamp.opengl._
import com.jogamp.opengl.awt.GLCanvas
import com.jogamp.opengl.fixedfunc.GLMatrixFunc._
import com.jogamp.opengl.glu.gl2.GLUgl2
import com.jogamp.opengl.util.Animator

object NoSine {
  val programSource =
    """
      |kernel void checkerboard(global int *bitmap, int width, int offset) {
      |  unsigned int x = get_global_id(0);
      |  unsigned int y = get_global_id(1);
      |  bitmap[y * width + x] = 0x00000000 + x % 256 + ((y % 256) << 8);
      |}
    """.stripMargin
}

class NoSine(width: Int, height: Int) extends GLEventListener {

  private val glu: GLUgl2 = new GLUgl2()

  private val BitmapWidth = 512
  private val BitmapHeight = 512

  private val BitmapBufferSize = BitmapHeight * BitmapWidth * 4

  private val glObjectIds: Array[Int] = Array.ofDim[Int](2)
  private val BitmapId = 1

  private var clContext: CLGLContext = _
  private var kernel: CLKernel = _
  private var commandQueue: CLCommandQueue = _
  private var bitmap: CLGLBuffer[_] = _

  def go(): Unit = {
    val self = this

    SwingUtilities.invokeLater(new Runnable() {
      def run(): Unit = {
        val config: GLCapabilities = new GLCapabilities(GLProfile.get(GLProfile.GL2))
        config.setSampleBuffers(true)
        config.setNumSamples(4)

        val canvas: GLCanvas = new GLCanvas(config)
        canvas.addGLEventListener(self)

        val frame: JFrame = new JFrame("JOGL-JOCL Interoperability Example")
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
        frame.add(canvas)
        frame.setSize(width, height)

        frame.setVisible(true)
      }
    })

  }


  override def init(drawable: GLAutoDrawable): Unit = {

    if (clContext == null) {

      // find gl compatible device
      val devices: Array[CLDevice] = CLPlatform.getDefault().listCLDevices()
      val device: CLDevice = devices.find(_.isGLMemorySharingSupported).getOrElse(throw new RuntimeException("couldn't find any CL/GL memory sharing devices .."))

      // create OpenCL context before creating any OpenGL objects
      // you want to share with OpenCL (AMD driver requirement)
      clContext = CLGLContext.create(drawable.getContext, device)

      // enable GL error checking using the composable pipeline
      drawable.setGL(new DebugGL2(drawable.getGL.getGL2))

      // OpenGL initialization
      val gl: GL2 = drawable.getGL.getGL2

      gl.setSwapInterval(1)

      gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2GL3.GL_LINE)

      gl.glGenBuffers(glObjectIds.length, glObjectIds, 0)

      gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER_EXT, glObjectIds(BitmapId))
      gl.glBufferData(GL2.GL_PIXEL_UNPACK_BUFFER_EXT, BitmapBufferSize, null, GL.GL_DYNAMIC_DRAW)
      gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER_EXT, 0)


      // push perspective view
      gl.glViewport(0, 0, BitmapWidth, BitmapHeight)
      gl.glMatrixMode(GL_PROJECTION)
      gl.glLoadIdentity()
      glu.gluOrtho2D(0, BitmapWidth, 0, BitmapHeight)
      gl.glMatrixMode(GL_MODELVIEW)
      gl.glLoadIdentity()


      gl.glFinish()

      // init OpenCL

      // build program
      val program = clContext.createProgram(NoSine.programSource)
      program.build()

      commandQueue = clContext.getMaxFlopsDevice.createCommandQueue()

      bitmap = clContext.createFromGLBuffer(glObjectIds(BitmapId), BitmapBufferSize, Mem.WRITE_ONLY)

      System.out.println("clsize: " + bitmap.getCLSize)
      System.out.println("cl buffer type: " + bitmap.getGLObjectType)
      System.out.println("shared with gl buffer: " + bitmap.getGLObjectID)

      kernel = program.createCLKernel("checkerboard")
        .putArg(bitmap)
        .putArg(256)
        .putArg(0)
        .rewind()

      // start rendering thread
      val animator: Animator = new Animator(drawable)
      animator.start()

    }
  }

  override def display(drawable: GLAutoDrawable): Unit = {
    val gl: GL2 = drawable.getGL.getGL2

    // ensure pipeline is clean before doing cl work
    gl.glFinish()

    commandQueue.putAcquireGLObject(bitmap)
      .put2DRangeKernel(kernel, 0, 0, BitmapWidth, BitmapHeight, 0, 0)
      .putReleaseGLObject(bitmap)
      .finish()

    // render bitmap
    gl.glClear(GL.GL_COLOR_BUFFER_BIT)

    gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER_EXT, glObjectIds(BitmapId))
    gl.glRasterPos2i(0, 0)

    gl.glDrawPixels(BitmapWidth, BitmapHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, 0)

    gl.glBindBuffer(GL2.GL_PIXEL_UNPACK_BUFFER_EXT, 0)

  }

  override def reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int): Unit = ()
  override def dispose(drawable: GLAutoDrawable): Unit = ()
}

object MainForRealNoSine extends App {

  GLProfile.initSingleton()
  new NoSine(512, 512).go()
}