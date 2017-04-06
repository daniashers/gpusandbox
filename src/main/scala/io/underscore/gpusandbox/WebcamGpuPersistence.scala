package io.underscore.gpusandbox

import java.awt.image.BufferedImage
import java.awt.{Color, GradientPaint, Graphics2D}
import javax.swing.JFrame

import com.github.sarxos.webcam.Webcam
import com.jogamp.opencl.CLMemory.Mem
import com.jogamp.opencl.gl.{CLGLContext, CLGLTexture2d}
import com.jogamp.opencl.{CLCommandQueue, CLDevice, CLKernel, CLPlatform}
import com.jogamp.opengl._
import com.jogamp.opengl.awt.GLCanvas
import com.jogamp.opengl.fixedfunc.GLMatrixFunc
import com.jogamp.opengl.glu.GLU
import com.jogamp.opengl.util.Animator
import com.jogamp.opengl.util.texture.awt.AWTTextureIO
import com.jogamp.opengl.util.texture.{Texture, TextureCoords, TextureIO}


object WebcamGpuPersistence extends App {
  private val glu: GLU = new GLU()

  private val Width = 640
  private val Height = 480

  private var liveTexture: Texture = _
  private var backTexture: Texture = _
  private var camTexture: Texture = _

  private var clContext: CLGLContext = _

  private var cpyKernel: CLKernel = _
  private var blendKernel: CLKernel = _

  private var commandQueue: CLCommandQueue = _

  private var liveBuffer: CLGLTexture2d[_] = _
  private var backBuffer: CLGLTexture2d[_] = _
  private var camBuffer: CLGLTexture2d[_] = _

  private val webcam: Webcam = Webcam.getDefault

  val programSource =
    """
      |const sampler_t samplerA = CLK_NORMALIZED_COORDS_FALSE |
      |                           CLK_ADDRESS_REPEAT          |
      |                           CLK_FILTER_NEAREST;
      |
      |kernel void cpy(read_only image2d_t src, write_only image2d_t dst) {
      |  unsigned int x = get_global_id(0);
      |  unsigned int y = get_global_id(1);
      |  float4 pixel = read_imagef(src, samplerA, (int2)(x,y));
      |  write_imagef(dst, (int2)(x, y), pixel);
      |}
      |
      |kernel void blend(read_only image2d_t prev, read_only image2d_t new, write_only image2d_t out) {
      |  unsigned int x = get_global_id(0);
      |  unsigned int y = get_global_id(1);
      |  float ratio = 0.9f;
      |  float4 oldPixel = read_imagef(prev, samplerA, (int2)(x, y));
      |  float4 newPixel = read_imagef(new, samplerA, (int2)(x, y));
      |  float4 outPixel = newPixel;
      |  outPixel.x = newPixel.x * (1.0f-ratio) + oldPixel.x * (ratio);
      |  outPixel.y = newPixel.y * (1.0f-ratio) + oldPixel.y * (ratio);
      |  outPixel.z = newPixel.z * (1.0f-ratio) + oldPixel.z * (ratio);
      |  outPixel.w = newPixel.w * (1.0f-ratio) + oldPixel.w * (ratio);
      |  write_imagef(out, (int2)(x, y), outPixel);
      |}
    """.stripMargin

  val testImage: BufferedImage = {
    val img = new BufferedImage(Width, Height, BufferedImage.TYPE_4BYTE_ABGR)
    val g: Graphics2D = img.createGraphics()
    g.setPaint(new GradientPaint(0, 0, Color.RED, img.getWidth(), img.getHeight(), Color.WHITE))
    g.fillRect(0, 0, img.getWidth(), img.getHeight())
    g.dispose()
    img
  }

  class Listener extends GLEventListener {
    def init(drawable: GLAutoDrawable): Unit = {

      // Do all the CL shit
      if (clContext == null) {

        // find gl compatible device
        val devices: Array[CLDevice] = CLPlatform.getDefault().listCLDevices()
        val device: CLDevice = devices.find(_.isGLMemorySharingSupported).getOrElse(throw new RuntimeException("couldn't find any CL/GL memory sharing devices .."))

        // create OpenCL context before creating any OpenGL objects you want to share with OpenCL (AMD driver requirement)
        clContext = CLGLContext.create(drawable.getContext, device)

        // enable GL error checking using the composable pipeline
        drawable.setGL(new DebugGL2(drawable.getGL.getGL2))

        // OpenGL initialization
        val gl: GL2 = drawable.getGL.getGL2

        gl.setSwapInterval(1)


        gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION)
        gl.glLoadIdentity()
        glu.gluOrtho2D(0, 1, 0, 1)
        gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW)
        gl.glLoadIdentity()

        // Create a TextureData and Texture from it
        val textureData = AWTTextureIO.newTextureData(gl.getGLProfile, testImage, false)
        liveTexture = TextureIO.newTexture(textureData)
        liveBuffer = clContext.createFromGLTexture2d(GL.GL_TEXTURE_2D, liveTexture.getTextureObject, 0, Mem.READ_WRITE)

        // Create the back-buffer texture
        {
          val textureData = AWTTextureIO.newTextureData(gl.getGLProfile, testImage, false)
          backTexture = TextureIO.newTexture(textureData)
          backBuffer = clContext.createFromGLTexture2d(GL.GL_TEXTURE_2D, backTexture.getTextureObject, 0, Mem.READ_WRITE)
        }

        // Create the texture to receive web cam data
        {
          val textureData = AWTTextureIO.newTextureData(gl.getGLProfile, testImage, false)
          camTexture = TextureIO.newTexture(textureData)
          camBuffer = clContext.createFromGLTexture2d(GL.GL_TEXTURE_2D, camTexture.getTextureObject, 0, Mem.READ_WRITE)
        }



        gl.glFinish()

        // init OpenCL

        // build program
        val program = clContext.createProgram(programSource)
        program.build()

        commandQueue = clContext.getMaxFlopsDevice.createCommandQueue()

        cpyKernel = program.createCLKernel("cpy")
          .putArg(liveBuffer)
          .putArg(backBuffer)

        blendKernel = program.createCLKernel("blend")
          .putArg(backBuffer)
          .putArg(camBuffer)
          .putArg(liveBuffer)

        // start rendering thread
        val animator: Animator = new Animator(drawable)
        animator.start()

      }

      // End CL shit
    }


    def display(drawable: GLAutoDrawable): Unit = {
      val gl: GL2 = drawable.getGL.getGL2
      gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT)


      //WEBCAM CODE
      val image = webcam.getImage
      camTexture.updateImage(gl, AWTTextureIO.newTextureData(gl.getGLProfile, image, false))
      //END WEBCAM CODE

      commandQueue
        .putAcquireGLObject(liveBuffer)
        .putAcquireGLObject(backBuffer)
        .putAcquireGLObject(camBuffer)
        .put2DRangeKernel(blendKernel, 0, 0, Width, Height, 0, 0)
        .put2DRangeKernel(cpyKernel, 0, 0, Width, Height, 0, 0)
        .putReleaseGLObject(camBuffer)
        .putReleaseGLObject(backBuffer)
        .putReleaseGLObject(liveBuffer)
        .finish()


      // Now draw one quad with the texture
      liveTexture.enable(gl)
      liveTexture.bind(gl)
      gl.glTexEnvi(GL2ES1.GL_TEXTURE_ENV, GL2ES1.GL_TEXTURE_ENV_MODE, GL.GL_REPLACE)
      val coords: TextureCoords = liveTexture.getImageTexCoords
      gl.glBegin(GL2GL3.GL_QUADS)
      gl.glTexCoord2f(coords.left(), coords.bottom())
      gl.glVertex3f(0, 0, 0)
      gl.glTexCoord2f(coords.right(), coords.bottom())
      gl.glVertex3f(1, 0, 0)
      gl.glTexCoord2f(coords.right(), coords.top())
      gl.glVertex3f(1, 1, 0)
      gl.glTexCoord2f(coords.left(), coords.top())
      gl.glVertex3f(0, 1, 0)
      gl.glEnd()
      liveTexture.disable(gl)
    }

    def displayChanged(drawable: GLAutoDrawable, modeChanged: Boolean, deviceChanged: Boolean): Unit = ()
    def reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int): Unit = ()
    def dispose(drawable: GLAutoDrawable): Unit = ()
  }

  //
  // MAIN CODE BEGINS HERE
  //

  val viewSize = webcam.getViewSizes()(2) // horrible code to obtain a specific resolution, device-dependent, you may have to change this for your device.
  webcam.setViewSize(viewSize)
  webcam.open()

  // Create top-level window
  val frame: JFrame = new JFrame("Texture Image Test")

  frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)

  // Now set up the main GLCanvas
  val mainCanvas = new GLCanvas()
  mainCanvas.addGLEventListener(new Listener())

  mainCanvas.repaint()

  frame.getContentPane.add(mainCanvas)
  frame.setSize(Width, Height)
  frame.setVisible(true)

}