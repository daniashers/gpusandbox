package io.underscore.gpusandbox

import java.nio.FloatBuffer

import com.jogamp.opencl._
import com.jogamp.opencl.CLMemory.Mem._

import com.jogamp.opencl.CLContext

/**
  * Hello Java OpenCL example. Adds all elements of buffer A to buffer B
  * and stores the result in buffer C.<br/>
  * Sample was inspired by the Nvidia VectorAdd example written in C/C++
  * which is bundled in the Nvidia OpenCL SDK.
  * @author Michael Bien
  */
object JogampCLDemo extends App {

  val programString: String =
    """
      | // OpenCL Kernel Function for element by element vector addition
      | kernel void VectorAdd(global const float* a, global const float* b, global float* c, int numElements) {
      |   // get index into global data array
      |   int iGID = get_global_id(0);
      |
      |   // bound check (equivalent to the limit on a 'for' loop for standard/serial C code
      |   if (iGID >= numElements)  {
      |     return;
      |   }
      |
      |   // add the vector elements
      |   c[iGID] = a[iGID] * b[iGID];
      | }
    """.stripMargin

  val elementCount = 100000 // Length of arrays to process

  // try the calculation on the CPU first.
  val arrayA = Array.tabulate[Float](elementCount)(i => i)
  val arrayB = Array.tabulate[Float](elementCount)(i => i)
  val arrayC = Array.ofDim[Float](elementCount)
  val cpuStartTime = System.nanoTime
  var i = 0
  while (i < elementCount) {
    arrayC(i) = arrayA(i) * arrayB(i)
    i = i + 1
  }
  val timeTakenCPU = System.nanoTime - cpuStartTime

  // set up (uses default CLPlatform and creates context for all devices)
  val context = CLContext.create()
  println("created "+context)

  try {

    // select fastest device
    val device: CLDevice = context.getMaxFlopsDevice()
    println("Device: " + device)

    // create command queue on device.
    val queue: CLCommandQueue = device.createCommandQueue()

    val localWorkSize = Math.min(device.getMaxWorkGroupSize(), 256) // Local work size dimensions
    val globalWorkSize = roundUp(localWorkSize, elementCount) // rounded up to the nearest multiple of the localWorkSize

    // load sources, create and build program
    val program: CLProgram = context.createProgram(programString).build()

    // A, B are input buffers, C is for the result
    val clBufferA: CLBuffer[FloatBuffer] = context.createFloatBuffer(globalWorkSize, READ_ONLY)
    val clBufferB: CLBuffer[FloatBuffer] = context.createFloatBuffer(globalWorkSize, READ_ONLY)
    val clBufferC: CLBuffer[FloatBuffer] = context.createFloatBuffer(globalWorkSize, WRITE_ONLY)

    println("used device memory: " + (clBufferA.getCLSize()+clBufferB.getCLSize()+clBufferC.getCLSize())/1024/1024 +" MB")

    // fill the buffers
    val bufferA = clBufferA.getBuffer
    val bufferB = clBufferB.getBuffer
    var x: Float = 0f
    while (bufferA.remaining != 0) {
      bufferA.put(x)
      bufferB.put(x)
      x = x + 1.0f
    }
    bufferA.rewind()
    bufferB.rewind()

    // get a reference to the kernel function with the name 'VectorAdd'
    // and map the buffers to its input parameters.
    val kernel: CLKernel = program.createCLKernel("VectorAdd")
    kernel.putArgs(clBufferA, clBufferB, clBufferC).putArg(elementCount)

    // asynchronous write of data to GPU device,
    // followed by blocking read to get the computed results back.
    val time0 = System.nanoTime()
    queue.putWriteBuffer(clBufferA, false)
      .putWriteBuffer(clBufferB, false)
      .put1DRangeKernel(kernel, 0, globalWorkSize, localWorkSize)
      .putReadBuffer(clBufferC, true)
    val timeTakenGPU = System.nanoTime() - time0

    // print first few elements of the resulting buffer to the console.
    print("Results snapshot: ")
    (0 until 10).foreach { i =>
      print(clBufferC.getBuffer().get() + ", ")
    }
    println("... " + clBufferC.getBuffer().remaining() + " more")

    println("CPU time: " + (timeTakenCPU / 1000) + " µs")
    println("GPU time: " + (timeTakenGPU / 1000) + " µs")

  } finally {
    // cleanup all resources associated with this context.
    context.release()
  }

  def roundUp(groupSize: Int, globalSize: Int): Int = {
    val r = globalSize % groupSize
    if (r == 0) {
      globalSize
    } else {
      globalSize + groupSize - r
    }
  }

}
