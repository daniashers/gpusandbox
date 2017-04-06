GPU sandbox.
A few examples of GPU code I got running.
All of these files were initially converted from Java classes and then further worked on so they don't have a very 'functional' structure yet.
There was initially more code where I tried out other libraries such as Aparapi, plain JOCL wrappers, etc, in the end I got rid of everything that didn't work.
The files here show both a 'plain' OpenCL operation with arrays and some more examples of CL-GL interoperation where the OpenCL kernel modifies a GL texture directly on the device, for display.
It also contains some webcam related code that may or may not work on your particular configuration, I've put in no attention in making it universal...
