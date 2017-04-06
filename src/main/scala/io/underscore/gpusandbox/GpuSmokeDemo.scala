package io.underscore.gpusandbox

import java.awt.{Color, GradientPaint, Graphics2D}
import java.awt.image.BufferedImage
import javax.swing.{JFrame, JPopupMenu}

import com.jogamp.opencl.CLMemory.Mem
import com.jogamp.opencl.gl.{CLGLContext, CLGLTexture2d}
import com.jogamp.opencl.{CLCommandQueue, CLDevice, CLKernel, CLPlatform}
import com.jogamp.opengl._
import com.jogamp.opengl.awt.GLCanvas
import com.jogamp.opengl.fixedfunc.GLMatrixFunc
import com.jogamp.opengl.glu.GLU
import com.jogamp.opengl.util.Animator
import com.jogamp.opengl.util.texture.{Texture, TextureCoords, TextureIO}
import com.jogamp.opengl.util.texture.awt.AWTTextureIO



object GpuSmokeDemo extends App {
  private val glu: GLU = new GLU()

  private val Width = 256
  private val Height = 256
  private val BitmapBufferSize = Width * Height * 4

  private var texture: Texture = _

  private var clContext: CLGLContext = _

  private var kernel: CLKernel = _
  private var cpyKernel: CLKernel = _
  private var fireKernel: CLKernel = _
  private var translateKernel: CLKernel = _

  private var commandQueue: CLCommandQueue = _
  private var bitmap: CLGLTexture2d[_] = _
  private var backBuffer: CLGLTexture2d[_] = _

  private val glObjectIds: Array[Int] = Array.ofDim[Int](1)

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
      |kernel void translate(read_only image2d_t prev, write_only image2d_t new) {
      |  unsigned int x = get_global_id(0);
      |  unsigned int y = get_global_id(1);
      |  float4 pixel = read_imagef(prev, samplerA, (int2)((x+1)%256,y));
      |  write_imagef(new, (int2)(x, y), pixel);
      |}
      |
      |kernel void fire(read_only image2d_t prev, write_only image2d_t new, int rndSeed) {
      |  unsigned int x = get_global_id(0);
      |  unsigned int y = get_global_id(1);
      |  if (x > 0 && x < 255) {
      |    if (y < 255) {
      |      float4 pixela = read_imagef(prev, samplerA, (int2)((x - 1) % 256, y + 1));
      |      float4 pixelb = read_imagef(prev, samplerA, (int2)((  x  ) % 256, y + 1));
      |      float4 pixelc = read_imagef(prev, samplerA, (int2)((x + 1) % 256, y + 1));
      |      float4 newPixel = (float4)((pixela.x + pixelb.x + pixelc.x) / 3.0f);
      |      write_imagef(new, (int2)(x, y), newPixel);
      |    } else {
      |      float random = (float)(((int)(1+sin((float)rndSeed/10 * x))) % 2);
      |      write_imagef(new, (int2)(x, y), (float4)random);
      |    }
      |  }
      |}
      |
      |kernel void checkerboard(write_only image2d_t bitmap) {
      |  unsigned int x = get_global_id(0);
      |  unsigned int y = get_global_id(1);
      |  int2 coord = (int2)(x, y);
      |  write_imagef(bitmap, coord, (float4)((float)x/256, (float)y/256, 0, 1.0));
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
        texture = TextureIO.newTexture(textureData)
        bitmap = clContext.createFromGLTexture2d(GL.GL_TEXTURE_2D, texture.getTextureObject, 0, Mem.READ_WRITE)

        // Create the back-buffer texture
        {
          val textureData = AWTTextureIO.newTextureData(gl.getGLProfile, testImage, false)
          texture = TextureIO.newTexture(textureData)
          backBuffer = clContext.createFromGLTexture2d(GL.GL_TEXTURE_2D, texture.getTextureObject, 0, Mem.READ_WRITE)
        }






        gl.glFinish()

        // init OpenCL

        // build program
        val program = clContext.createProgram(programSource)
        program.build()

        commandQueue = clContext.getMaxFlopsDevice.createCommandQueue()


        kernel = program.createCLKernel("checkerboard")
          .putArg(bitmap)
          .rewind()

        cpyKernel = program.createCLKernel("cpy")
          .putArg(bitmap)
          .putArg(backBuffer)

        translateKernel = program.createCLKernel("translate")
          .putArg(backBuffer)
          .putArg(bitmap)

        fireKernel = program.createCLKernel("fire")
          .putArg(backBuffer)
          .putArg(bitmap)
          .putArg(0) // random seed

        // start rendering thread
        val animator: Animator = new Animator(drawable)
        animator.start()

      }

      // End CL shit
    }


    def display(drawable: GLAutoDrawable): Unit = {
      val gl: GL2 = drawable.getGL.getGL2
      gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT)

      fireKernel.setArg(2, System.currentTimeMillis().toInt)

      commandQueue.putAcquireGLObject(bitmap)
        .put2DRangeKernel(cpyKernel, 0, 0, Width, Height, 0, 0)
        .put2DRangeKernel(fireKernel, 0, 0, Width, Height, 0, 0)
//        .put2DRangeKernel(kernel, 0, 0, Width, Height, 0, 0)
        .putReleaseGLObject(bitmap)
        .finish()

      // Now draw one quad with the texture
      texture.enable(gl)
      texture.bind(gl)
      gl.glTexEnvi(GL2ES1.GL_TEXTURE_ENV, GL2ES1.GL_TEXTURE_ENV_MODE, GL.GL_REPLACE)
      val coords: TextureCoords = texture.getImageTexCoords
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
      texture.disable(gl)
    }

    def displayChanged(drawable: GLAutoDrawable, modeChanged: Boolean, deviceChanged: Boolean): Unit = ()
    def reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int): Unit = ()
    def dispose(drawable: GLAutoDrawable): Unit = ()
  }

  //
  // MAIN CODE BEGINS HERE
  //

  JPopupMenu.setDefaultLightWeightPopupEnabled(false)

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

  while(true) {
    mainCanvas.repaint()
    Thread.sleep(10)
  }

}